package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers which node type sits behind each node id of a submitted job.
 *
 * The server reports progress and execution by node id only ("147 is running"), and the id means
 * nothing without the graph it came from. The graph is in hand exactly once -- at submit time --
 * so it is captured here and looked up again when the socket reports a step.
 *
 * Bounded: a phone that queues all evening must not accumulate every graph it ever sent.
 */
object NodeTypeIndex {

    private const val MAX_ENTRIES = 24

    private val index = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Map<String, String>>
        ): Boolean = size > MAX_ENTRIES
    }

    private val lock = Any()

    /** Called once, right after the server hands back a prompt id. */
    fun record(promptId: String, workflowJson: String) {
        if (promptId.isBlank()) return
        val nodes = parse(workflowJson) ?: return
        synchronized(lock) { index[promptId] = nodes }
    }

    fun classOf(promptId: String?, nodeId: String?): String? {
        if (promptId.isNullOrBlank() || nodeId.isNullOrBlank()) return null
        return synchronized(lock) { index[promptId]?.get(nodeId) }
    }

    fun forget(promptId: String?) {
        if (promptId.isNullOrBlank()) return
        synchronized(lock) { index.remove(promptId) }
    }

    /**
     * Both shapes the app sends are accepted: the API form (`{"id": {"class_type": …}}`) and the
     * LiteGraph form (`{"nodes": [{"id": 3, "type": …}]}`). Anything else yields null, which just
     * means the stage falls back to "处理中" -- never a wrong name.
     */
    private fun parse(workflowJson: String): Map<String, String>? {
        val root = runCatching { JSONObject(workflowJson) }.getOrNull() ?: return null
        val out = HashMap<String, String>()

        fun readApi(nodes: JSONObject) {
            nodes.keys().forEach { id ->
                val node = nodes.optJSONObject(id) ?: return@forEach
                node.optString("class_type").takeIf { it.isNotBlank() }?.let { out[id] = it }
            }
        }

        fun readLite(array: JSONArray) {
            for (i in 0 until array.length()) {
                val node = array.optJSONObject(i) ?: continue
                val id = node.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                node.optString("type").takeIf { it.isNotBlank() }?.let { out[id] = it }
            }
        }

        root.optJSONObject("prompt")?.let { readApi(it) }
        root.optJSONObject("nodes")?.let { readApi(it) }
        root.optJSONArray("nodes")?.let { readLite(it) }
        if (out.isEmpty()) readApi(root)

        return out.ifEmpty { null }
    }
}
