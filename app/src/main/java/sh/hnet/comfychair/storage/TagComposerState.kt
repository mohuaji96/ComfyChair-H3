package sh.hnet.comfychair.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import sh.hnet.comfychair.model.ContentProfile
import sh.hnet.comfychair.workflow.TagPick

/**
 * Remembers the last combination for each mode, so reopening the page shows what was there
 * before instead of a fresh roll every time.
 *
 * Solo and group keep separate records on purpose: switching tabs must not overwrite the
 * other side's selection. The two content profiles keep separate files for the same reason —
 * a word that exists in both libraries would otherwise carry across the switch.
 */
class TagComposerState(context: Context, profile: ContentProfile) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefsName(profile), Context.MODE_PRIVATE)

    init {
        seedFromLegacy(context.applicationContext, prefs, profile)
    }

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

    /** Keeps the seeding mark: a reset must not pull the old shared record back in. */
    fun clear() = prefs.edit().clear().putBoolean(KEY_SEEDED, true).apply()

    private fun keyFor(mode: String): String =
        if (mode == MODE_MULTI) KEY_PICKS_MULTI else KEY_PICKS_SOLO

    companion object {
        const val MODE_SOLO = "solo"
        const val MODE_MULTI = "multi"
        val MODES = listOf(MODE_SOLO, MODE_MULTI)

        private const val LEGACY_PREFS_NAME = "TagComposerPrefs"
        private const val KEY_SEEDED = "seeded_from_legacy"
        private const val KEY_LEGACY_OWNER = "legacy_owner"
        private const val KEY_PICKS_SOLO = "picks_solo"
        private const val KEY_PICKS_MULTI = "picks_multi"
        private const val KEY_LEGACY = "picks"
        private const val KEY_MODE = "mode"
        private const val KEY_STRICT = "strict"
        private const val KEY_AVOID_REPEAT = "avoid_repeat"
        private const val KEY_DEFAULTS_VERSION = "defaults_v_"
        private const val KEY_LOCKED_GROUPS = "locked_groups_"

        private fun prefsName(profile: ContentProfile) = "TagComposerPrefs_${profile.key}"

        /**
         * 老版本只有一个存档文件，两档共用。升级后第一次按档打开时，把旧记录整份接过来一次。
         *
         * 只认领一次：认领的档位写进旧文件，另一档再打开就跳过，否则两档都会复制同一份选择，
         * 切档又变回互相串。目标文件里也留一个标记，用户点重置清空之后不会再从旧文件捞回来。
         */
        private fun seedFromLegacy(
            context: Context,
            target: SharedPreferences,
            profile: ContentProfile
        ) {
            if (target.getBoolean(KEY_SEEDED, false)) return
            if (target.all.isNotEmpty()) {
                target.edit().putBoolean(KEY_SEEDED, true).apply()
                return
            }
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) {
                target.edit().putBoolean(KEY_SEEDED, true).apply()
                return
            }
            val owner = legacy.getString(KEY_LEGACY_OWNER, null)
            if (owner != null && owner != profile.key) {
                target.edit().putBoolean(KEY_SEEDED, true).apply()
                return
            }
            val editor = target.edit()
            legacy.all.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.putBoolean(KEY_SEEDED, true).apply()
            legacy.edit().putString(KEY_LEGACY_OWNER, profile.key).apply()
        }
    }
}
