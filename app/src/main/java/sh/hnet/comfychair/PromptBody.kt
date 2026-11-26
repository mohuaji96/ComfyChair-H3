package sh.hnet.comfychair

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Builds the body for `POST /prompt`.
 *
 * Two shapes reach this point: the stored workflow form `{"name":…, "nodes":{…}}` and an already
 * flattened node map produced by the H3 submission planner. Both must end up as
 * `{"prompt": <node map>, "client_id": …}`.
 *
 * `JSONObject.put(key, null)` removes the key, so passing the result of `optJSONObject("nodes")`
 * straight through silently produced a body without `prompt`. The server rejects that, and custom
 * nodes hooked on `on_prompt` report it as `KeyError: 'prompt'`.
 */
internal object PromptBody {

    fun build(workflowJson: String, clientId: String, front: Boolean = false, requestId: String? = null): JSONObject {
        val root = JSONObject(workflowJson)
        val nodes = root.optJSONObject("nodes") ?: root
        require(nodes.length() > 0) { "提交内容为空：工作流里没有节点" }
        return JSONObject().apply {
            put("prompt", nodes)
            put("client_id", clientId)
            if (!requestId.isNullOrBlank()) put("extra_data", JSONObject().put("comfychair_request_id", requestId))
            if (front) put("front", true)
        }
    }
    /** Stable fingerprint used to reconcile a POST /prompt whose response was lost. */
    fun fingerprint(prompt: JSONObject): String {
        fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
                "${JSONObject.quote(key)}:${canonical(value.opt(key))}"
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index -> canonical(value.opt(index)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical(prompt).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

}
