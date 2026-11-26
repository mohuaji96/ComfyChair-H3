package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.model.ContentProfile

/**
 * Global app settings singleton.
 * Provides access to app-wide settings that need to be checked from multiple places.
 */
object AppSettings {
    private const val PREFS_NAME = "AppSettings"
    private const val KEY_MEDIA_STORAGE_MODE = "media_storage_mode"
    // Former switches, read only to migrate to KEY_MEDIA_STORAGE_MODE
    private const val KEY_MEDIA_CACHE_DISABLED = "media_cache_disabled"
    private const val KEY_MEMORY_FIRST_CACHE = "memory_first_cache"
    private const val KEY_LIVE_PREVIEW_ENABLED = "live_preview_enabled"
    private const val KEY_VIEWER_LOOP = "viewer_loop"
    private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
    private const val KEY_AUTO_CONNECT = "autoConnect"
    private const val KEY_DISABLED_WORKFLOWS = "disabled_workflows"
    // Former switch hiding all built-in workflows, read only to migrate to KEY_DISABLED_WORKFLOWS
    private const val KEY_SHOW_BUILT_IN_WORKFLOWS = "show_built_in_workflows"
    private const val KEY_OFFLINE_MODE = "offline_mode"
    private const val KEY_EDGE_ROUTER = "edge_router"
    private const val DEFAULT_EDGE_ROUTER = "hermite"
    private const val KEY_PROMPT_SPELL_CHECK = "prompt_spell_check"
    private const val KEY_H3_PREVIEW_ENABLED = "h3_preview_enabled"
    private const val KEY_THEME_ID = "theme_id"

    private val _themeFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val themeFlow: kotlinx.coroutines.flow.StateFlow<String?> = _themeFlow

