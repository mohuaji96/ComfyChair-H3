package sh.hnet.comfychair

import org.json.JSONObject

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

    fun build(workflowJson: String, clientId: String, front: Boolean = false): JSONObject {
        val root = JSONObject(workflowJson)
        val nodes = root.optJSONObject("nodes") ?: root
        require(nodes.length() > 0) { "提交内容为空：工作流里没有节点" }
        return JSONObject().apply {
            put("prompt", nodes)
            put("client_id", clientId)
            if (front) put("front", true)
        }
    }
}
