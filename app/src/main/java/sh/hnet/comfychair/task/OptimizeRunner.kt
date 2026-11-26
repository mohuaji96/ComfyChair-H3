package sh.hnet.comfychair.task

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.net.PromptOptimizer
import sh.hnet.comfychair.storage.ApiProviderStore
import sh.hnet.comfychair.storage.OptimizeHistoryStore
import sh.hnet.comfychair.storage.PromptTemplateStore
import sh.hnet.comfychair.util.DebugLogger

/**
 * Runs the prompt rewrite outside any screen.
 *
 * Closing the sheet, switching tabs or letting the system drop the page must not kill a call
 * that is already being billed, and the queue sheet has to be able to show it. A process-wide
 * holder is the only place that survives all three, so the coroutine lives here and every
 * screen only observes it.
 */
object OptimizeRunner {

    private const val TAG = "OptimizeRunner"

    data class Task(
        val providerName: String,
        val model: String,
        val startedAtMs: Long,
        /** Text received so far, so the sheet can fill in while the model is still writing. */
        val partial: String = "",
        val elapsedMs: Long = 0L
    )

    sealed class Report {
        data class Done(
            val text: String,
            val note: String,
            val elapsedMs: Long,
            val promptTokens: Int,
            val completionTokens: Int,
            val thinkingRejected: Boolean = false
        ) : Report()

        data class Failed(val message: String, val detail: String?) : Report()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var ticker: Job? = null

    private val _task = MutableStateFlow<Task?>(null)
    val task: StateFlow<Task?> = _task.asStateFlow()

    private val _reports = MutableSharedFlow<Report>(extraBufferCapacity = 8)
    val reports: SharedFlow<Report> = _reports.asSharedFlow()

    fun isRunning(): Boolean = _task.value != null

    fun cancel() {
        job?.cancel()
        ticker?.cancel()
        job = null
        ticker = null
        _task.value = null
    }

    /** Returns false when a run is already in flight; one rewrite at a time. */
    fun start(context: Context, provider: ApiProvider, key: String, input: String, rules: String): Boolean {
        if (_task.value != null) return false
        val app = context.applicationContext
        val started = System.currentTimeMillis()
        _task.value = Task(
            providerName = provider.name.ifBlank { provider.model },
            model = provider.model,
            startedAtMs = started
        )

        // The first token can take five seconds to arrive; without this the elapsed label
        // would sit at 00:00 and look frozen exactly when the user is most unsure.
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                _task.value = _task.value?.let {
                    it.copy(elapsedMs = System.currentTimeMillis() - it.startedAtMs)
                } ?: break
            }
        }

        job = scope.launch {
            val outcome = try {
                PromptOptimizer.optimize(provider, key, input, rules) { piece ->
                    _task.value = _task.value?.let {
                        it.copy(
                            partial = it.partial + piece,
                            elapsedMs = System.currentTimeMillis() - it.startedAtMs
                        )
                    }
                }
            } catch (e: CancellationException) {
                ticker?.cancel()
                ticker = null
                _task.value = null
                throw e
            }

            ticker?.cancel()
            ticker = null
            _task.value = null

            when (outcome) {
                is PromptOptimizer.Outcome.Ok -> {
                    val (body, note) = OptimizeHistoryStore.splitNote(outcome.text)
                    runCatching {
                        val template = PromptTemplateStore.active(app).name
                        OptimizeHistoryStore.add(
                            app,
                            OptimizeHistoryStore.newRecord(template, input, body, note)
                        )
                    }.onFailure { DebugLogger.w(TAG, "history write failed: ${it.message}") }

                    runCatching {
                        val now = System.currentTimeMillis()
                        val list = ApiProviderStore.loadOrSeed(app).map {
                            if (it.id == provider.id) it.copy(lastUsedAt = now, lastOkAt = now) else it
                        }
                        ApiProviderStore.save(app, list)
                    }.onFailure { DebugLogger.w(TAG, "provider touch failed: ${it.message}") }

                    _reports.emit(
                        Report.Done(
                            text = body,
                            note = note,
                            elapsedMs = outcome.elapsedMs,
                            promptTokens = outcome.promptTokens,
                            completionTokens = outcome.completionTokens,
                            thinkingRejected = outcome.thinkingRejected
                        )
                    )
                }

                is PromptOptimizer.Outcome.Error -> {
                    val message = outcome.message.format(app)
                    val hint = outcome.hint?.format(app)
                    _reports.emit(
                        Report.Failed(
                            message = if (hint.isNullOrBlank()) message else "$message · $hint",
                            detail = outcome.detail
                        )
                    )
                }
            }
        }
        return true
    }
}
