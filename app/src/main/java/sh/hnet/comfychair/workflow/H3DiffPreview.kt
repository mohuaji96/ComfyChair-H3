package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Human-readable preview of what the H3 sheet will actually submit.
 *
 * Built from the same [H3ApiPatch.PatchResult] that produced the JSON plus the frontend
 * nodes [H3PromptCompiler] removed, so the list cannot drift from the real payload.
 */
object H3DiffPreview {
    enum class Kind { FIELD, NODE_ADD, LINK_REMOVE, REMOVED }

    data class Line(val title: String, val detail: String, val kind: Kind)

    data class Preview(
        val lines: List<Line>,
        val nodesBefore: Int,
        val nodesAfter: Int
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()
        val nodeDelta: Int get() = nodesAfter - nodesBefore
    }

    private const val MAX_TEXT = 48

    fun build(
        patch: H3ApiPatch.PatchResult,
        removed: List<Pair<String, String>>,
        nodesBefore: Int,
        nodesAfter: Int
    ): Preview {
        val lines = ArrayList<Line>(patch.changes.size + removed.size)
        patch.changes.forEach { change ->
            when (change.kind) {
                "node-add" -> lines += Line(
                    title = "新增节点 #${change.nodeId}",
                    detail = "${change.after ?: ""}（参考图接口）",
                    kind = Kind.NODE_ADD
                )
                "link-remove" -> lines += Line(
                    title = "#${change.nodeId}.${change.input}",
                    detail = "解除参考图接口连线（原节点保留）",
                    kind = Kind.LINK_REMOVE
                )
                else -> lines += Line(
                    title = "#${change.nodeId}.${change.input}",
                    detail = "${render(change.before)} → ${render(change.after)}",
                    kind = Kind.FIELD
                )
            }
        }
        removed.forEach { (id, type) ->
            lines += Line(title = "移除前端辅助节点 #$id", detail = type, kind = Kind.REMOVED)
        }
        return Preview(lines, nodesBefore, nodesAfter)
    }

    private fun render(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "（空）"
        is JSONArray -> "连线[${value.optString(0)}:${value.optInt(1)}]"
        is Boolean -> if (value) "开" else "关"
        is String -> {
            val flat = value.replace("\n", "⏎")
            if (flat.length <= MAX_TEXT) flat else flat.take(MAX_TEXT) + "…"
        }
        else -> value.toString()
    }
}
