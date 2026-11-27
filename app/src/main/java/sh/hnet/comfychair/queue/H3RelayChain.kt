package sh.hnet.comfychair.queue

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.workflow.H3RelayClient

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
    private const val KEY_SERVER = "server_id"
    private const val KEY_WORKFLOW = "workflow_id"
    private const val KEY_AREA = "area"

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

    /** How many staged segments are waiting for 开始. */
    private val _staged = MutableStateFlow(0)
    val staged: StateFlow<Int> = _staged

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Picks the chain back up after the process was killed; the server kept running it. */
    fun restore(context: Context) {
        val saved = prefs(context)
        _staged.value = saved.getInt(KEY_STAGED, 0).coerceAtLeast(0)
        // A live state is newer than the disk; never step backwards over a poll.
        if (_state.value.started) return
        val id = saved.getString(KEY_ID, null) ?: return
        _state.value = Snapshot(
            chainId = id,
            project = saved.getString(KEY_PROJECT, "").orEmpty(),
            of = saved.getInt(KEY_OF, 0),
            // The real state arrives with the first poll; until then it is at least running.
            state = H3RelayClient.ChainState.RUNNING
        )
    }

    fun begin(context: Context, chainId: String, project: String, of: Int) {
        prefs(context).edit()
            .putString(KEY_ID, chainId)
            .putString(KEY_PROJECT, project)
            .putInt(KEY_OF, of)
            .commit()
        _state.value = Snapshot(
            chainId = chainId,
            project = project,
            of = of,
            state = H3RelayClient.ChainState.QUEUED
        )
    }

    fun report(summary: H3RelayClient.Summary) {
        _state.value = Snapshot(
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
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ID).remove(KEY_PROJECT).remove(KEY_OF).commit()
        _state.value = Snapshot()
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
        val client = ConnectionManager.clientOrNull ?: return
        scope.launch {
            when (val answer = H3RelayClient.control(client, chainId, action)) {
                is H3RelayClient.Answer.Ok -> report(answer.value)
                is H3RelayClient.Answer.Gone -> clear(context)
                else -> Unit
            }
        }
    }
}