    fun getThemeId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedId = prefs.getString(KEY_THEME_ID, "glacier_blue") ?: "glacier_blue"
        // 07 曜石银已由石墨青柠替换；迁移旧 ID，保留用户原来的主题选择。
        val normalizedId = if (storedId == "obsidian_silver") "graphite_lime" else storedId
        if (normalizedId != storedId) {
            prefs.edit().putString(KEY_THEME_ID, normalizedId).apply()
        }
        return normalizedId
    }

    fun setThemeId(context: Context, themeId: String) {
        val normalizedId = if (themeId == "obsidian_silver") "graphite_lime" else themeId
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_ID, normalizedId)
            .apply()
        _themeFlow.value = normalizedId
    }

    /** Whether any app setting was ever stored (a fresh install, or one whose data was cleared, has none). */
    fun hasAnyValue(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all.isNotEmpty()

    /**
     * Check if live preview is enabled.
     * When enabled (default), intermediate preview images are shown during generation.
     * When disabled, only the final result is displayed.
     */
    fun isLivePreviewEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_PREVIEW_ENABLED, true)  // Default: true (show live previews)
    }

    /**
     * Set whether live preview should be enabled.
     */
    /** Whether the media viewer loops videos (its loop toggle); on by default. Previews always loop. */
    fun isViewerLoop(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_VIEWER_LOOP, true)

    fun setViewerLoop(context: Context, loop: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_VIEWER_LOOP, loop).apply()
    }

    fun setLivePreviewEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIVE_PREVIEW_ENABLED, enabled)
            .apply()
    }

    /**
     * Whether the H3 video workspace asks the server for step-by-step frames. Off means the preview
     * node is taken out of the graph before submission, so the run costs nothing extra on the GPU.
     * [isLivePreviewEnabled] still wins: it is the switch for every workspace.
     */
    fun isH3PreviewEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_H3_PREVIEW_ENABLED, true)

    fun setH3PreviewEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_H3_PREVIEW_ENABLED, enabled)
            .apply()
    }

    /**
     * Where generated images and videos are kept. Defaults to [MediaStorageMode.MEMORY_ONLY]:
     * nothing is written to the device unless the user chooses otherwise.
     */
    fun getMediaStorageMode(context: Context): MediaStorageMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        MediaStorageMode.fromKey(prefs.getString(KEY_MEDIA_STORAGE_MODE, null))?.let { return it }
        // H3 571 defaulted memory_first_cache to false (disk-first); keep that default when migrating.
        return MediaStorageMode.migrate(prefs.getBoolean(KEY_MEMORY_FIRST_CACHE, false))
    }

    /**
     * Set where generated images and videos are kept. Replaces the former two switches.
     */
    fun setMediaStorageMode(context: Context, mode: MediaStorageMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEDIA_STORAGE_MODE, mode.key)
            .remove(KEY_MEMORY_FIRST_CACHE)
            .remove(KEY_MEDIA_CACHE_DISABLED)
            .apply()
    }

    /**
     * Whether media is kept in RAM (both memory modes) rather than written directly to disk.
     */
    fun isMemoryFirstCache(context: Context): Boolean =
        getMediaStorageMode(context) != MediaStorageMode.DEVICE

    /**
     * Whether media must never be written to the device ([MediaStorageMode.MEMORY_ONLY]).
     */
    fun isMediaCacheDisabled(context: Context): Boolean =
        getMediaStorageMode(context) == MediaStorageMode.MEMORY_ONLY

    /**
     * Check if debug logging is enabled.
     * When enabled, operational logs are captured in memory for troubleshooting.
     * Default is false (off).
     */
    fun isDebugLoggingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGGING_ENABLED, false)
    }

    /**
     * Set whether debug logging should be enabled.
     */
    fun setDebugLoggingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_LOGGING_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if auto-connect is enabled.
     * When enabled (default), the app auto-connects to the last selected server on startup.
     */
    fun isAutoConnectEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT, true)  // Default: true (auto-connect)
    }

    /**
     * Set whether auto-connect should be enabled.
     */
    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    /**
     * IDs of the workflows the user switched off; they are not offered on the generation screens.
     * Storing the disabled ones keeps every new workflow enabled by default.
     *
     * The former "show built-in workflows" switch is migrated on first read: if it was off, all
     * built-in workflows ([builtInIds]) start disabled.
     */
    fun getDisabledWorkflowIds(context: Context, builtInIds: Collection<String>): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val disabled = prefs.getStringSet(KEY_DISABLED_WORKFLOWS, null).orEmpty().toMutableSet()
        if (prefs.contains(KEY_SHOW_BUILT_IN_WORKFLOWS)) {
            if (!prefs.getBoolean(KEY_SHOW_BUILT_IN_WORKFLOWS, true)) disabled += builtInIds
            prefs.edit()
                .putStringSet(KEY_DISABLED_WORKFLOWS, disabled.toSet())
                .remove(KEY_SHOW_BUILT_IN_WORKFLOWS)
                .apply()
        }
        return disabled
    }

    fun setDisabledWorkflowIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DISABLED_WORKFLOWS, ids.toSet())
            .apply()
    }

    /**
     * Check if offline mode is enabled.
     * When enabled, the app operates with cached data only and does not connect to the server.
     * Requires disk-first cache mode to be enabled for full functionality.
     * Default is false (online mode).
     */
    fun isOfflineMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFFLINE_MODE, false)
    }

    /**
     * Set whether offline mode should be enabled.
     * Note: Should only be enabled when disk-first cache mode is active.
     */
    fun setOfflineMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFLINE_MODE, enabled)
            .apply()
    }

    /**
     * Get the selected edge router ID for the workflow editor.
     * Default is "hermite" (Hermite spline).
     */
    fun getEdgeRouterId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EDGE_ROUTER, DEFAULT_EDGE_ROUTER) ?: DEFAULT_EDGE_ROUTER
    }

    /**
     * Set the edge router ID for the workflow editor.
     */
    fun setEdgeRouterId(context: Context, routerId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EDGE_ROUTER, routerId)
            .apply()
    }

    /**
     * Check if spell check and autocorrect are enabled in prompt input fields.
     * Default is false — AI prompts contain many non-standard tokens that would be
     * flagged as errors, so the feature is opt-in.
     */
    fun isPromptSpellCheckEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPT_SPELL_CHECK, false)
    }

    fun setPromptSpellCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROMPT_SPELL_CHECK, enabled)
            .apply()
    }

    private const val KEY_PROMPT_EXPAND = "prompt_expand"
    private const val KEY_RELAY_NOTIFY = "relay_notify"
    private const val KEY_CONTENT_PROFILE = "content_profile"
    private const val KEY_SFW_INTRO_SEEN = "sfw_intro_seen"

    fun isPromptExpandEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPT_EXPAND, false)
    }

    fun setPromptExpandEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROMPT_EXPAND, enabled)
            .apply()
    }

    fun isRelayNotifyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RELAY_NOTIFY, false)
    }

    fun setRelayNotifyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RELAY_NOTIFY, enabled).apply()
    }

    fun getContentProfile(context: Context): ContentProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_CONTENT_PROFILE, null)?.let { return ContentProfile.fromKey(it) }
        val legacy = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime - info.firstInstallTime > 5 * 60 * 1000L
        }.getOrDefault(true)
        val profile = if (legacy) ContentProfile.NSFW else ContentProfile.SFW
        prefs.edit().putString(KEY_CONTENT_PROFILE, profile.key).apply()
        return profile
    }

    fun setContentProfile(context: Context, profile: ContentProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONTENT_PROFILE, profile.key).apply()
    }

    fun hasSeenSfwIntro(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SFW_INTRO_SEEN, false)

    fun setSfwIntroSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SFW_INTRO_SEEN, true).apply()
    }

    /** Every app setting back to its default (restoring defaults); servers are stored elsewhere and stay. */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

/**
 * Where generated images and videos are kept.
 */
enum class MediaStorageMode(val key: String) {
    /** RAM only: nothing is written to the device; results are gone when the app is closed. */
    MEMORY_ONLY("memory_only"),

    /** RAM, saved on the device when the app goes to the background, to survive process death. */
    MEMORY_PERSISTED("memory_persisted"),

    /** Written to the device directly; lower memory use, required for offline mode. */
    DEVICE("device");

    companion object {
        fun fromKey(key: String?): MediaStorageMode? = entries.firstOrNull { it.key == key }

        /**
         * Mode for settings from before this setting existed. Disk-first stays on the device;
         * memory-first becomes memory only, the privacy default, even where it used to save media
         * on exit (that was the former default, so it can't be told apart from a deliberate choice).
         */
        fun migrate(memoryFirstCache: Boolean): MediaStorageMode =
            if (memoryFirstCache) MEMORY_ONLY else DEVICE
    }
}
