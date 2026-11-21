package sh.hnet.comfychair.queue

import org.json.JSONObject
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.FieldMappingAnalyzer
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.WorkflowParser

/**
 * The prompts of jobs in the server queue, read from the workflows the queue hands out (the server
 * shares every client's job, its prompts included). Found the way an import finds a workflow's
 * positive prompt; kept in memory only, by job (a job's prompt never changes), never logged.
 */
object JobPrompts {

    private const val MAX_KEPT = 200

    // Job id -> prompt ("" for none found)
    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_KEPT
        }
    )

    /** The positive prompt of the job [promptId] whose workflow is [workflow]; null when none is found. */
    fun of(promptId: String, workflow: JSONObject?, registry: NodeTypeRegistry = ConnectionManager.nodeTypeRegistry): String? {
        cache[promptId]?.let { return it.ifEmpty { null } }
        val prompt = workflow?.let { positivePrompt(it, registry) }.orEmpty()
        cache[promptId] = prompt
        return prompt.ifEmpty { null }
    }

    /** Already-known prompt of a job, without reading its workflow. */
    fun known(promptId: String): String? = cache[promptId]?.takeIf { it.isNotEmpty() }

    /** Prompt of a workflow we are about to submit; nothing is cached, the job records its own. */
    fun extract(workflow: JSONObject?, registry: NodeTypeRegistry = ConnectionManager.nodeTypeRegistry): String? =
        workflow?.let { positivePrompt(it, registry) }

    fun clear(promptId: String) {
        cache.remove(promptId)
    }

    private fun positivePrompt(workflow: JSONObject, registry: NodeTypeRegistry): String? {
        // The import's own analysis first: it follows the wiring from the sampler down, which is
        // the only way to tell 正向 from 负向 when a workflow has several text nodes.
        runCatching {
            val graph = WorkflowParser().parse(workflow.toString())
            FieldMappingAnalyzer.createPromptFieldMappings(graph, registry)["positive_text"].orEmpty()
                .firstNotNullOfOrNull { (it.currentValue as? String)?.trim()?.takeIf(String::isNotEmpty) }
        }.getOrNull()?.let { return it }

        // Then a plain scan: the queue hands out raw API graphs, and a server that never sent its
        // node list (or a custom text node) leaves the analysis above with nothing to work on.
        return longestTextNode(workflow)
    }

    /** Longest string on any text-carrying node — a caption is longer than everything else here. */
    private fun longestTextNode(workflow: JSONObject): String? {
        var best = ""
        val ids = workflow.keys()
        while (ids.hasNext()) {
            val node = workflow.optJSONObject(ids.next()) ?: continue
            val cls = node.optString("class_type")
            val inputs = node.optJSONObject("inputs") ?: continue
            val looksLikePrompt =
                cls.contains("CLIPTextEncode", ignoreCase = true) || cls.contains("TextEncode", ignoreCase = true)
            val keys = inputs.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = inputs.optString(key)
                if (value.isBlank() || value.length < 12) continue
                if (value.startsWith("{{") || value.startsWith("[")) continue
                val textField = key.equals("text", true) || key.equals("prompt", true) ||
                    key.equals("positive", true) || key.equals("positive_prompt", true)
                if (!looksLikePrompt && !textField) continue
                if (value.length > best.length) best = value
            }
        }
        return best.trim().takeIf { it.isNotEmpty() }
    }
}
