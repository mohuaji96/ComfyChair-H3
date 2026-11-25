package sh.hnet.comfychair.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared progress of a 完整连拍 run, so the notification and the page never disagree.
 *
 * `remaining` counts the segments still waiting on the phone; the segment being generated is not
 * part of it, because it is already out of our hands.
 */
object H3RelayStatus {

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _done = MutableStateFlow(0)
    val done: StateFlow<Int> = _done.asStateFlow()

    private val _remaining = MutableStateFlow(0)
    val remaining: StateFlow<Int> = _remaining.asStateFlow()

    /**
     * What the segment being generated is doing right now. Empty when nothing is running, so the
     * notification can drop the line instead of leaving a stale stage on screen.
     */
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    val active: Boolean get() = _done.value + _remaining.value > 0

    /**
     * Bumped whenever the chain is stopped from outside the page (the queue sheet's 停止连拍). The page
     * watches this to drop the local counters it keeps in composition; zero means "nothing asked".
     */
    private val _stopSignal = MutableStateFlow(0)
    val stopSignal: StateFlow<Int> = _stopSignal.asStateFlow()

    fun setDetail(value: String) {
        _detail.value = value
    }

    fun report(done: Int, remaining: Int) {
        _done.value = done
        _remaining.value = remaining
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    fun togglePaused() {
        _paused.value = !_paused.value
    }

    fun clear() {
        _paused.value = false
        _done.value = 0
        _remaining.value = 0
        _detail.value = ""
    }

    /** Stops the chain from outside the page: clears the counts and wakes the page. */
    fun requestStop() {
        clear()
        _stopSignal.value += 1
    }
}
