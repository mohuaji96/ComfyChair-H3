package sh.hnet.comfychair.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared in-memory projection of the durable H3 relay queue state. */
object H3RelayStatus {
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private val _done = MutableStateFlow(0)
    val done: StateFlow<Int> = _done.asStateFlow()
    private val _remaining = MutableStateFlow(0)
    val remaining: StateFlow<Int> = _remaining.asStateFlow()
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()
    val active: Boolean get() = _done.value + _remaining.value > 0
    private val _stopSignal = MutableStateFlow(0)
    val stopSignal: StateFlow<Int> = _stopSignal.asStateFlow()

    fun restore(done: Int, remaining: Int, paused: Boolean) {
        _done.value = done.coerceAtLeast(0)
        _remaining.value = remaining.coerceAtLeast(0)
        _paused.value = paused
        if (_done.value + _remaining.value == 0) _detail.value = ""
    }
    fun setDetail(value: String) { _detail.value = value }
    fun report(done: Int, remaining: Int) {
        _done.value = done.coerceAtLeast(0)
        _remaining.value = remaining.coerceAtLeast(0)
    }
    fun setPaused(value: Boolean) { _paused.value = value }
    fun togglePaused() { _paused.value = !_paused.value }
    fun clear() {
        _paused.value = false
        _done.value = 0
        _remaining.value = 0
        _detail.value = ""
    }
    fun requestStop() {
        clear()
        _stopSignal.value += 1
    }
}
