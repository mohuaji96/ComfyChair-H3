package sh.hnet.comfychair.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ApiFormat
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.model.LocalizedText
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
        /**
         * [message] is safe to show and is finished by the screen, so it reads in the active
         * language; [detail] keeps the raw body for the log; [hint] is an optional extra line
         * such as "raise the timeout".
         */
        data class Error(
            val message: LocalizedText,
            val detail: String? = null,
            val hint: LocalizedText? = null
        ) : Outcome()
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

    private fun diagnoseEmpty(raw: String): LocalizedText? = try {
        val json = JSONObject(raw)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val finish = choice?.optString("finish_reason").orEmpty()
        when {
            finish.equals("length", true) -> LocalizedText(R.string.poe_truncated)
            finish.equals("content_filter", true) -> LocalizedText(R.string.poe_content_filtered)
            choice != null -> LocalizedText(R.string.poe_empty_body)
            json.has("data") -> LocalizedText(R.string.poe_stream_unsupported)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** A short localized explanation for the status code, or null when the code speaks for itself. */
    private fun httpHint(code: Int): LocalizedText? = when (code) {
        401, 403 -> LocalizedText(R.string.poe_http_key)
        404 -> LocalizedText(R.string.poe_http_not_found)
        429 -> LocalizedText(R.string.poe_http_rate_limited)
        in 500..599 -> LocalizedText(R.string.poe_http_server)
        else -> null
    }

    /**
     * The headline for a failed call. The vendor's own sentence rides inside the raw body that
     * the screen shows underneath, so this line only has to carry the status code.
     */
    private fun errorMessage(code: Int): LocalizedText = LocalizedText(R.string.poe_http_status, code)

    // ---------------------------------------------------------------- calls

    private fun clientFor(timeoutSec: Int): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(minOf(timeoutSec, 30).toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout((timeoutSec + 10).toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun execute(provider: ApiProvider, apiKey: String, input: String, forTest: Boolean): Outcome {
        if (provider.baseUrl.isBlank()) return Outcome.Error(LocalizedText(R.string.poe_missing_url))
        if (provider.model.isBlank()) return Outcome.Error(LocalizedText(R.string.poe_missing_model))

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
                    return Outcome.Error(errorMessage(response.code), raw, httpHint(response.code))
                }
                val text = parse(provider, raw, allowReasoning = forTest)
                    ?: return Outcome.Error(
                        diagnoseEmpty(raw) ?: LocalizedText(R.string.poe_unparsable),
                        raw
                    )
                val cleaned = cleanResult(text)
                if (cleaned.isBlank()) {
                    return Outcome.Error(
                        diagnoseEmpty(raw) ?: LocalizedText(R.string.poe_empty_result),
                        raw
                    )
                }
                Outcome.Ok(cleaned, elapsed)
            }
        } catch (e: java.net.SocketTimeoutException) {
            Outcome.Error(
                LocalizedText(R.string.poe_timeout, timeout),
                hint = LocalizedText(R.string.poe_timeout_hint)
            )
        } catch (e: java.net.UnknownHostException) {
            Outcome.Error(LocalizedText(R.string.poe_dns_failed))
        } catch (e: javax.net.ssl.SSLException) {
            Outcome.Error(LocalizedText(R.string.poe_tls_error, e.message.orEmpty()))
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Request failed: ${e.message}")
            Outcome.Error(LocalizedText(R.string.poe_request_failed, e.message ?: e.javaClass.simpleName))
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
