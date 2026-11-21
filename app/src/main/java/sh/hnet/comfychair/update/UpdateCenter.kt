package sh.hnet.comfychair.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.hnet.comfychair.BuildConfig

/** What the about page renders for「检查更新」. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * The one place that knows whether a newer release exists.
 *
 * Policy: at most one network call per day, the answer is cached in prefs so a cold start can
 * show it without touching the network, failures stay silent unless the user asked by hand,
 * and a version the user skipped never comes back.
 */
object UpdateCenter {

    private const val PREFS = "update_check"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_tag"
    private const val KEY_TAG = "remote_tag"
    private const val KEY_NAME = "remote_name"
    private const val KEY_CODE = "remote_code"
    private const val KEY_NOTES = "remote_notes"
    private const val KEY_URL = "remote_url"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var running = false

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) _state.value = UpdateState.Idle
    }

    fun skippedTag(context: Context): String = prefs(context).getString(KEY_SKIPPED, "").orEmpty()

    /**
     * @param force true for a tap on the row: ignores the daily gate and reports failures.
     *   A silent start-up check never overwrites a cached answer with an error.
     */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!force && !isEnabled(app)) return
        if (running) return
        val prefs = prefs(app)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && last > 0 && System.currentTimeMillis() - last < DAY_MS) {
            publishCached(app)
            return
        }
        running = true
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                val info = UpdateChecker().fetchLatest()
                prefs.edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .putString(KEY_TAG, info.tag)
                    .putString(KEY_NAME, info.versionName)
                    .putInt(KEY_CODE, info.versionCode ?: -1)
                    .putString(KEY_NOTES, info.notes)
                    .putString(KEY_URL, info.pageUrl)
                    .apply()
                publish(app, info)
            } catch (error: Exception) {
                if (force) {
                    _state.value = UpdateState.Failed(error.message ?: "network")
                } else {
                    publishCached(app)
                }
            } finally {
                running = false
            }
        }
    }

    /** Hides this release until a newer tag shows up. */
    fun skip(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_SKIPPED, tag).apply()
        _state.value = UpdateState.UpToDate(System.currentTimeMillis())
    }

    /** Publishes whatever the last successful check found, without a network call. */
    fun restore(context: Context) = publishCached(context.applicationContext)

    private fun publishCached(context: Context) {
        val prefs = prefs(context)
        val tag = prefs.getString(KEY_TAG, "").orEmpty()
        if (tag.isEmpty()) return
        publish(
            context,
            UpdateInfo(
                tag = tag,
                versionName = prefs.getString(KEY_NAME, "").orEmpty(),
                versionCode = prefs.getInt(KEY_CODE, -1).takeIf { it > 0 },
                notes = prefs.getString(KEY_NOTES, "").orEmpty(),
                pageUrl = prefs.getString(KEY_URL, "").orEmpty()
            )
        )
    }

    private fun publish(context: Context, info: UpdateInfo) {
        val newer = UpdateVersion.isNewer(
            info.versionName,
            info.versionCode,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        _state.value = when {
            !newer -> UpdateState.UpToDate(System.currentTimeMillis())
            info.tag == skippedTag(context) -> UpdateState.UpToDate(System.currentTimeMillis())
            else -> UpdateState.Available(info)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
