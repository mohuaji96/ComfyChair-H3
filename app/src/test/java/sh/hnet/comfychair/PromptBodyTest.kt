package sh.hnet.comfychair

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The body of `POST /prompt` must always carry a `prompt` key.
 *
 * A missing key is not a cosmetic issue: ComfyUI rejects the request, and custom nodes hooked on
 * `on_prompt` (ComfyUI-Impact-Pack and friends) log `KeyError: 'prompt'` for every attempt. That is
 * exactly what the server console showed, so this stays covered.
 */
class PromptBodyTest {

    private fun payload() = File("src/main/res/raw/itv_h3_mobile_r2v.json").readText()

    @Test
    fun bareNodeMapIsWrappedUnderPrompt() {
        val bare = H3PlanBareMap.of(payload())
        val body = PromptBody.build(bare, "client-1")
        assertTrue("prompt key must exist", body.has("prompt"))
        val prompt = body.getJSONObject("prompt")
        assertFalse("the display wrapper must not leak", prompt.has("nodes"))
        assertTrue(prompt.keys().asSequence().any { prompt.getJSONObject(it).has("class_type") })
        assertEquals("client-1", body.getString("client_id"))
    }

    @Test
    fun wrappedWorkflowStillWorks() {
        val inner = JSONObject(payload()).getJSONObject("nodes")
        val wrapped = JSONObject().put("name", "H3").put("nodes", inner).toString()
        val body = PromptBody.build(wrapped, "client-2", front = true)
        val prompt = body.getJSONObject("prompt")
        assertTrue(prompt.keys().asSequence().any { prompt.getJSONObject(it).has("class_type") })
        assertTrue(body.getBoolean("front"))
    }

    @Test
    fun repeatedWorkflowHasSameFingerprintButDifferentSubmissionTokens() {
        val workflow = H3PlanBareMap.of(payload())
        val first = PromptBody.build(workflow, "client-1", requestId = "request-1")
        val second = PromptBody.build(workflow, "client-1", requestId = "request-2")
        assertEquals("request-1", first.getJSONObject("extra_data").getString("comfychair_request_id"))
        assertEquals("request-2", second.getJSONObject("extra_data").getString("comfychair_request_id"))
        assertEquals(PromptBody.fingerprint(first.getJSONObject("prompt")), PromptBody.fingerprint(second.getJSONObject("prompt")))
        assertFalse(first.getJSONObject("prompt").has("extra_data"))
    }

    @Test
    fun emptySubmissionIsRejectedBeforeTheRequest() {
        val failed = runCatching { PromptBody.build("{\"nodes\":{}}", "c") }
        assertTrue(failed.isFailure)
    }

    @Test
    fun fingerprintIsIndependentOfObjectKeyOrder() {
        val first = JSONObject("""{"b":2,"a":{"y":true,"x":1}}""")
        val second = JSONObject("""{"a":{"x":1,"y":true},"b":2}""")
        assertEquals(PromptBody.fingerprint(first), PromptBody.fingerprint(second))
    }

}

/** Minimal helper so the test does not depend on Android-only classes. */
private object H3PlanBareMap {
    fun of(source: String): String {
        val root = JSONObject(source)
        val nodes = root.optJSONObject("nodes") ?: root
        return nodes.toString()
    }
}
