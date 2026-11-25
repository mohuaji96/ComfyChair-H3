package sh.hnet.comfychair.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.WebSocketMessage
import sh.hnet.comfychair.queue.JobRegistry

/**
 * 采样预览 state, kept outside the H3 page.
 *
 * The page lives in a NavHost, so leaving the tab destroys its composition: anything held in a
 * `remember` there is gone on the way back, which is exactly how the preview strip vanished when the
 * user looked at another workspace mid-run while frames were still arriving. Holding the switch and
 * the latest frame here — and collecting the socket here rather than in the page — keeps the strip
 * alive across tab switches, and lets the frames that arrive while the page is off screen update the
 * picture instead of being dropped.
 */
object H3SamplingPreviewState {

    private const val H3_OWNER_ID = "H3_WORKSPACE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _frame = MutableStateFlow<WebSocketMessage.SamplingPreview?>(null)
    val frame: StateFlow<WebSocketMessage.SamplingPreview?> = _frame.asStateFlow()

    /**
     * Whether the run in flight carries the preview node. Set at submission and left alone until the
     * run ends: turning the picture off must not lose the fact that the server is still sending it,
     * or the switch could never be turned back on for the same run.
     */
    private val _attached = MutableStateFlow(false)
    val attached: StateFlow<Boolean> = _attached.asStateFlow()

    /** Whether the user wants to watch. Only this one the eye flips. */
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Tracks whether the user explicitly hid the preview during the current run. */
    @Volatile
    private var userHidCurrentRun = false

    /** Tracks whether we have seen the H3 job enter execution since [begin]. */
    @Volatile
    private var seenExecuting = false

    init {
        scope.launch {
            ConnectionManager.webSocketMessages.collect { message ->
                val h3Running = JobRegistry.queueState.value.executingOwnerId == H3_OWNER_ID
                when (message) {
                    // 带步数的帧只可能来自 H3 自己的预览节点，它本身就说明身份，所以不看归属直接收。
                    // 冷启动时第一批帧往往比「这笔任务是我的」的队列快照先到，以前那层归属闸门会把
                    // 它们全部挡掉，等快照对齐时采样可能已经过了大半，画面就再也接不上。
                    is WebSocketMessage.SamplingPreview -> {
                        _frame.value = message
                        attachIfNeeded()
                    }
                    // A graph without the override node still previews when the server's own preview
                    // is on; that frame carries no step, but it is better than a card that never
                    // fills while the server is plainly sending pictures. 这种帧没有身份，仍要看归属，
                    // 否则会把别的客户端正在跑的东西画到自己页面上。
                    is WebSocketMessage.PreviewImage -> {
                        if (!_attached.value && !h3Running) return@collect
                        _frame.value = WebSocketMessage.SamplingPreview(
                            bitmap = message.bitmap,
                            step = 0,
                            total = 0,
                            stepMillis = null
                        )
                        attachIfNeeded()
                    }
                    else -> Unit
                }
            }
        }
        // Clean up here rather than from a page LaunchedEffect: leaving and re-entering the tab
        // while a job is still queued (`ownJobExecuting == false`) used to trigger finish() on
        // composition entry and wipe `_attached` before step 1 even started.
        scope.launch {
            JobRegistry.queueState.collect { q ->
                val h3Executing = q.executingOwnerId == H3_OWNER_ID
                if (h3Executing) {
                    seenExecuting = true
                } else if (seenExecuting && q.ownActiveJobCount == 0) {
                    finish()
                }
            }
        }
    }

    /**
     * 收到第一帧就认定这次运行带预览，并替用户把画面打开（除非他这轮亲手关过）。
     * 冷启动走的就是这条路：没有任何人调用过 begin()，状态得从帧本身长出来。
     */
    private fun attachIfNeeded() {
        if (_attached.value) return
        _attached.value = true
        if (!userHidCurrentRun) _visible.value = true
    }

    /** Called once per submission with what the payload ended up carrying; a new run starts blank. */
    fun begin(attached: Boolean) {
        _frame.value = null
        userHidCurrentRun = false
        seenExecuting = JobRegistry.queueState.value.executingOwnerId == H3_OWNER_ID
        _attached.value = attached
        _visible.value = attached
    }

    /**
     * The eye. Frames keep being collected while hidden (and the current frame is retained in memory),
     * so turning the picture back on shows the latest frame immediately instead of an empty card.
     */
    fun setVisible(visible: Boolean) {
        userHidCurrentRun = !visible
        _visible.value = visible
    }

    fun finish() {
        seenExecuting = false
        userHidCurrentRun = false
        _frame.value = null
        _attached.value = false
        _visible.value = false
    }
}
