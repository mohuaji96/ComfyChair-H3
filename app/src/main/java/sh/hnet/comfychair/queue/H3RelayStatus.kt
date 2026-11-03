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

    val active: Boolean get() = _done.value + _remaining.value > 0

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
    }
}
