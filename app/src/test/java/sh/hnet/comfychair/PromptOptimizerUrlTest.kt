package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import sh.hnet.comfychair.model.ApiFormat
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.net.PromptOptimizer

/**
 * URL joining is the part users cannot debug from the app, so every vendor shape that has
 * bitten us gets a case here.
 */
class PromptOptimizerUrlTest {

    private fun provider(url: String, format: ApiFormat, model: String = "m"): ApiProvider =
        ApiProvider.create(id = "t", name = "", systemPrompt = "").copy(
            baseUrl = url,
            format = format,
            model = model
        )

    @Test
    fun `bare host gets the default version`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            PromptOptimizer.endpointFor(provider("https://api.openai.com", ApiFormat.OPENAI_CHAT))
        )
    }

    @Test
    fun `pasted v1 is not doubled`() {
        assertEquals(
            "https://api.siliconflow.cn/v1/chat/completions",
            PromptOptimizer.endpointFor(
                provider("https://api.siliconflow.cn/v1", ApiFormat.OPENAI_CHAT)
            )
        )
    }

    @Test
    fun `pasted full endpoint collapses to the root`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            PromptOptimizer.endpointFor(
                provider(
                    "https://openrouter.ai/api/v1/chat/completions",
                    ApiFormat.OPENAI_CHAT
                )
            )
        )
    }

    @Test
    fun `zhipu keeps its own v4 segment`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            PromptOptimizer.endpointFor(
                provider("https://open.bigmodel.cn/api/paas/v4", ApiFormat.OPENAI_CHAT)
            )
        )
    }

    @Test
    fun `anthropic root and versioned url agree`() {
        val expected = "https://api.anthropic.com/v1/messages"
        assertEquals(
            expected,
            PromptOptimizer.endpointFor(provider("https://api.anthropic.com", ApiFormat.ANTHROPIC))
        )
        assertEquals(
            expected,
            PromptOptimizer.endpointFor(
                provider("https://api.anthropic.com/v1", ApiFormat.ANTHROPIC)
            )
        )
    }

    @Test
    fun `gemini carries the model id`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            PromptOptimizer.endpointFor(
                provider(
                    "https://generativelanguage.googleapis.com",
                    ApiFormat.GEMINI,
                    "models/gemini-2.0-flash"
                )
            )
        )
    }

    @Test
    fun `gemini pasted version is not doubled`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            PromptOptimizer.endpointFor(
                provider(
                    "https://generativelanguage.googleapis.com/v1beta",
                    ApiFormat.GEMINI,
                    "gemini-2.0-flash"
                )
            )
        )
    }

    @Test
    fun `responses path replaces the chat path`() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            PromptOptimizer.endpointFor(
                provider("https://api.openai.com/v1", ApiFormat.OPENAI_RESPONSES)
            )
        )
    }

    @Test
    fun `scheme is assumed when omitted`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            PromptOptimizer.endpointFor(provider("api.deepseek.com", ApiFormat.OPENAI_CHAT))
        )
    }

    @Test
    fun `empty url produces no endpoint`() {
        assertEquals("", PromptOptimizer.endpointFor(provider("   ", ApiFormat.OPENAI_CHAT)))
    }

    @Test
    fun `active template rules are included in the final request body`() {
        val provider = provider("https://api.example.com", ApiFormat.OPENAI_CHAT)
            .copy(systemPrompt = "LEGACY PROVIDER RULE")
        val body = JSONObject(
            PromptOptimizer.bodyFor(provider, "test input", forTest = false, rules = "ACTIVE TEMPLATE RULE")
        )
        val messages = body.getJSONArray("messages")
        val userContent = messages.getJSONObject(messages.length() - 1).getString("content")
        assertTrue(userContent.contains("ACTIVE TEMPLATE RULE"))
        assertTrue(!userContent.contains("LEGACY PROVIDER RULE"))
    }

}
