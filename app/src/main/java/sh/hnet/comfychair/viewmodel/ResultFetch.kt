package sh.hnet.comfychair.viewmodel

/**
 * How hard a screen tries to pull a finished result down before it gives up.
 *
 * The completion event is consumed when it arrives, so a failed download used to leave the page
 * sitting at "generating" until the user navigated away and back. Retrying twice covers the
 * ordinary case (history written a beat late); after that the run is finished anyway and the
 * user is told the file could not be fetched.
 */
internal object ResultFetch {
    const val RETRIES = 2
    const val RETRY_DELAY_MS = 1_500L
}
