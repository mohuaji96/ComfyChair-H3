package sh.hnet.comfychair.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.ApiFormat
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.util.DebugLogger
import java.util.concurrent.TimeUnit

/**
 * Talks to a user-configured text model and returns the rewritten prompt.
 *
 * Four wire formats are supported because that is what the market actually speaks:
 * OpenAI chat completions covers relays, DeepSeek, Kimi, Qwen, Zhipu and OpenRouter;
 * the other three cover the vendors that refuse to be OpenAI-compatible.
 */
object PromptOptimizer {

    private const val TAG = "PromptOptimizer"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    /** Enough room for a reasoning model to think and still emit "OK". */
    private const val TEST_MAX_TOKENS = 256
    private val PROMPT_BLOCK = Regex("<prompt>([\\s\\S]*?)</prompt>", RegexOption.IGNORE_CASE)
    private val VERSION_RE = Regex("/v\\d+(?:beta|alpha|preview)?$", RegexOption.IGNORE_CASE)

    sealed class Outcome {
        data class Ok(val text: String, val elapsedMs: Long) : Outcome()
        /** [message] is safe to show; [detail] keeps the raw body for the log. */
        data class Error(val message: String, val detail: String? = null) : Outcome()
    }

    // ---------------------------------------------------------------- url handling

