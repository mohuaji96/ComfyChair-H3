package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which `steps` node actually feeds the sampler.
 *
 * Official H3 workflows carry two step sources joined by `ComfySwitchNode`
 * (`on_true` -> 8, `on_false` -> 20, switch driven by a boolean primitive). Editing the value
 * that the switch does not select is silently ignored by the server, which is why "set 8, ran 20"
 * looked like a bug in the submission path.
 *
 * The switch state is read, never changed: the workflow keeps its original branch unless the user
 * edits the switch themselves in advanced settings.
 */
object H3StepsRouting {

    data class Route(
        val switchId: String,
        val switchValue: Boolean?,
        val activeNodeId: String?,
        val activeSteps: Double?,
        val inactiveNodeId: String?,
        val inactiveSteps: Double?
    )

    fun routes(source: String): List<Route> {
        val nodes = runCatching { H3WorkflowAdapter.nodes(JSONObject(source)) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Route>()
        nodes.keys().forEach { id ->
            val node = nodes.optJSONObject(id) ?: return@forEach
            if (node.optString("class_type") != "ComfySwitchNode") return@forEach
            val inputs = node.optJSONObject("inputs") ?: return@forEach
            val trueId = sourceId(inputs, "on_true") ?: return@forEach
            val falseId = sourceId(inputs, "on_false") ?: return@forEach
            val trueSteps = numericValue(nodes, trueId)
            val falseSteps = numericValue(nodes, falseId)
            // Only treat it as a steps switch when both sides are numeric primitives.
            if (trueSteps == null || falseSteps == null) return@forEach
            val flag = switchValue(nodes, sourceId(inputs, "switch"))
            out.add(
                Route(
                    switchId = id,
                    switchValue = flag,
                    // Without a readable switch state the active branch is unknown; never guess.
                    activeNodeId = when (flag) { true -> trueId; false -> falseId; null -> null },
                    activeSteps = when (flag) { true -> trueSteps; false -> falseSteps; null -> null },
                    inactiveNodeId = when (flag) { true -> falseId; false -> trueId; null -> null },
                    inactiveSteps = when (flag) { true -> falseSteps; false -> trueSteps; null -> null }
                )
            )
        }
        return out.sortedBy { it.switchId.toIntOrNull() ?: Int.MAX_VALUE }
    }

    /** The steps that will really run, or null when no switch is understood. */
    fun activeSteps(source: String): Double? =
        routes(source).firstOrNull { it.switchValue != null }?.activeSteps

    private fun sourceId(inputs: JSONObject, key: String): String? =
        (inputs.opt(key) as? JSONArray)?.optString(0)?.takeIf { it.isNotEmpty() }

    private fun numericValue(nodes: JSONObject, id: String): Double? {
        val node = nodes.optJSONObject(id) ?: return null
        val cls = node.optString("class_type")
        if (!cls.startsWith("Primitive")) return null
        val v = node.optJSONObject("inputs")?.opt("value")
        return (v as? Number)?.toDouble()
    }

    /** Boolean literal, or a PrimitiveBoolean node reached through the switch link. */
    private fun switchValue(nodes: JSONObject, id: String?): Boolean? {
        if (id == null) return null
        val node = nodes.optJSONObject(id) ?: return null
        val v = node.optJSONObject("inputs")?.opt("value")
        return when (v) {
            is Boolean -> v
            is String -> when (v.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            is Number -> v.toDouble() != 0.0
            else -> null
        }
    }
}
