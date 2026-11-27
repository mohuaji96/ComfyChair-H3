package sh.hnet.comfychair.queue

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.workflow.H3RelayClient
import java.util.UUID

/**
 * The 连拍 chain, as this phone last saw it.
 *
 * Two things live here. Segments the user has staged but not sent yet, which are just a cursor
 * over draft snapshots the page writes; and the chain the server is running, which the phone
 * only watches. Because the server owns the run, closing the app stops nothing — [restore]
 * picks the chain back up on the next launch.
 *
 * Nothing here polls. A page owns the loop, so a page that is not visible costs no requests.
 */
object H3RelayChain {

    private const val PREFS = "h3_chain"
    private const val KEY_ID = "chain_id"
    private const val KEY_PROJECT = "chain_project"
    private const val KEY_OF = "chain_of"
    private const val KEY_STAGED = "staged"
    private const val KEY_HEAD = "head"
    /** Which workspace the staged segments belong to; cleared whenever staging is reset. */
    private const val KEY_SERVER = "server_id"
    /**
     * Which server is running the chain. Separate from [KEY_SERVER] on purpose: starting a chain
     * resets staging, and a shared key would take the chain's owner down with it.
     */
    private const val KEY_OWNER = "chain_server"
    private const val KEY_WORKFLOW = "workflow_id"
    private const val KEY_AREA = "area"
    private const val KEY_PENDING = "pending_id"
    /** The last reading of the chain, so the first screen after a launch is not a zero. */
    private const val KEY_DONE = "chain_done"
    private const val KEY_SEGMENT = "chain_segment"
    private const val KEY_STATE = "chain_state"
    private const val KEY_STAGE = "chain_stage"

    data class Snapshot(
        val chainId: String? = null,
        val project: String = "",
        val of: Int = 0,
        val done: Int = 0,
        val segment: Int = 0,
        val state: String = "",
        val stage: String? = null,
        val pauseRequested: Boolean = false,
        val error: String? = null
    ) {
        val started: Boolean get() = !chainId.isNullOrBlank()

        /** The server will not start another segment by itself. */
        val ended: Boolean get() = started && state in ENDED

        val active: Boolean get() = started && !ended

        val paused: Boolean get() = state == H3RelayClient.ChainState.PAUSED

        /** 1-based number of the segment being worked on. */
        val current: Int get() = segment.coerceIn(1, of.coerceAtLeast(1))
    }

