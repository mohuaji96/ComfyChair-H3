package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Non-destructive edits on an imported LiteGraph document.
 *
 * This intentionally does NOT flatten subgraphs or translate to API prompt JSON.
 * The untouched original is stored separately by [LiteGraphSourceStore].
 * Unsupported widget mappings fail closed instead of silently writing a wrong index.
 */
object LiteGraphSourceEditor {
    data class WidgetChange(val json: String, val oldValue: Any, val nodeId: Int, val key: String)

    fun changeTopLevelWidget(source: String, nodeId: Int, key: String, newValue: Any): WidgetChange {
        require(key.isNotBlank()) { "Widget key must not be empty" }
        val root = JSONObject(source)
        val nodes = root.optJSONArray("nodes") ?: error("Not a LiteGraph workflow")
        val node = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
            .singleOrNull { it.optInt("id", -1) == nodeId }
            ?: error("Node id $nodeId not found or ambiguous")
        val named = node.optJSONObject("widgets_values_named")
            ?: error("Node $nodeId does not contain named widget values")
        require(named.has(key)) { "Widget '$key' is not present on node $nodeId" }
        val old = named.get(key)
        require(old != JSONObject.NULL) { "Null widgets are not supported for point editing" }
        // Never overwrite an input which is provided by an upstream connection.
        val inputs = node.optJSONArray("inputs")
        val input = if (inputs == null) null else (0 until inputs.length())
            .mapNotNull { inputs.optJSONObject(it) }
            .firstOrNull { it.optString("name") == key }
        require(input == null || input.isNull("link")) { "Widget '$key' is connected: edit its source node instead" }

        val positional = node.opt("widgets_values")
        when (positional) {
            is JSONObject -> {
                require(positional.has(key)) { "Object widget '$key' missing; refusing ambiguous edit" }
                positional.put(key, newValue)
            }
            is JSONArray -> {
                // ComfyUI widget slots usually appear in inputs[] with widget metadata.
                val widgetNames = if (inputs == null) emptyList() else (0 until inputs.length())
                    .mapNotNull { inputs.optJSONObject(it) }
                    .filter { it.has("widget") && !it.isNull("widget") }
                    .map { it.optString("name") }
                val idx = widgetNames.indexOf(key)
                val safeIndex = if (idx >= 0) idx else if (positional.length() == 1 && named.length() == 1) 0 else -1
                require(safeIndex in 0 until positional.length()) {
                    "Cannot safely map '$key' to widgets_values index on node $nodeId"
                }
                val positionalOld = positional.get(safeIndex)
                require(samePrimitiveValue(old, positionalOld)) {
                    "Widget '$key' positional value disagrees with named value; refusing ambiguous edit"
                }
                positional.put(safeIndex, newValue)
            }
            null, JSONObject.NULL -> Unit // named-only widget
            else -> error("Unsupported widgets_values representation")
        }
        named.put(key, newValue)
        return WidgetChange(root.toString(), old, nodeId, key)
    }

    /** Numeric widgets may be represented as Int vs Double by different JSON producers. */
    private fun samePrimitiveValue(a: Any, b: Any): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
}
