package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.LiteGraphConverter

/**
 * Applies validated LiteGraph quick edits to a PREPARED ComfyChair API prompt.
 * Never writes to the imported source or permanently replaces its API template.
 * If a field cannot be tracked all the way through conversion, fail closed:
 * silently submitting an obsolete prompt is worse than refusing to submit.
 */
object LiteGraphExecutionPatch {
    data class Applied(val json: String, val changedFields: List<String>)
    private data class Edit(val nodeId: Int, val field: String, val old: Any, val updated: Any)

    fun apply(
        original: String,
        working: String,
        preparedPrompt: String,
        getNodeDefinition: (String) -> NodeTypeDefinition?
    ): Applied {
        val originalRoot = JSONObject(original)
        val workingRoot = JSONObject(working)
        val baseline = originalRoot.optJSONArray("nodes") ?: error("Original workflow is not LiteGraph")
        val editedNodes = workingRoot.optJSONArray("nodes") ?: error("Working workflow is not LiteGraph")
        require(baseline.length() == editedNodes.length()) { "Workflow structure changed; use the professional editor" }
        val edits = mutableListOf<Edit>()
        var replay = original
        for (i in 0 until baseline.length()) {
            val before = baseline.getJSONObject(i)
            val after = editedNodes.getJSONObject(i)
            require(before.getInt("id") == after.getInt("id")) { "Node ordering or identity changed" }
            val beforeNamed = before.optJSONObject("widgets_values_named")
            val afterNamed = after.optJSONObject("widgets_values_named")
            if (beforeNamed == null && afterNamed == null) continue
            require(beforeNamed != null && afterNamed != null) { "Named widget structure changed" }
            require(beforeNamed.length() == afterNamed.length()) { "Widget set changed" }
            for (field in beforeNamed.keys()) {
                require(afterNamed.has(field)) { "Widget key disappeared: $field" }
                val old = beforeNamed.get(field)
                val value = afterNamed.get(field)
                if (!equivalent(old, value)) {
                    require(before.optInt("mode", 0) == 0) {
                        "Cannot submit changes to bypassed/muted node #${before.getInt("id")}: $field"
                    }
                    require(old is String || old is Number || old is Boolean) {
                        "Only scalar widget values can be patched: $field"
                    }
                    require(value is String || value is Number || value is Boolean) {
                        "Only scalar widget values can be patched: $field"
                    }
                    val id = before.getInt("id")
                    replay = LiteGraphSourceEditor.changeTopLevelWidget(replay, id, field, value).json
                    edits += Edit(id, field, old, value)
                }
            }
        }
        // Replay via the same protected single-field editor as the UI. Any extra
        // change in the working copy (links, nodes, subgraphs, modes, metadata) fails.
        require(equivalent(JSONObject(replay), workingRoot)) {
            "Working copy contains changes beyond supported quick fields"
        }
        if (edits.isEmpty()) return Applied(preparedPrompt, emptyList())

        val converter = LiteGraphConverter(getNodeDefinition)
        val beforeApi = JSONObject(converter.convert(originalRoot).jsonContent).getJSONObject("nodes")
        val afterApi = JSONObject(converter.convert(workingRoot).jsonContent).getJSONObject("nodes")
        require(equivalent(beforeApi.keys().asSequence().toSet(), afterApi.keys().asSequence().toSet())) {
            "Node topology changed after conversion"
        }
        // Prove that converter output differs *only* in fields we intend to patch.
        val expectedAfter = JSONObject(beforeApi.toString())
        for (edit in edits) {
            val id = edit.nodeId.toString()
            val beforeInputs = expectedAfter.optJSONObject(id)?.optJSONObject("inputs")
                ?: error("Edited node #$id is not executable (missing node definition or bypassed)")
            val convertedInputs = afterApi.optJSONObject(id)?.optJSONObject("inputs")
                ?: error("Converted node #$id missing")
            require(beforeInputs.has(edit.field) && convertedInputs.has(edit.field)) {
                "Node #$id field ${edit.field} did not reach API conversion; check /object_info"
            }
            require(equivalent(beforeInputs.get(edit.field), edit.old) &&
                    equivalent(convertedInputs.get(edit.field), edit.updated)) {
                "Node #$id field ${edit.field} is not safely mapped through the converter"
            }
            beforeInputs.put(edit.field, edit.updated)
        }
        require(equivalent(expectedAfter, afterApi)) {
            "Conversion introduced unrelated changes; refusing to patch execution JSON"
        }

        val output = JSONObject(preparedPrompt)
        val promptNodes = output.optJSONObject("nodes")
            ?: error("Prepared ComfyChair prompt must contain an API 'nodes' object")
        for (edit in edits) {
            val target = promptNodes.optJSONObject(edit.nodeId.toString())?.optJSONObject("inputs")
                ?: error("Edited node #${edit.nodeId} was removed from the prepared prompt")
            require(target.has(edit.field) && target.get(edit.field) !is JSONArray &&
                    target.get(edit.field) !is JSONObject) {
                "Prepared field #${edit.nodeId}.${edit.field} is missing or linked; cannot overwrite"
            }
            target.put(edit.field, edit.updated)
        }
        return Applied(output.toString(), edits.map { "${it.nodeId}:${it.field}" })
    }

    private fun equivalent(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> {
            val ak = a.keys().asSequence().toSet()
            val bk = b.keys().asSequence().toSet()
            ak == bk && ak.all { equivalent(a.get(it), b.get(it)) }
        }
        a is JSONArray && b is JSONArray -> a.length() == b.length() &&
            (0 until a.length()).all { equivalent(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
}
