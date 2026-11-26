package sh.hnet.comfychair

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.ApiFormat
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.net.PromptOptimizer

/**
 * Every vendor spells "do not think" differently, and a wrong guess is a 400 the user cannot
 * read. Each host that has bitten us gets a case here.
 */
class PromptOptimizerThinkingTest {

    private fun provider(
        url: String,
        model: String = "m",
        format: ApiFormat = ApiFormat.OPENAI_CHAT,
        thinking: Boolean = false
    ): ApiProvider = ApiProvider.create(id = "t", name = "", systemPrompt = "").copy(
        baseUrl = url,
        format = format,
        model = model,
        thinking = thinking
    )

    private fun body(provider: ApiProvider): JSONObject = JSONObject(
        PromptOptimizer.bodyFor(provider, "in", forTest = false, rules = "r")
    )

    private fun geminiConfig(provider: ApiProvider): JSONObject =
        body(provider).getJSONObject("generationConfig")

    @Test
    fun `zhipu gets the thinking flag`() {
        val json = body(provider("https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air"))
        assertEquals("disabled", json.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun `the host wins over the model name on nvidia`() {
        // The same GLM is served by Zhipu and by NVIDIA, and only the endpoint knows which
        // field it accepts. This exact pair used to be sent the wrong one.
        val json = body(provider("https://integrate.api.nvidia.com/v1", "z-ai/glm-5.3-flash"))
        assertFalse(json.has("thinking"))
        assertEquals("low", json.getString("reasoning_effort"))
    }

    @Test
    fun `the host wins over the model name on openrouter`() {
        val json = body(provider("https://openrouter.ai/api/v1", "qwen/qwen3-32b"))
        assertFalse(json.has("enable_thinking"))
        assertEquals("none", json.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun `groq can only lower gpt-oss`() {
        val json = body(provider("https://api.groq.com/openai/v1", "openai/gpt-oss-120b"))
        assertEquals("low", json.getString("reasoning_effort"))
    }

    @Test
    fun `groq can stop qwen outright`() {
        val json = body(provider("https://api.groq.com/openai/v1", "qwen/qwen3.8-27b"))
        assertEquals("none", json.getString("reasoning_effort"))
    }

    @Test
    fun `siliconflow uses the boolean`() {
        val json = body(provider("https://api.siliconflow.cn/v1", "Qwen/Qwen3-8B"))
        assertFalse(json.getBoolean("enable_thinking"))
    }

    @Test
    fun `an unknown host is left completely alone`() {
        val json = body(provider("https://relay.example.com/v1", "glm-4.5-air"))
        assertFalse(json.has("thinking"))
        assertFalse(json.has("reasoning"))
        assertFalse(json.has("reasoning_effort"))
        assertFalse(json.has("enable_thinking"))
        assertFalse(PromptOptimizer.thinkingSupported(provider("https://relay.example.com/v1")))
    }

    @Test
    fun `turning it on asks for thinking explicitly`() {
        val json = body(provider("https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air", thinking = true))
        assertEquals("enabled", json.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun `gemini 3 gets a level because the budget field is rejected`() {
        val config = geminiConfig(
            provider("https://generativelanguage.googleapis.com", "gemini-3.0-flash", ApiFormat.GEMINI)
        )
        assertEquals("minimal", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test
    fun `gemini 2_5 pro keeps its own default`() {
        val config = geminiConfig(
            provider("https://generativelanguage.googleapis.com", "gemini-2.5-pro", ApiFormat.GEMINI)
        )
        assertFalse(config.has("thinkingConfig"))
    }

    @Test
    fun `gemini 2_5 flash gets a zero budget`() {
        val config = geminiConfig(
            provider("https://generativelanguage.googleapis.com", "gemini-2.5-flash", ApiFormat.GEMINI)
        )
        assertEquals(0, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test
    fun `gemini leaves the config alone when thinking is on`() {
        val config = geminiConfig(
            provider(
                "https://generativelanguage.googleapis.com",
                "gemini-2.5-flash",
                ApiFormat.GEMINI,
                thinking = true
            )
        )
        assertFalse(config.has("thinkingConfig"))
    }

    @Test
    fun `mistral never receives stream_options`() {
        val json = JSONObject(
            PromptOptimizer.bodyFor(
                provider("https://api.mistral.ai/v1", "mistral-large-latest"),
                "in",
                forTest = false,
                rules = "r",
                stream = true
            )
        )
        assertTrue(json.getBoolean("stream"))
        assertFalse(json.has("stream_options"))
    }

    @Test
    fun `providers saved by the older build keep their behaviour`() {
        val old = JSONObject().put("id", "a").put("baseUrl", "https://x.y").put("model", "m")
            .put("fastMode", true)
        assertFalse(ApiProvider.fromJson(old, "fallback").thinking)

        val kept = JSONObject().put("id", "b").put("baseUrl", "https://x.y").put("model", "m")
            .put("fastMode", false)
        assertTrue(ApiProvider.fromJson(kept, "fallback").thinking)
    }

    @Test
    fun `a fresh provider does not think`() {
        assertFalse(ApiProvider.create(id = "n", name = "", systemPrompt = "").thinking)
    }
}
