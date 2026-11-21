package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * Wire format of an OpenAI-compatible (or vendor-specific) endpoint.
 *
 * Only the relative path lives here: the settings screen asks the user for the root
 * address, so pasting `https://relay.example.com` and `https://relay.example.com/v1`
 * both end up on the same endpoint.
 */
enum class ApiFormat(
    val id: String,
    /** Full default path, used only to describe the format while the URL is still empty. */
    val pathSuffix: String,
    /** Segment added when the user's URL carries no version of its own. */
    val defaultVersion: String,
    /** What gets appended after the version segment. */
    val tail: String
) {
    OPENAI_CHAT("openai_chat", "/v1/chat/completions", "/v1", "/chat/completions"),
    OPENAI_RESPONSES("openai_responses", "/v1/responses", "/v1", "/responses"),
    ANTHROPIC("anthropic", "/v1/messages", "/v1", "/messages"),
    GEMINI(
        "gemini", "/v1beta/models/%s:generateContent", "/v1beta", "/models/%s:generateContent"
    );

    companion object {
        fun fromId(id: String?): ApiFormat = entries.firstOrNull { it.id == id } ?: OPENAI_CHAT
    }
}

/**
 * One configured prompt-optimisation backend.
 *
 * The API key is deliberately absent: it lives in [sh.hnet.comfychair.storage.CredentialStorage]
 * so it never lands in the plaintext provider JSON (which backup/export travels through).
 */
data class ApiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val format: ApiFormat,
    /**
     * Hand-written path appended to the root, used verbatim. Empty means the format decides.
     * Azure puts the deployment in the middle of the URL and appends a query string, which no
     * suffix guess can rebuild.
     */
    val apiPath: String = "",
    val model: String,
    /** System prompt sent with every request; seeded from the bundled template. */
    val systemPrompt: String,
    /** true = ask the model for English prompt prose, false = Simplified Chinese. */
    val english: Boolean,
    val temperature: Float,
    val maxTokens: Int,
    val timeoutSec: Int,
    /** Which built-in template this provider uses: "full", "lite" or "custom". */
    val templateSource: String = TEMPLATE_FULL,
    val lastUsedAt: Long? = null,
    val lastOkAt: Long? = null
) {
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("format", format.id)
        if (apiPath.isNotBlank()) put("apiPath", apiPath)
        put("model", model)
        put("systemPrompt", systemPrompt)
        put("english", english)
        put("temperature", temperature.toDouble())
        put("maxTokens", maxTokens)
        put("timeoutSec", timeoutSec)
        put("templateSource", templateSource)
        lastUsedAt?.let { put("lastUsedAt", it) }
        lastOkAt?.let { put("lastOkAt", it) }
    }

    companion object {
        const val DEFAULT_TEMPERATURE = 0.2f
        const val DEFAULT_MAX_TOKENS = 4000
        const val DEFAULT_TIMEOUT_SEC = 120
        const val TEMPLATE_FULL = "full"
        const val TEMPLATE_LITE = "lite"
        const val TEMPLATE_CUSTOM = "custom"

        const val MIN_TIMEOUT_SEC = 15
        const val MAX_TIMEOUT_SEC = 300

        fun create(
            id: String,
            name: String = "",
            systemPrompt: String = ""
        ): ApiProvider = ApiProvider(
            id = id,
            name = name,
            baseUrl = "",
            format = ApiFormat.OPENAI_CHAT,
            model = "",
            systemPrompt = systemPrompt,
            english = true,
            temperature = DEFAULT_TEMPERATURE,
            maxTokens = DEFAULT_MAX_TOKENS,
            timeoutSec = DEFAULT_TIMEOUT_SEC
        )

        fun fromJson(json: JSONObject, fallbackSystemPrompt: String): ApiProvider = ApiProvider(
            id = json.optString("id").ifEmpty { "" },
            name = json.optString("name"),
            baseUrl = json.optString("baseUrl"),
            format = ApiFormat.fromId(json.optString("format")),
            apiPath = json.optString("apiPath"),
            model = json.optString("model"),
            systemPrompt = json.optString("systemPrompt").ifEmpty { fallbackSystemPrompt },
            english = json.optBoolean("english", true),
            temperature = json.optDouble("temperature", DEFAULT_TEMPERATURE.toDouble()).toFloat(),
            maxTokens = json.optInt("maxTokens", DEFAULT_MAX_TOKENS),
            timeoutSec = json.optInt("timeoutSec", DEFAULT_TIMEOUT_SEC),
            templateSource = json.optString("templateSource").ifEmpty { TEMPLATE_FULL },
            lastUsedAt = json.optLong("lastUsedAt", 0L).takeIf { it > 0L },
            lastOkAt = json.optLong("lastOkAt", 0L).takeIf { it > 0L }
        )
    }
}
