package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.util.DebugLogger

/**
 * Persistence for prompt-optimiser providers.
 *
 * Non-secret fields go into plain SharedPreferences JSON; the API key goes through
 * [CredentialStorage] (EncryptedSharedPreferences) so it never lands in the file that
 * export/backup copies around.
 */
object ApiProviderStore {

    private const val TAG = "ApiProviderStore"
    private const val PREFS = "PromptProviders"
    private const val KEY_LIST = "providers"
    private const val KEY_CURRENT = "current"

    const val ASSET_TEMPLATE = "prompt_expert.md"
    const val ASSET_TEMPLATE_LITE = "prompt_expert_lite.md"

    private var cachedTemplate: String? = null
    private var cachedAsset: String? = null
    private var credentials: CredentialStorage? = null

    private fun creds(context: Context): CredentialStorage {
        val app = context.applicationContext
        return credentials ?: CredentialStorage(app).also { credentials = it }
    }

    /** The bundled system prompt. Falls back to a short stub if the asset is missing. */
    fun builtinTemplate(context: Context, source: String = ApiProvider.TEMPLATE_FULL): String {
        val asset = if (source == ApiProvider.TEMPLATE_LITE) ASSET_TEMPLATE_LITE else ASSET_TEMPLATE
        cachedTemplate?.let { if (cachedAsset == asset) return it }
        val text = try {
            context.assets.open(asset).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Bundled prompt template missing: ${e.message}")
            ""
        }
        cachedTemplate = text
        cachedAsset = asset
        return text
    }

    fun builtinLineCount(context: Context, source: String = ApiProvider.TEMPLATE_FULL): Int =
        builtinTemplate(context, source).lineSequence().count()

    fun load(context: Context): List<ApiProvider> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    // The fallback must be the template this provider actually uses, or a lite
                    // or custom entry would come back as the full 491-line prompt.
                    val source = obj.optString("templateSource").ifEmpty {
                        ApiProvider.TEMPLATE_FULL
                    }
                    val fallback = builtinTemplate(context, source)
                    val provider = ApiProvider.fromJson(obj, fallback)
                    if (provider.id.isNotEmpty()) add(provider)
                }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to read providers: ${e.message}")
            emptyList()
        }
    }

    fun save(context: Context, providers: List<ApiProvider>) {
        // An untouched template stays out of the prefs blob: it is 41 KB, and the editor
        // autosaves on every keystroke pause, so re-serialising it per provider would turn
        // each save into hundreds of kilobytes of SharedPreferences churn.
        val array = JSONArray()
        providers.forEach { provider ->
            val json = provider.toJson()
            val source = provider.templateSource
            if (source != ApiProvider.TEMPLATE_CUSTOM &&
                provider.systemPrompt == builtinTemplate(context, source)
            ) {
                json.put("systemPrompt", "")
            }
            array.put(json)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIST, array.toString())
            .apply()
    }

    fun currentId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT, null)

    fun setCurrentId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (id == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, id)
            apply()
        }
    }

    fun apiKey(context: Context, providerId: String): String? =
        creds(context).getApiKey(providerId)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, providerId: String, key: String) {
        creds(context).setApiKey(providerId, key.trim())
    }

    fun removeApiKey(context: Context, providerId: String) {
        creds(context).clearApiKey(providerId)
    }

    fun hasApiKey(context: Context, providerId: String): Boolean =
        creds(context).hasApiKey(providerId)

    /**
     * Returns the stored providers, creating a first one seeded with the bundled template
     * when the list is still empty.
     */
    fun loadOrSeed(context: Context): List<ApiProvider> {
        val existing = load(context)
        if (existing.isNotEmpty()) return existing
        val seeded = listOf(
            ApiProvider.create(
                id = newId(),
                name = "",
                systemPrompt = builtinTemplate(context)
            )
        )
        save(context, seeded)
        setCurrentId(context, seeded.first().id)
        return seeded
    }

    /** Exports the JSON without keys, for the About screen / debugging if ever needed. */
    fun snapshotJson(context: Context): String =
        JSONObject().put("providers", JSONArray().also { arr ->
            load(context).forEach { arr.put(it.toJson()) }
        }).toString()

    fun newId(): String = "p" + System.currentTimeMillis().toString(36) +
        Integer.toHexString((0..0xFFFF).random())
}
