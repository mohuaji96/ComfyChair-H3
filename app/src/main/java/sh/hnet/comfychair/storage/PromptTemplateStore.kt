package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger

/**
 * One system prompt. The two bundled templates are read-only; everything the player writes is a
 * custom copy, so an import can never damage the factory text.
 */
data class PromptTemplate(
    val id: String,
    val name: String,
    val body: String,
    val builtIn: Boolean,
    val updatedAt: Long = 0L,
    /** Name of the template this one was copied from; empty for imports and migration. */
    val sourceName: String = "",
    /** One line under the name saying what this template is good for. Built-ins only. */
    val blurb: String = ""
)

/** Pieces of the model that are worth a test without an Android context. */
object PromptTemplates {

    const val LONG_ID = "builtin_long"
    const val LITE_ID = "builtin_lite"

    fun builtIns(longBody: String, liteBody: String): List<PromptTemplate> = listOf(
        PromptTemplate(
            LONG_ID, "完整版", longBody, builtIn = true,
            blurb = "最细 · 难稿稳，少返工"
        ),
        PromptTemplate(
            LITE_ID, "精简版", liteBody, builtIn = true,
            blurb = "默认 · 判断全，出图快"
        )
    )

    /** Falls back to the lite template, which is the default, when the active id is gone. */
    fun resolve(list: List<PromptTemplate>, activeId: String?): PromptTemplate =
        list.firstOrNull { it.id == activeId }
            ?: list.firstOrNull { it.id == LITE_ID }
            ?: list.first()

    /**
     * Deleting the template in use must leave something usable behind: the lite built-in, which
     * is the default and cannot be removed.
     */
    fun nextActiveAfterDelete(list: List<PromptTemplate>, activeId: String?, deletedId: String): String {
        if (list.isEmpty()) return LITE_ID
        val remaining = list.filter { it.id != deletedId }
        if (remaining.isEmpty()) return LITE_ID
        if (activeId != deletedId && remaining.any { it.id == activeId }) return activeId!!
        return remaining.firstOrNull { it.id == LITE_ID }?.id ?: remaining.first().id
    }

    /** "长版·副本" -> "长版·副本 2" -> 3 ... */
    fun uniqueName(list: List<PromptTemplate>, base: String): String {
        val taken = list.map { it.name }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }
}

/**
 * Template storage. Custom bodies live in their own prefs file: they are 20-40 KB each and must
 * not bloat the provider list, which is rewritten on every keystroke of the settings form.
 */
object PromptTemplateStore {

    private const val TAG = "PromptTemplateStore"
    private const val PREFS = "PromptTemplates"
    private const val KEY_LIST = "custom"
    private const val KEY_ACTIVE = "active"
    private const val KEY_MIGRATED = "migrated"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAsset(context: Context, name: String): String = runCatching {
        context.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrElse {
        DebugLogger.w(TAG, "缺少内置模板 $name")
        ""
    }

    fun load(context: Context): List<PromptTemplate> {
        val builtIns = PromptTemplates.builtIns(
            readAsset(context, "prompt_expert.md"),
            readAsset(context, "prompt_expert_lite.md")
        )
        return builtIns + loadCustom(context)
    }

    private fun loadCustom(context: Context): List<PromptTemplate> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isEmpty()) continue
                    add(
                        PromptTemplate(
                            id = id,
                            name = o.optString("name").ifEmpty { "自定义" },
                            body = o.optString("body"),
                            builtIn = false,
                            updatedAt = o.optLong("updatedAt"),
                            sourceName = o.optString("source")
                        )
                    )
                }
            }
        }.getOrElse {
            DebugLogger.w(TAG, "模板列表损坏，已忽略：${it.message}")
            emptyList()
        }
    }

    fun saveCustom(context: Context, template: PromptTemplate) {
        val customs = loadCustom(context).filter { it.id != template.id } + template.copy(builtIn = false)
        val array = JSONArray()
        customs.forEach { t ->
            array.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("body", t.body)
                put("updatedAt", t.updatedAt)
                put("source", t.sourceName)
            })
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    fun delete(context: Context, id: String) {
        val customs = loadCustom(context).filter { it.id != id }
        val array = JSONArray()
        customs.forEach { t ->
            array.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("body", t.body)
                put("updatedAt", t.updatedAt)
                put("source", t.sourceName)
            })
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    fun activeId(context: Context): String? = prefs(context).getString(KEY_ACTIVE, null)

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun active(context: Context): PromptTemplate =
        PromptTemplates.resolve(load(context), activeId(context))

    fun isMigrated(context: Context): Boolean = prefs(context).getBoolean(KEY_MIGRATED, false)

    /**
     * The provider used to own the system prompt. On the first launch after the library exists,
     * whatever it holds becomes 自定义 1 and is switched on, so an edited prompt is never lost.
     */
    fun migrateLegacyPrompt(context: Context, legacy: String) {
        if (isMigrated(context)) return
        // A provider that was never edited still answers with the built-in text, because
        // ApiProvider.fromJson fills a blank prompt from the factory copy. Saving that as a custom
        // template created a twin of the built-in on every restore, and being switched on it also
        // pinned the prompt to the version it was copied at.
        val builtInBodies = load(context).filter { it.builtIn }.map { it.body.trim() }.toSet()
        if (legacy.isNotBlank() && legacy.trim() !in builtInBodies) {
            val template = PromptTemplate(
                id = "custom_1",
                name = "自定义 1",
                body = legacy,
                builtIn = false,
                updatedAt = System.currentTimeMillis()
            )
            saveCustom(context, template)
            setActive(context, template.id)
            DebugLogger.i(TAG, "旧提示词已存为「自定义 1」并设为生效")
        }
        prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    /**
     * Removes custom templates that are byte-identical to a built-in.
     *
     * They cannot carry any edit, so they only add a duplicate row and pin the active prompt to a
     * frozen version of the factory text. Deleting is safe: the built-in still holds the same body.
     */
    fun pruneBuiltInCopies(context: Context) {
        val builtInBodies = load(context).filter { it.builtIn }.map { it.body.trim() }.toSet()
        if (builtInBodies.isEmpty()) return
        val doomed = loadCustom(context).filter { it.body.trim() in builtInBodies }
        if (doomed.isEmpty()) return
        doomed.forEach { delete(context, it.id) }
        if (activeId(context) in doomed.map { it.id }) {
            prefs(context).edit().remove(KEY_ACTIVE).apply()
        }
        DebugLogger.i(TAG, "已清理 ${doomed.size} 个与内置完全相同的自定义模板")
    }

    /** A fresh custom copy of [source]; the built-in itself is never written to. */
    fun copyAsCustom(context: Context, source: PromptTemplate, name: String? = null): PromptTemplate {
        val all = load(context)
        val template = PromptTemplate(
            id = "custom_${System.currentTimeMillis()}",
            name = name ?: PromptTemplates.uniqueName(all, "${source.name}·副本"),
            body = source.body,
            builtIn = false,
            updatedAt = System.currentTimeMillis(),
            sourceName = source.name
        )
        saveCustom(context, template)
        return template
    }
}
