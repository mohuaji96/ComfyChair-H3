package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.model.ContentProfile

/**
 * Global app settings singleton.
 * Provides access to app-wide settings that need to be checked from multiple places.
 */
object AppSettings {
    private const val PREFS_NAME = "AppSettings"
    private const val KEY_MEDIA_CACHE_DISABLED = "media_cache_disabled"
    private const val KEY_MEMORY_FIRST_CACHE = "memory_first_cache"
    private const val KEY_LIVE_PREVIEW_ENABLED = "live_preview_enabled"
    private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
    private const val KEY_AUTO_CONNECT = "autoConnect"
    private const val KEY_SHOW_BUILT_IN_WORKFLOWS = "show_built_in_workflows"
    private const val KEY_DISABLED_WORKFLOWS = "disabled_workflows"
    private const val KEY_OFFLINE_MODE = "offline_mode"
    private const val KEY_EDGE_ROUTER = "edge_router"
    private const val DEFAULT_EDGE_ROUTER = "hermite"
    private const val KEY_PROMPT_SPELL_CHECK = "prompt_spell_check"
    private const val KEY_RELAY_NOTIFY = "relay_notify"

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
    fun setLivePreviewEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIVE_PREVIEW_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if memory-first caching is enabled.
     * When enabled (default), media is kept in RAM and persisted to disk on app background.
     * When disabled, media is written directly to disk (disk-first mode for low-end devices).
     */
    fun isMemoryFirstCache(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEMORY_FIRST_CACHE, false)  // H3 Beta: persist assets by default for new installs
    }

    /**
     * Set whether memory-first caching should be enabled.
     */
    fun setMemoryFirstCache(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MEMORY_FIRST_CACHE, enabled)
            .apply()
    }

    /**
     * Check if media cache is disabled.
     * When disabled, the app does not persist preview images and videos to disk.
     * Only applicable in memory-first mode.
     */
    fun isMediaCacheDisabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEDIA_CACHE_DISABLED, false)
    }

    /**
     * Set whether media cache should be disabled.
     */
    fun setMediaCacheDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MEDIA_CACHE_DISABLED, disabled)
            .apply()
    }

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
     * Check if built-in workflows should be shown.
     * When enabled (default), built-in workflows are displayed alongside user workflows.
     * When disabled, only user-created workflows are shown.
     */
    fun isShowBuiltInWorkflows(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_BUILT_IN_WORKFLOWS, true)  // Default: true (show built-in)
    }

    /**
     * Set whether built-in workflows should be shown.
     */
    fun setShowBuiltInWorkflows(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_BUILT_IN_WORKFLOWS, show)
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

    /** 完整连拍 progress notification + background keep-alive. Off by default. */
    fun isRelayNotifyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RELAY_NOTIFY, false)
    }

    fun setRelayNotifyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RELAY_NOTIFY, enabled).apply()
    }

    private const val KEY_CONTENT_PROFILE = "content_profile"
    private const val KEY_SFW_INTRO_SEEN = "sfw_intro_seen"

    /**
     * 当前内容级别。
     *
     * 老用户默认「开」——升级不该让原有词库突然少一半；全新安装默认「关」，也不弹询问。
     *
     * 判断「老用户」用的是安装/更新时间差，而不是「有没有存过设置」：词库 Store 会在首次
     * 启动时自己写出副本文件，若拿文件存在与否判断，全新安装第一次运行就会被误判成老用户。
     * 安装时间差不受初始化顺序影响。
     */
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

    /** 首次切到 SFW 的那张说明卡只弹一次。 */
    fun hasSeenSfwIntro(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SFW_INTRO_SEEN, false)

    fun setSfwIntroSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SFW_INTRO_SEEN, true).apply()
    }
}
