package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONObject
import sh.hnet.comfychair.workflow.TagPick

/**
 * Remembers the last combination for each mode, so reopening the page shows what was there
 * before instead of a fresh roll every time.
 *
 * Solo and group keep separate records on purpose: switching tabs must not overwrite the
 * other side's selection.
 */
class TagComposerState(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Which tab was open last time. */
    fun loadMode(): String = prefs.getString(KEY_MODE, MODE_SOLO) ?: MODE_SOLO

    fun saveMode(mode: String) = prefs.edit().putString(KEY_MODE, mode).apply()

    fun load(mode: String): Map<String, TagPick> {
        // Older builds kept a single record with no mode; treat it as the solo one.
        val stored = prefs.getString(keyFor(mode), null)
            ?: if (mode == MODE_SOLO) prefs.getString(KEY_LEGACY, null) else null
        val raw = stored ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { key ->
                val entry = root.optJSONObject(key) ?: return@associateWith TagPick()
                TagPick(
                    value = entry.optString("v"),
                    locked = entry.optBoolean("l"),
                    ignored = entry.optBoolean("i")
                )
            }
        }.getOrDefault(emptyMap())
    }

    fun save(mode: String, picks: Map<String, TagPick>) {
        val root = JSONObject()
        picks.forEach { (key, pick) ->
            if (pick.value.isEmpty() && !pick.locked && !pick.ignored) return@forEach
            root.put(
                key,
                JSONObject()
                    .put("v", pick.value)
                    .put("l", pick.locked)
                    .put("i", pick.ignored)
            )
        }
        prefs.edit().putString(keyFor(mode), root.toString()).apply()
    }

    /**
     * 整段锁住的段。和单格锁分开存：单格锁写在 pick 里，是"这一格不许动"；
     * 段锁是"这一段不参与全局随机"，两件事取消时不能互相牵连。
     */
    fun loadLockedGroups(mode: String): Set<String> =
        prefs.getStringSet(KEY_LOCKED_GROUPS + mode, emptySet())?.toSet().orEmpty()

    fun saveLockedGroups(mode: String, groups: Set<String>) =
        prefs.edit().putStringSet(KEY_LOCKED_GROUPS + mode, HashSet(groups)).apply()

    /**
     * Catalogue revision the stored defaults were applied against. A new library re-decides
     * what starts switched off, and a saved selection would otherwise outrank it forever.
     */
    fun loadDefaultsVersion(mode: String): String =
        prefs.getString(KEY_DEFAULTS_VERSION + mode, "").orEmpty()

    fun saveDefaultsVersion(mode: String, version: String) =
        prefs.edit().putString(KEY_DEFAULTS_VERSION + mode, version).apply()

    /**
     * 增强模式 is the default: a fresh install should look finished before it looks free.
     * 普通模式 is a deliberate opt-in, so the flag is stored rather than inferred.
     */
    fun loadStrict(): Boolean = prefs.getBoolean(KEY_STRICT, true)

    fun saveStrict(value: Boolean) = prefs.edit().putBoolean(KEY_STRICT, value).apply()

    fun loadAvoidRepeat(): Boolean = prefs.getBoolean(KEY_AVOID_REPEAT, true)

    fun saveAvoidRepeat(value: Boolean) = prefs.edit().putBoolean(KEY_AVOID_REPEAT, value).apply()

    fun clear() = prefs.edit().clear().apply()

    private fun keyFor(mode: String): String =
        if (mode == MODE_MULTI) KEY_PICKS_MULTI else KEY_PICKS_SOLO

    companion object {
        const val MODE_SOLO = "solo"
        const val MODE_MULTI = "multi"
        val MODES = listOf(MODE_SOLO, MODE_MULTI)

        private const val PREFS_NAME = "TagComposerPrefs"
        private const val KEY_PICKS_SOLO = "picks_solo"
        private const val KEY_PICKS_MULTI = "picks_multi"
        private const val KEY_LEGACY = "picks"
        private const val KEY_MODE = "mode"
        private const val KEY_STRICT = "strict"
        private const val KEY_AVOID_REPEAT = "avoid_repeat"
        private const val KEY_DEFAULTS_VERSION = "defaults_v_"
        private const val KEY_LOCKED_GROUPS = "locked_groups_"
    }
}
