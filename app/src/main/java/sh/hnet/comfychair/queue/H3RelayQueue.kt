package sh.hnet.comfychair.queue

import android.content.Context

/**
 * The phone-side queue of a 完整连拍 chain, as seen from outside the H3 page.
 *
 * Queued segments are draft snapshots on disk plus two counters in `h3_continuation`; the page owns
 * the loading of the drafts, but the "停止连拍" button in the queue sheet is not inside that page, so it
 * needs a way to stop the chain that survives the page being off screen. Clearing the counters here is
 * what actually ends the chain: the page reloads its count from the same prefs, and a chain with zero
 * queued segments has nothing left to submit.
 */
object H3RelayQueue {

    private const val PREFS = "h3_continuation"
    private const val KEY_COUNT = "relay_count"
    private const val KEY_DONE = "relay_done"

    fun remaining(context: Context): Int =
        prefs(context).getInt(KEY_COUNT, 0)

    /**
     * Ends the chain: no queued segments, counters back to zero, notification gone, and a signal the
     * page picks up if it happens to be composed.
     */
    fun clear(context: Context) {
        prefs(context).edit().putInt(KEY_COUNT, 0).putInt(KEY_DONE, 0).apply()
        H3RelayStatus.requestStop()
        H3RelayService.stop(context)
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
