package sh.hnet.comfychair.workflow

import org.json.JSONObject

/**
 * Reads a `/history/{prompt_id}` entry.
 *
 * ComfyUI answers with `{"<prompt_id>": {"status": {...}, "outputs": {...}}}` while a job is
 * still running, and with an empty object once the id is unknown (server restarted, history
 * trimmed). The three cases must stay distinguishable: a missing id is not a failure.
 */
object JobRecovery {

    enum class State { QUEUED, RUNNING, COMPLETED, FAILED, UNKNOWN }

    data class Snapshot(val state: State, val outputs: List<String>, val message: String?)

    fun parse(historyJson: String, promptId: String): Snapshot {
        val root = runCatching { JSONObject(historyJson) }.getOrNull()
            ?: return Snapshot(State.UNKNOWN, emptyList(), "无法解析 /history 响应")
        val entry = root.optJSONObject(promptId)
            ?: return Snapshot(State.UNKNOWN, emptyList(), "服务器历史中没有该 prompt_id")
        val status = entry.optJSONObject("status")
        val completed = status?.optBoolean("completed") == true
        val statusStr = status?.optString("status_str").orEmpty()
        val outputs = collectOutputs(entry.optJSONObject("outputs"))
        return when {
            statusStr == "error" -> Snapshot(State.FAILED, outputs, errorMessage(status))
            completed -> Snapshot(State.COMPLETED, outputs, null)
            statusStr == "success" -> Snapshot(State.COMPLETED, outputs, null)
            outputs.isNotEmpty() -> Snapshot(State.RUNNING, outputs, null)
            else -> Snapshot(State.RUNNING, emptyList(), null)
        }
    }

    private fun errorMessage(status: JSONObject?): String? {
        val messages = status?.optJSONArray("messages") ?: return null
        for (i in 0 until messages.length()) {
            val pair = messages.optJSONArray(i) ?: continue
            if (pair.optString(0) == "execution_error") {
                val detail = pair.optJSONObject(1)
                val node = detail?.optString("node_id").orEmpty()
                val text = detail?.optString("exception_message").orEmpty()
                if (node.isNotEmpty() || text.isNotEmpty()) return "#$node $text".trim()
            }
        }
        return null
    }

    /** Every filename a finished job produced, across all output nodes. */
    private fun collectOutputs(outputs: JSONObject?): List<String> {
        if (outputs == null) return emptyList()
        val files = mutableListOf<String>()
        outputs.keys().forEach { nodeId ->
            val node = outputs.optJSONObject(nodeId) ?: return@forEach
            for (key in listOf("images", "gifs", "videos")) {
                val arr = node.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val name = item.optString("filename")
                    if (name.isNotEmpty()) files.add(name)
                }
            }
        }
        return files
    }
}
