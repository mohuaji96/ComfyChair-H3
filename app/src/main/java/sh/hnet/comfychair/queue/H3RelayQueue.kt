package sh.hnet.comfychair.queue

import android.content.Context

/**
 * Durable metadata for the phone-side H3 continuation chain.
 * Draft snapshots remain in H3DraftStore; this class owns their queue cursor and run phase.
 */
object H3RelayQueue {
    private const val PREFS = "h3_continuation"
    private const val KEY_COUNT = "relay_count"
    private const val KEY_DONE = "relay_done"
    private const val KEY_HEAD = "relay_head"
    private const val KEY_STAGE = "relay_stage"
    private const val KEY_PAUSED = "relay_paused"
    private const val KEY_SOURCE_PROMPT = "relay_source_prompt"

    const val STAGE_IDLE = "IDLE"
    const val STAGE_WAITING_RESULT = "WAITING_RESULT"
    const val STAGE_EXTRACTING_TAIL = "EXTRACTING_TAIL"
    const val STAGE_READY_TO_SUBMIT = "READY_TO_SUBMIT"
    const val STAGE_SUBMITTING = "SUBMITTING"
    const val STAGE_SUBMIT_UNKNOWN = "SUBMIT_UNKNOWN"

    data class Progress(val done: Int, val remaining: Int)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remaining(context: Context): Int = prefs(context).getInt(KEY_COUNT, 0).coerceAtLeast(0)
    fun done(context: Context): Int = prefs(context).getInt(KEY_DONE, 0).coerceAtLeast(0)
    fun headIndex(context: Context): Int = prefs(context).getInt(KEY_HEAD, 0).coerceAtLeast(0)
    fun nextIndex(context: Context): Int = headIndex(context) + remaining(context)
    fun stage(context: Context): String = prefs(context).getString(KEY_STAGE, STAGE_IDLE) ?: STAGE_IDLE
    fun sourcePromptId(context: Context): String? = prefs(context).getString(KEY_SOURCE_PROMPT, null)

    fun setStage(context: Context, stage: String, sourcePromptId: String? = null): Boolean {
        val editor = prefs(context).edit().putString(KEY_STAGE, stage)
        if (sourcePromptId == null) editor.remove(KEY_SOURCE_PROMPT) else editor.putString(KEY_SOURCE_PROMPT, sourcePromptId)
        return editor.commit()
    }

    fun setPaused(context: Context, paused: Boolean): Boolean {
        val saved = prefs(context).edit().putBoolean(KEY_PAUSED, paused).commit()
        if (saved) H3RelayStatus.setPaused(paused)
        return saved
    }

    /** Restore the in-memory notification model after process recreation. */
    fun restore(context: Context) {
        val p = prefs(context)
        H3RelayStatus.restore(
            done = p.getInt(KEY_DONE, 0).coerceAtLeast(0),
            remaining = p.getInt(KEY_COUNT, 0).coerceAtLeast(0),
            paused = p.getBoolean(KEY_PAUSED, false)
        )
    }

    /** Atomically advances one completed relay segment and its queue cursor. */
    @Synchronized
    fun commitCurrentSegment(context: Context): Progress? {
        val p = prefs(context)
        val done = (p.getInt(KEY_DONE, 0) + 1).coerceAtLeast(0)
        val remaining = (p.getInt(KEY_COUNT, 0) - 1).coerceAtLeast(0)
        val head = p.getInt(KEY_HEAD, 0) + 1
        val saved = p.edit()
            .putInt(KEY_DONE, done)
            .putInt(KEY_COUNT, remaining)
            .putInt(KEY_HEAD, head)
            .putString(KEY_STAGE, STAGE_READY_TO_SUBMIT)
            .remove(KEY_SOURCE_PROMPT)
            .remove("pending")
            .remove("pending_server_id")
            .remove("relay_submit_fingerprint")
            .remove("relay_submit_client_id")
            .remove("relay_submit_request_id")
            .commit()
        if (!saved) return null
        H3RelayStatus.report(done, remaining)
        return Progress(done, remaining)
    }

    /** Ends the phone-side chain without interrupting a prompt already accepted by ComfyUI. */
    fun clear(context: Context) {
        prefs(context).edit()
            .putInt(KEY_COUNT, 0)
            .putInt(KEY_DONE, 0)
            .putInt(KEY_HEAD, 0)
            .putBoolean(KEY_PAUSED, false)
            .putString(KEY_STAGE, STAGE_IDLE)
            .remove(KEY_SOURCE_PROMPT)
            .remove("pending")
            .remove("pending_server_id")
            .remove("relay_server_id")
            .remove("relay_workflow_id")
            .remove("relay_area")
            .remove("relay_submit_fingerprint")
            .remove("relay_submit_client_id")
            .remove("relay_submit_request_id")
            .commit()
        H3RelayStatus.requestStop()
        H3RelayService.stop(context)
    }
}