    private val ENDED = setOf(
        H3RelayClient.ChainState.DONE,
        H3RelayClient.ChainState.FAILED,
        H3RelayClient.ChainState.STOPPED
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    /** Kept so [report] can write the reading down; set by [restore] and [watch]. */
    @Volatile private var _app: Context? = null

    /** How many staged segments are waiting for 开始. */
    private val _staged = MutableStateFlow(0)
    val staged: StateFlow<Int> = _staged

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Picks the chain back up after the process was killed; the server kept running it. */
    fun restore(context: Context) {
        _app = context.applicationContext
        val saved = prefs(context)
        _staged.value = saved.getInt(KEY_STAGED, 0).coerceAtLeast(0)
        // A live state is newer than the disk; never step backwards over a poll.
        if (_state.value.started) return
        val id = saved.getString(KEY_ID, null) ?: return
        // A chain belongs to the server that ran it. Showing another server's progress would
        // point the user at clips this gallery will never get, so an obvious mismatch is
        // dropped instead of restored. No record at all means older data: keep it.
        val owner = saved.getString(KEY_OWNER, null)
        val here = ConnectionManager.currentServerId
        if (owner != null && here != null && owner != here) return
        // The last reading is shown before the first poll answers, so a chain at 5 of 8 does
        // not flash "第 1 段" on the way in. The state falls back to running because a chain
        // with nothing recorded has not ended.
        _state.value = Snapshot(
            chainId = id,
            project = saved.getString(KEY_PROJECT, "").orEmpty(),
            of = saved.getInt(KEY_OF, 0),
            done = saved.getInt(KEY_DONE, 0),
            segment = saved.getInt(KEY_SEGMENT, 0),
            stage = saved.getString(KEY_STAGE, null),
            state = saved.getString(KEY_STATE, null) ?: H3RelayClient.ChainState.RUNNING
        )
    }

    /**
     * The id the next submission carries.
     *
     * The plugin rejects a chain without one, and the id is what makes a retry land on the same
     * chain instead of a second copy — so it is minted and written down before the first byte
     * leaves, and reused until a submission actually starts a chain.
     */
    fun pendingChainId(context: Context): String {
        val saved = prefs(context)
        val existing = saved.getString(KEY_PENDING, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = "h3-" + UUID.randomUUID().toString()
        saved.edit().putString(KEY_PENDING, fresh).commit()
        return fresh
    }

    /**
     * Records the chain the server just accepted. Returns false when the write did not land:
     * the in-memory state is set either way, so this run keeps working, but the caller has to
     * say so — after a restart the chain would otherwise be gone without a trace.
     */
    fun begin(context: Context, chainId: String, project: String, of: Int): Boolean {
        _app = context.applicationContext
        val saved = prefs(context).edit()
            .putString(KEY_ID, chainId)
            .putString(KEY_PROJECT, project)
            .putInt(KEY_OF, of)
            .putString(KEY_OWNER, ConnectionManager.currentServerId)
            .remove(KEY_PENDING)
            .commit()
        _state.value = Snapshot(
            chainId = chainId,
            project = project,
            of = of,
            state = H3RelayClient.ChainState.QUEUED
        )
        return saved
    }

    fun report(summary: H3RelayClient.Summary) {
        val snapshot = Snapshot(
            chainId = summary.chainId,
            // The plugin dedupes the name against every chain it has run; what it settled on is
            // what the user should see, not the name the phone asked for.
            project = summary.prefix.ifBlank { _state.value.project },
            of = summary.of,
            done = summary.done,
            segment = summary.segment,
            state = summary.state,
            stage = summary.stage,
            pauseRequested = summary.pauseRequested,
            error = summary.error
        )
        _state.value = snapshot
        remember(snapshot)
    }

    /**
     * Writes the reading down, once per change.
     *
     * A poll every two seconds would otherwise rewrite the file every two seconds for a
     * number that moved once a minute; the comparison is what keeps it cheap.
     */
    private fun remember(snapshot: Snapshot) {
        val app = _app ?: return
        val saved = prefs(app)
        if (saved.getInt(KEY_DONE, 0) == snapshot.done &&
            saved.getInt(KEY_SEGMENT, 0) == snapshot.segment &&
            saved.getString(KEY_STATE, null) == snapshot.state &&
            saved.getString(KEY_STAGE, null) == snapshot.stage
        ) {
            return
        }
        prefs(app).edit()
            .putInt(KEY_DONE, snapshot.done)
            .putInt(KEY_SEGMENT, snapshot.segment)
            .putString(KEY_STATE, snapshot.state)
            .putString(KEY_STAGE, snapshot.stage)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_ID).remove(KEY_PROJECT).remove(KEY_OF).remove(KEY_PENDING)
            .remove(KEY_OWNER).remove(KEY_DONE).remove(KEY_SEGMENT)
            .remove(KEY_STATE).remove(KEY_STAGE).commit()
        _state.value = Snapshot()
    }

    // ---- watching the server ------------------------------------------------------------

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null
    @Volatile private var settling = false

    /**
     * Starts following the chain, once per process.
     *
     * The loop lives here rather than in a page on purpose: a page that is not visible stops
     * polling, and the user comes back to a progress bar frozen where they left it. Ownership
     * does not move — the server still runs the chain; this only keeps the reading current
     * wherever the user happens to be.
     */
    fun watch(context: Context) {
        val app = context.applicationContext
        _app = app
        if (watching?.isActive != true) {
            watching = io.launch {
                _state.map { it.chainId }.distinctUntilChanged().collectLatest { id ->
                    if (!id.isNullOrBlank()) follow(app, id)
                }
            }
        }
        // Retried on every call, not just the first: the earliest call can arrive before there
        // is a client to ask, and that is exactly when a lost answer needs settling.
        io.launch { settlePending(app) }
    }

    /**
     * An answer we never saw is settled by asking, not by guessing. The id was written down
     * before the request left, so asking about it either finds the chain or proves it never
     * started — and nothing here starts a second one.
     */
    private suspend fun settlePending(app: Context) {
        if (_state.value.started || settling) return
        val saved = prefs(app)
        val id = saved.getString(KEY_PENDING, null)?.takeIf { it.isNotBlank() } ?: return
        val client = ConnectionManager.clientOrNull ?: return
        settling = true
        try {
            when (val answer = H3RelayClient.chain(client, id)) {
                is H3RelayClient.Answer.Ok ->
                    begin(app, id, answer.value.prefix, answer.value.of)
                is H3RelayClient.Answer.Gone -> saved.edit().remove(KEY_PENDING).commit()
                else -> Unit
            }
        } finally {
            settling = false
        }
    }

    /** One request every two seconds until the server stops reporting a live chain. */
    private suspend fun follow(app: Context, id: String) {
        while (true) {
            val client = ConnectionManager.clientOrNull
            if (client == null) {
                delay(2000)
                continue
            }
            when (val answer = H3RelayClient.chain(client, id)) {
                is H3RelayClient.Answer.Ok -> {
                    report(answer.value)
                    if (answer.value.ended) {
                        // Finished segments are files on the server already, and a failed chain
                        // still has the ones it got through: show them either way.
                        GalleryRepository.getInstance().refresh()
                        return
                    }
                }
                is H3RelayClient.Answer.Gone -> {
                    clear(app)
                    return
                }
                else -> Unit
            }
            delay(2000)
        }
    }

    // ---- staged segments --------------------------------------------------------------

    fun headIndex(context: Context): Int = prefs(context).getInt(KEY_HEAD, 0).coerceAtLeast(0)

    /** Where the next staged snapshot goes: after everything already staged. */
    fun nextIndex(context: Context): Int = headIndex(context) + _staged.value

    /**
     * True when the staged segments were composed on this page, against this server and this
     * workspace. A chain mixed from two of them would continue one clip with another's tail.
     */
    fun ownsStaging(context: Context, serverId: String?, workflowId: String, area: String): Boolean {
        if (_staged.value <= 0) return true
        val saved = prefs(context)
        return saved.getString(KEY_SERVER, null) == serverId &&
            saved.getString(KEY_WORKFLOW, null) == workflowId &&
            saved.getString(KEY_AREA, null) == area
    }

    fun stage(context: Context, serverId: String?, workflowId: String, area: String): Boolean {
        val count = _staged.value
        val saved = prefs(context).edit()
            .putInt(KEY_STAGED, count + 1)
            .putString(KEY_SERVER, serverId)
            .putString(KEY_WORKFLOW, workflowId)
            .putString(KEY_AREA, area)
            .commit()
        if (saved) _staged.value = count + 1
        return saved
    }

    /** Drops the batch; the snapshot files stay behind harmlessly and get overwritten. */
    fun resetStaging(context: Context) {
        prefs(context).edit()
            .putInt(KEY_STAGED, 0)
            .putInt(KEY_HEAD, 0)
            .remove(KEY_SERVER)
            .remove(KEY_WORKFLOW)
            .remove(KEY_AREA)
            .commit()
        _staged.value = 0
    }

    /**
     * Sends pause / resume / stop to the server. Stop is always the soft kind: the segment that
     * is already generating finishes and is kept, because throwing it away wastes the minutes it
     * has cost. A chain that is gone already (plugin restarted) simply clears itself.
     */
    fun control(context: Context, scope: CoroutineScope, action: String) {
        val chainId = _state.value.chainId ?: return
        val client = ConnectionManager.clientOrNull
        if (client == null) {
            // The button did nothing at all, which must not look like a chain that ignored
            // the tap: say the server is not reachable and leave the state alone.
            AppErrors.report(context, R.string.h3_chain_unreachable)
            return
        }
        scope.launch {
            when (val answer = H3RelayClient.control(client, chainId, action)) {
                is H3RelayClient.Answer.Ok -> report(answer.value)
                is H3RelayClient.Answer.Gone -> clear(context)
                // A refused control (plugin switched off, chain record gone from memory, a
                // server that answered with a reason) used to be swallowed here: the row
                // kept showing "生成中" and the user pressed the same button again.
                is H3RelayClient.Answer.Rejected -> AppErrors.report(
                    AppError(
                        context.getString(
                            R.string.h3_chain_control_failed,
                            answer.refused.message?.takeIf { it.isNotBlank() }
                                ?: answer.refused.failure.wire
                        )
                    )
                )
                is H3RelayClient.Answer.Unreachable -> AppErrors.report(
                    AppError(answer.message?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.h3_chain_unreachable))
                )
            }
        }
    }
}