    /**
     * Strips what the user pasted down to the root so [endpointFor] can append its own path.
     * Accepts a bare host, a host with `/v1`, or a full endpoint URL.
     */
    fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            value = "https://$value"
        }
        value = value.trimEnd('/')

        // Drop a pasted endpoint path so only the root (plus an optional version segment) stays.
        // "/chat/completions" has to be tried before "/completions".
        val endpointTails = listOf("/chat/completions", "/completions", "/responses", "/messages")
        endpointTails.firstOrNull { value.endsWith(it, true) }
            ?.let { value = value.dropLast(it.length) }

        // Gemini callers often paste the documented path, which already carries the model name.
        val modelMarker = "/models/"
        val idx = value.indexOf(modelMarker, ignoreCase = true)
        if (idx >= 0 && value.substring(idx + modelMarker.length).contains(':')) {
            value = value.substring(0, idx)
        }
        return value.trimEnd('/')
    }

    /** Version segment already present at the end of the path, e.g. "/v1", "/v4", "/v1beta". */
    private fun versionOf(base: String): String? {
        val schemeEnd = base.indexOf("://")
        val pathStart = base.indexOf('/', if (schemeEnd >= 0) schemeEnd + 3 else 0)
        if (pathStart < 0) return null
        return VERSION_RE.find(base.substring(pathStart))?.value
    }

    fun endpointFor(provider: ApiProvider): String {
        val raw = provider.baseUrl.trim()
        if (raw.isEmpty()) return ""

        // A hand-written path is used verbatim: Azure puts the deployment in the middle of the
        // URL and appends a query string, which no suffix guess can reconstruct.
        val custom = provider.apiPath.trim()
        if (custom.isNotEmpty()) {
            var base = raw
            if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
                base = "https://$base"
            }
            base = base.trimEnd('/')
            val path = if (custom.startsWith("/")) custom else "/$custom"
            return base + path
        }

        val base = normalizeBaseUrl(raw)
        val tail = when (provider.format) {
            ApiFormat.GEMINI -> provider.format.tail.format(geminiModelId(provider.model))
            else -> provider.format.tail
        }
        // Zhipu serves /api/paas/v4/chat/completions and 404s on /v4/v1/chat/completions, so the
        // version already in the URL must win over the format's default.
        val prefix = if (versionOf(base) != null) "" else provider.format.defaultVersion
        return base + prefix + tail
    }

    private fun geminiModelId(model: String): String =
        model.trim().removePrefix("models/").removePrefix("/")

    // ---------------------------------------------------------------- request bodies

    private fun languageDirective(english: Boolean): String = if (english) {
        "OUTPUT LANGUAGE: English.\n" +
            "Write the entire prompt body in English, including every material, garment and " +
            "camera phrase. Do not keep any source-language token."
    } else {
        "OUTPUT LANGUAGE: 简体中文。\n" +
            "提示词正文全部用简体中文书写，包括材质、服装、镜头等所有描述。" +
            "不要保留英文短语，也不要输出中英混排。"
    }

    /**
     * The rules ride inside the user message rather than a separate system role: small models
     * routinely ignore system prompts, and the ones that do honour it behave the same either
     * way. The input therefore has to come last, after the rules were read.
     */
    private fun systemFor(provider: ApiProvider, forTest: Boolean = false): String =
        if (forTest) "You are a connectivity check. Reply with the single word OK." else ""

    private fun userFor(provider: ApiProvider, input: String, forTest: Boolean = false, rules: String? = null): String {
        if (forTest) return "ping"
        return buildString {
            append("先完整读完下面的工作规则，再处理最后一节的输入。规则优先于你的默认习惯。\n\n")
            append("=== BEGIN RULES ===\n")
            // Rules come from the template library now; the provider field is a legacy fallback.
            append((rules ?: provider.systemPrompt).trimEnd())
            append("\n=== END RULES ===\n\n")
            append(languageDirective(provider.english))
            append("\n\n=== 待优化输入 ===\n")
            append(input.trim())
        }
    }

    private fun bodyFor(provider: ApiProvider, input: String, forTest: Boolean): String {
        val system = systemFor(provider, forTest)
        val user = userFor(provider, input, forTest)
        val maxTokens = if (forTest) TEST_MAX_TOKENS else provider.maxTokens
        return when (provider.format) {
            ApiFormat.OPENAI_CHAT -> JSONObject().apply {
                put("model", provider.model.trim())
                put("messages", JSONArray().apply {
                    if (system.isNotBlank()) {
                        put(JSONObject().put("role", "system").put("content", system))
                    }
                    put(JSONObject().put("role", "user").put("content", user))
                })
                put("temperature", provider.temperature.toDouble())
                put("max_tokens", maxTokens)
                put("stream", false)
            }.toString()

            ApiFormat.OPENAI_RESPONSES -> JSONObject().apply {
                put("model", provider.model.trim())
                if (system.isNotBlank()) put("instructions", system)
                put("input", user)
                put("temperature", provider.temperature.toDouble())
                put("max_output_tokens", maxTokens)
            }.toString()

            ApiFormat.ANTHROPIC -> JSONObject().apply {
                put("model", provider.model.trim())
                if (system.isNotBlank()) put("system", system)
                put("max_tokens", maxTokens)
                put("temperature", provider.temperature.toDouble())
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", user))
                })
            }.toString()

            ApiFormat.GEMINI -> JSONObject().apply {
                if (system.isNotBlank()) {
                    put("system_instruction", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", system))
                    ))
                }
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", user)))
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", provider.temperature.toDouble())
                    put("maxOutputTokens", maxTokens)
                })
            }.toString()
        }
    }

    private fun requestFor(provider: ApiProvider, apiKey: String, body: String): Request {
        val builder = Request.Builder()
            .url(endpointFor(provider))
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")

        when (provider.format) {
            ApiFormat.ANTHROPIC -> {
                if (apiKey.isNotBlank()) builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            ApiFormat.GEMINI -> {
                if (apiKey.isNotBlank()) builder.header("x-goog-api-key", apiKey)
            }
            else -> {
                if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            }
        }
        return builder.build()
    }

    // ---------------------------------------------------------------- response parsing

    private fun parse(provider: ApiProvider, raw: String, allowReasoning: Boolean = false): String? = try {
        val json = JSONObject(raw)
        when (provider.format) {
            ApiFormat.OPENAI_CHAT -> {
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                val message = choice?.optJSONObject("message")
                // Reasoning models leave `content` empty and put everything in
                // `reasoning_content`, which is still a usable proof of life for a test call.
                // Never fall back to reasoning_content here: a thinking model that ran out of
                // budget returns empty content, and showing its thoughts as the answer is worse
                // than an honest error.
                message?.let { readContent(it.opt("content")) }?.takeIf { it.isNotBlank() }
                    ?: if (allowReasoning) {
                        message?.optString("reasoning_content")?.takeIf { it.isNotBlank() }
                    } else null
                    ?: choice?.optString("text")?.takeIf { it.isNotBlank() }
            }

            ApiFormat.OPENAI_RESPONSES -> {
                json.optString("output_text").takeIf { it.isNotBlank() }
                    ?: json.optJSONArray("output")?.let { output ->
                        buildString {
                            for (i in 0 until output.length()) {
                                val content = output.optJSONObject(i)
                                    ?.optJSONArray("content") ?: continue
                                for (j in 0 until content.length()) {
                                    val text = content.optJSONObject(j)?.optString("text").orEmpty()
                                    if (text.isNotEmpty()) append(text).append('\n')
                                }
                            }
                        }.trim().takeIf { it.isNotEmpty() }
                    }
            }

            ApiFormat.ANTHROPIC -> json.optJSONArray("content")?.let { content ->
                buildString {
                    for (i in 0 until content.length()) {
                        val block = content.optJSONObject(i) ?: continue
                        if (block.optString("type") == "text") append(block.optString("text"))
                    }
                }.trim().takeIf { it.isNotEmpty() }
            }

            ApiFormat.GEMINI -> json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.let { parts ->
                    buildString {
                        for (i in 0 until parts.length()) {
                            parts.optJSONObject(i)?.optString("text")?.let { append(it) }
                        }
                    }.trim().takeIf { it.isNotEmpty() }
                }
        }
    } catch (e: Exception) {
        DebugLogger.w(TAG, "Failed to parse response: ${e.message}")
        null
    }

    /** Content may arrive as a bare string or as an array of typed parts. */
    private fun readContent(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val part = value.optJSONObject(i) ?: continue
                val text = part.optString("text").ifEmpty { part.optString("content") }
                if (text.isNotEmpty()) append(text)
            }
        }
        else -> value.toString()
    }

    /**
     * Explains the common "HTTP 200 but no text" case: a reasoning model spent the whole
     * allowance thinking, or the relay returned a shape we can read but that carried no body.
     */
    private fun lastFencedBlock(text: String): String? {
        val matches = Regex("```[a-zA-Z]*\\n([\\s\\S]*?)```").findAll(text).toList()
        if (matches.isEmpty()) return null
        return matches.last().groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    private fun diagnoseEmpty(raw: String): String? = try {
        val json = JSONObject(raw)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val finish = choice?.optString("finish_reason").orEmpty()
        when {
            finish.equals("length", true) ->
                "模型输出被截断：推理内容占满了 max_tokens，把「最大 token」调大再试"
            finish.equals("content_filter", true) -> "输出被内容审核拦下了"
            choice != null -> "接口回话了，但正文是空的"
            json.has("data") -> "返回的是流式格式，当前只支持非流式响应"
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Pulls a human-readable reason out of whatever error shape the vendor returned. */
    private fun errorMessage(code: Int, raw: String): String {
        val parsed = try {
            val json = JSONObject(raw)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }
        } catch (e: Exception) {
            null
        }
        val short = parsed?.take(180) ?: raw.take(180)
        val hint = when (code) {
            401, 403 -> "密钥无效或无权限"
            404 -> "地址或模型名不对"
            429 -> "触发限流，稍后再试"
            in 500..599 -> "服务端错误"
            else -> ""
        }
        return buildString {
            append("HTTP $code")
            if (hint.isNotEmpty()) append(" · ").append(hint)
            if (short.isNotBlank()) append(" · ").append(short)
        }
    }

    // ---------------------------------------------------------------- calls

    private fun clientFor(timeoutSec: Int): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(minOf(timeoutSec, 30).toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout((timeoutSec + 10).toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun execute(provider: ApiProvider, apiKey: String, input: String, forTest: Boolean): Outcome {
        if (provider.baseUrl.isBlank()) return Outcome.Error("还没填 API URL")
        if (provider.model.isBlank()) return Outcome.Error("还没填模型名")

        val started = System.currentTimeMillis()
        val body = bodyFor(provider, input, forTest)
        val request = requestFor(provider, apiKey, body)
        val timeout = provider.timeoutSec

        return try {
            clientFor(timeout).newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - started
                if (!response.isSuccessful) {
                    DebugLogger.w(TAG, "Request failed ${response.code}: ${raw.take(400)}")
                    return Outcome.Error(errorMessage(response.code, raw), raw)
                }
                val text = parse(provider, raw, allowReasoning = forTest)
                    ?: return Outcome.Error(
                        diagnoseEmpty(raw) ?: "返回内容解析不出来",
                        raw
                    )
                val cleaned = cleanResult(text)
                if (cleaned.isBlank()) {
                    return Outcome.Error(diagnoseEmpty(raw) ?: "模型返回了空内容", raw)
                }
                Outcome.Ok(cleaned, elapsed)
            }
        } catch (e: java.net.SocketTimeoutException) {
            Outcome.Error("请求超时（${timeout}s），模型或中转站太慢", "可在「高级设置 → 超时」里调大")
        } catch (e: java.net.UnknownHostException) {
            Outcome.Error("域名解析失败，检查 API URL")
        } catch (e: javax.net.ssl.SSLException) {
            Outcome.Error("HTTPS 证书错误：${e.message}")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Request failed: ${e.message}")
            Outcome.Error("请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Drops the wrappers a chat model likes to add even when told not to. */
    private fun cleanResult(text: String): String {
        var value = text.trim()
        PROMPT_BLOCK.find(value)?.let { return it.groupValues[1].trim() }
        // A model that ignores the wrapper usually still fences its final draft; the last
        // fence is the one it settled on, earlier ones are discarded attempts.
        lastFencedBlock(value)?.let { return it }
        value = value.removePrefix("```").removeSuffix("```").trim()
        val fence = Regex("^```[a-zA-Z]*\\n")
        value = fence.replace(value, "").trim()
        val prefixes = listOf(
            "optimized prompt:", "prompt:", "here is the optimized prompt:",
            "优化后的提示词：", "优化后：", "提示词："
        )
        val lower = value.lowercase()
        prefixes.firstOrNull { lower.startsWith(it) }?.let {
            value = value.substring(it.length).trim()
        }
        return value
    }

    fun optimize(provider: ApiProvider, apiKey: String, input: String, rules: String? = null): Outcome =
        execute(provider, apiKey, input, forTest = false)

    /** Cheap round-trip that proves URL + key + model name all work. */
    fun test(provider: ApiProvider, apiKey: String): Outcome =
        execute(provider, apiKey, "", forTest = true)
}
