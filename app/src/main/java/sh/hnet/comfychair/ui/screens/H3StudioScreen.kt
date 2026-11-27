package sh.hnet.comfychair.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.theme.LocalAppElevations
import sh.hnet.comfychair.ui.components.shared.ModelDropdown
import sh.hnet.comfychair.ui.components.shared.ChoiceMenu
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.PromptBody
import sh.hnet.comfychair.model.localizedText
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.storage.MetadataStore
import sh.hnet.comfychair.storage.SequenceStore
import sh.hnet.comfychair.queue.H3RelayService
import sh.hnet.comfychair.queue.H3RelayQueue
import sh.hnet.comfychair.workflow.ProgressStage
import sh.hnet.comfychair.queue.H3RelayStatus
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.H3SamplingPreviewState
import sh.hnet.comfychair.ui.components.H3SamplingPreviewStrip
import sh.hnet.comfychair.ui.components.H3SeedField
import sh.hnet.comfychair.workflow.H3Naming
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.WebSocketMessage
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.H3Frame
import sh.hnet.comfychair.util.LoraInfo
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.workflow.H3Continuation
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.workflow.H3Plan
import sh.hnet.comfychair.workflow.H3RelayClient
import sh.hnet.comfychair.workflow.H3RelayState
import sh.hnet.comfychair.workflow.H3Resolution
import sh.hnet.comfychair.workflow.H3SamplingPreview
import sh.hnet.comfychair.workflow.H3Seed
import sh.hnet.comfychair.workflow.JobRecovery
import sh.hnet.comfychair.workflow.PendingJobStore
import sh.hnet.comfychair.workflow.SubmitPreflight
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import java.util.UUID

/** Progress routing id for this page. The ordinary pages own TEXT_TO_VIDEO / IMAGE_TO_VIDEO. */
internal const val H3_OWNER_ID = "H3_WORKSPACE"

/** Bottom of the trained frame range documented by `MiniMaxH3ImageToVideo`. */
// Cell markers for the reference grid: one tile is the add button, one is the tail frame.
private const val H3_CELL_ADD = -1
private const val H3_CELL_TAIL = -2
private const val H3_MAX_PICTURES = 9

/**
 * H3 studio: card sections on top, a fixed generate bar at the bottom.
 *
 * The bar and the controls read the same numbers, and every control writes to a node that was
 * proven by following links ([H3Plan.Spec]). The mode decision is applied only to the submission
 * copy, so switching T2V / I2V / FL2V keeps the images uploaded for the other modes.
 */
@Composable
fun H3StudioContent(
    workflowId: String,
    area: H3Plan.Area,
    generationViewModel: GenerationViewModel?,
    onOpenWorkflowSettings: () -> Unit,
    isVisible: Boolean = true
) {
    val context = LocalContext.current
    val workflow = remember(workflowId) { WorkflowManager.getWorkflowById(workflowId) }
    if (workflow == null) {
        Text(stringResource(R.string.h3_workflow_missing), Modifier.padding(16.dp))
        return
    }
    val baseSource = workflow.jsonContent
    val spec = remember(baseSource) { H3Plan.resolve(baseSource) }
    if (spec == null) {
        Text(stringResource(R.string.h3_workflow_not_h3), Modifier.padding(16.dp))
        return
    }
    val draftStore = remember { H3DraftStore(context) }
    val presetStore = remember { H3PresetStore(context) }
    var presetName by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(presetStore.list()) }
    var draft by remember(workflowId, area) { mutableStateOf(draftStore.load(workflowId, area, spec)) }
    // Also re-check when returning to the H3 tab (`isVisible == true`): while H3 is kept alive in
    // the background for a running job or 连拍, `workflowId` and `area` do not change on tab return,
    // so a frame sent from Gallery -> 用作素材 would otherwise sit unconsumed in `h3_pending`.
    LaunchedEffect(workflowId, area, isVisible) {
        if (!isVisible) return@LaunchedEffect
        val pendingPrefs = context.getSharedPreferences("h3_pending", android.content.Context.MODE_PRIVATE)
        val slot = pendingPrefs.getString("sourceSlot", null)
        val file = pendingPrefs.getString("sourceFile", null)
        if (slot != null && !file.isNullOrBlank()) {
            val matchesArea = (slot == "ref" && area == H3Plan.Area.R2V) ||
                ((slot == "first" || slot == "last") && area == H3Plan.Area.FRAMES)
            if (matchesArea) {
                pendingPrefs.edit().remove("sourceSlot").remove("sourceFile").apply()
                val nextDraft = when (slot) {
                    "first" -> draft.copy(first = file)
                    "last" -> draft.copy(last = file)
                    else -> {
                        val curRefs = draft.refs.toMutableList()
                        if (curRefs.isEmpty()) curRefs.add(file)
                        else {
                            val emptyIdx = curRefs.indexOfFirst { it.isBlank() }
                            if (emptyIdx >= 0) curRefs[emptyIdx] = file else curRefs[0] = file
                        }
                        draft.copy(refs = curRefs)
                    }
                }
                draft = nextDraft
                draftStore.save(workflowId, area, nextDraft)
            }
        }
    }
    // Debounced: typing should not hit SharedPreferences on every keystroke.
    LaunchedEffect(workflowId, area, draft) {
        kotlinx.coroutines.delay(400)
        draftStore.save(workflowId, area, draft)
    }

    // The frames window is one page: T2V / I2V / FL2V follow only from which keyframes are filled.
    // Nothing is chosen by the user, so an empty page is a valid text-to-video submission.
    val mode = if (area == H3Plan.Area.R2V) H3Plan.Mode.R2V
    else H3Plan.Mode.forFrames(draft.first, draft.last)
    // Only a tail frame is a different request from a first+last pair, so name it differently.
    val modeLabel = if (mode == H3Plan.Mode.FL2V && draft.first.isBlank()) stringResource(R.string.h3_mode_fl_no_first) else stringResource(mode.labelRes)

    val models by ConnectionManager.modelCache.collectAsState()
    val client = ConnectionManager.clientOrNull
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val pendingStore = remember { PendingJobStore(context) }
    // Survives a backgrounded or killed process: the id is written at submit time.
    val autoPrefs = remember {
        context.getSharedPreferences("h3_continuation", android.content.Context.MODE_PRIVATE)
    }
    // Restore the in-memory notification projection before collecting its StateFlows.
    val relayStateRestored = remember(context) { H3RelayQueue.restore(context); true }
    var pendingAutoId by remember { mutableStateOf(autoPrefs.getString("pending", null)) }
    var relayStage by remember { mutableStateOf(H3RelayQueue.stage(context)) }
    val savedRelayServerId = autoPrefs.getString("relay_server_id", null)
    val savedRelayWorkflowId = autoPrefs.getString("relay_workflow_id", null)
    val savedRelayArea = autoPrefs.getString("relay_area", null)
    val relayRunMatches = (savedRelayServerId == null || savedRelayServerId == ConnectionManager.currentServerId) &&
        (savedRelayWorkflowId == null || savedRelayWorkflowId == workflowId) &&
        (savedRelayArea == null || savedRelayArea == area.name)

    LaunchedEffect(relayStateRestored) {
        // A process died while a submit was in flight. Its result may have reached ComfyUI even
        // though the callback did not reach this process, so never auto-submit it a second time.
        if (relayStage == H3RelayQueue.STAGE_SUBMITTING) {
            if (H3RelayQueue.setStage(context, H3RelayQueue.STAGE_SUBMIT_UNKNOWN)) {
                relayStage = H3RelayQueue.STAGE_SUBMIT_UNKNOWN
            }
        }
    }

    fun setRelayStage(stage: String, sourcePromptId: String? = null): Boolean {
        val saved = H3RelayQueue.setStage(context, stage, sourcePromptId)
        if (saved) relayStage = stage
        return saved
    }

    var message by remember { mutableStateOf("") }
    var showDeclareHelp by remember { mutableStateOf(false) }
    var showRelayHelp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // 折叠状态是界面状态，不写进草稿、也不进预设。用 rememberSaveable 配
    // H3WorkspaceScreen 的 SaveableStateHolder，切页签或换工作流回来才各记各的。
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    // 参数面板：四项基础参数连同种子一起收进弹层，主屏只留四宫格。
    var showPanel by rememberSaveable { mutableStateOf(false) }
    var panelTab by rememberSaveable { mutableIntStateOf(0) }
    var showTurbo by rememberSaveable { mutableStateOf(false) }
    // Reference audio is on almost no run, so the section stays a single line until tapped.
    var showAudio by remember(workflowId) { mutableStateOf(false) }
    // Which number is being edited in the shared dialog; null closes it. The draft is only
    // touched on 确定, so 取消 is a true discard instead of a reverse patch.
    var editing by remember(workflowId) { mutableStateOf<H3NumberParam?>(null) }
    var editText by remember(workflowId) { mutableStateOf("") }
    var pendingSlot by remember { mutableStateOf<String?>(null) }
    var sourcePickerSlot by remember { mutableStateOf<String?>(null) }
    /** Which reference-audio slot the picker is filling; -1 means no pick is in flight. */
    var pendingAudio by remember { mutableStateOf(-1) }
    var previewRevision by remember { mutableStateOf(0) }
    val turboKeys = remember(spec) { H3TurboKeys.from(spec) }
    val isTurbo = turboKeys.complete
    // Progress and queue position come from the same WebSocket state the four ordinary pages use;
    // nothing here is polled by hand any more.
    val queueState by sh.hnet.comfychair.queue.JobRegistry.queueState.collectAsState()
    val ownJobExecuting = queueState.executingOwnerId == H3_OWNER_ID

    // The relay banner used to say only "3 done, 5 left", which is silent about the segment that
    // is actually costing the time. Stage changes are rare, so refreshing on those alone is cheap;
    // doing it per step would rewrite the notification dozens of times a minute.
    LaunchedEffect(generationViewModel) {
        var lastStage: ProgressStage? = null
        generationViewModel?.generationState?.collect { state ->
            val stage = if (state.isGenerating && state.promptId != null) state.stage else null
            if (stage == lastStage) return@collect
            lastStage = stage
            H3RelayStatus.setDetail(
                when {
                    stage == null -> ""
                    stage == ProgressStage.SAMPLE && state.maxProgress > 0 ->
                        context.getString(
                            R.string.h3_progress_steps,
                            context.getString(stage.labelRes),
                            state.progress,
                            state.maxProgress
                        )
                    else -> context.getString(stage.labelRes)
                }
            )
            H3RelayService.update(context)
        }
    }

    // The WebSocket status message only says how many jobs the server holds, not where ours sits,
    // and /queue is the only place that order exists. Poll it while we wait, stop the moment the
    // job starts or the queue empties.
    val ownJobWaiting = queueState.ownActiveJobCount > 0
    // Keep polling while the page still believes our job is executing, not only while a tracked
    // job exists: once the registry dropped the job the old loop stopped, and a stale "中止"
    // stayed on the button with nothing left to poll it away.
    LaunchedEffect(ownJobWaiting, ownJobExecuting) {
        while (ownJobWaiting || ownJobExecuting) {
            ConnectionManager.pollQueueStatus()
            delay(3000)
        }
    }
    // The page fetches its own result the moment the server writes it, so the bar can show the
    // last run (poster, duration, size) without waiting for a gallery refresh.
    var lastPromptId by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<H3Result?>(null) }
    var resultPoster by remember { mutableStateOf<Bitmap?>(null) }
    var resultLoading by remember { mutableStateOf(false) }

    // 采样预览: the frame and the switch live in [H3SamplingPreviewState], not in this composition —
    // leaving the tab tears the page down, and a `remember` here would take the strip with it.
    // Cleanup is owned by [H3SamplingPreviewState] watching [JobRegistry.queueState] directly, so
    // switching tabs while a job is still queued (`ownJobExecuting == false`) cannot wipe `_attached`.
    val previewFrame by H3SamplingPreviewState.frame.collectAsState()
    val previewAttached by H3SamplingPreviewState.attached.collectAsState()
    val previewVisible by H3SamplingPreviewState.visible.collectAsState()
    val genState = generationViewModel?.generationState?.collectAsState()?.value

    // 完整连拍: queued segments are draft snapshots, so each keeps the settings it was added with.
    var relayCount by remember { mutableStateOf(autoPrefs.getInt("relay_count", 0)) }
    var relaySubmitAfterFrame by remember { mutableStateOf(false) }
    var relaySubmitTick by remember { mutableStateOf(0) }
    var relayDone by remember { mutableStateOf(autoPrefs.getInt("relay_done", 0)) }
    val relayPaused by H3RelayStatus.paused.collectAsState()

    suspend fun relaySaveDurable(index: Int, d: H3Draft): Boolean =
        withContext(Dispatchers.IO) { draftStore.saveDurable("__relay$index", area, d) }

    fun relayLoad(index: Int): H3Draft = draftStore.load("__relay$index", area, spec)

    /** Read the queue head without consuming it; consumption happens only after tail upload is durable. */
    fun relayPeek(): H3Draft = relayLoad(H3RelayQueue.headIndex(context))

    fun commitRelayHead(): Boolean {
        val progress = H3RelayQueue.commitCurrentSegment(context) ?: return false
        relayDone = progress.done
        relayCount = progress.remaining
        relayStage = H3RelayQueue.STAGE_READY_TO_SUBMIT
        pendingAutoId = null
        H3RelayService.update(context)
        return true
    }

    fun enqueueSegment() {
        if (busy || !relayRunMatches) return
        busy = true
        val queuedDraft = draft
        val index = H3RelayQueue.nextIndex(context)
        val countAfterSave = relayCount + 1
        scope.launch {
            val savedDraft = relaySaveDurable(index, queuedDraft)
            val savedMeta = savedDraft && withContext(Dispatchers.IO) {
                autoPrefs.edit()
                    .putString("relay_server_id", ConnectionManager.currentServerId)
                    .putString("relay_workflow_id", workflowId)
                    .putString("relay_area", area.name)
                    .putInt("relay_count", countAfterSave)
                    .commit()
            }
            main.post {
                busy = false
                if (!savedMeta) {
                    message = context.getString(R.string.h3_msg_relay_persist_failed)
                    return@post
                }
                relayCount = countAfterSave
                H3RelayStatus.report(relayDone, relayCount)
                H3RelayService.update(context)
            }
        }
    }

    fun finishRelay() {
        // Clear only the phone-side continuation chain. A prompt already accepted by ComfyUI
        // remains a server job and is not interrupted by this local queue reset.
        H3RelayQueue.clear(context)
        relayDone = 0
        relayCount = 0
        relayStage = H3RelayQueue.STAGE_IDLE
        pendingAutoId = null
        H3RelayService.stop(context)
    }

    // 停止连拍 from the queue sheet: it is outside this page, so it clears the prefs and bumps a
    // signal; the local counters follow here.
    val relayStopSignal by H3RelayStatus.stopSignal.collectAsState()
    var handledRelayStop by remember { mutableIntStateOf(relayStopSignal) }
    LaunchedEffect(relayStopSignal) {
        if (relayStopSignal != handledRelayStop) {
            handledRelayStop = relayStopSignal
            // The queue controller already cleared persistent state. Never replay a stop against a new run.
            relayDone = H3RelayQueue.done(context)
            relayCount = H3RelayQueue.remaining(context)
            relayStage = H3RelayQueue.stage(context)
            pendingAutoId = autoPrefs.getString("pending", null)
            relaySubmitAfterFrame = false
        }
    }

    // Reference tiles. The template itself wires `ref_image_0..N`, so those slots are always
    // legal without asking anyone; only going past them needs the server's own declaration.
    var probedCeiling by remember(workflowId, client) { mutableStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var selectedRef by remember(workflowId, area) { mutableStateOf(-1) }
    val knownRefCeiling = probedCeiling.coerceAtLeast(0)
    val templateSlots = if (knownRefCeiling > 0) minOf(spec.refIndices.size, knownRefCeiling) else spec.refIndices.size
    val filledRefs = draft.refs.filterIndexed { i, f -> f.isNotBlank() && i !in draft.disabledRefs }

    // 连拍: the previous segment's last frame is always the final picture of the next segment, so
    // that segment can only spend capacity-1 on its own references. Counted here, once, so the
    // subtitle, the add button and the submission all agree instead of failing at the server.
    val reservesTailSlot = draft.continuationAuto &&
        (relayCount > 0 || draft.continuation.isNotBlank())
    val refCapacity = H3_MAX_PICTURES - if (reservesTailSlot) 1 else 0

    val aspectOptions = remember(models.nodeDefinitionsRevision) {
        ConnectionManager.nodeTypeRegistry
            .getInputDefinition("ResolutionSelector", "aspect_ratio")?.options.orEmpty()
    }
    // Choices come from the server's own aspect_ratio list, so a list that grows (5:4, 21:10 …)
    // needs no code change; the built-in table only covers an offline first launch.
    val ratios: List<Pair<String, Pair<Int, Int>>> = remember(aspectOptions) {
        H3Resolution.fromServer(aspectOptions).map { it.value to (it.w to it.h) }
    }
    val commonRatioPairs = remember(ratios) {
        H3Resolution.commonRatios.mapNotNull { name -> ratios.firstOrNull { it.first == name } }
    }
    val extraRatioPairs = remember(ratios) {
        ratios.filter { it.first !in H3Resolution.commonRatios }
    }

    // Every user action clears the previous answer, so a stale "上限" notice can never sit
    // under the tiles long after the click that produced it.
    fun update(next: H3Draft) {
        // The tail frame is whatever the live reference count says it is, so a declaration we
        // wrote has to follow that number. A hand-written one is never touched here; the prompt
        // card names the mismatch instead.
        var adjusted = next
        if (next.continuation.isNotBlank() && next.continuationOwner != H3Continuation.NONE) {
            val picture = next.refs.filterIndexed { i, f ->
                f.isNotBlank() && i !in next.disabledRefs
            }.size + 1
            if (picture != next.continuationOwner) {
                adjusted = next.copy(
                    prompt = H3Continuation.renumber(next.prompt, next.continuationOwner, picture),
                    continuationOwner = picture
                )
            }
        }
        draft = adjusted
        message = ""
    }

    /**
     * The switch writes the declaration and takes it back, and says which of the two happened.
     * Turning it off only removes lines this app wrote; a hand-written claim is left alone.
     */
    fun setContinuationInject(on: Boolean) {
        val picture = filledRefs.size + 1
        if (on) {
            val applied = H3Continuation.apply(draft.prompt, picture, true)
            if (!applied.wrote) {
                update(draft.copy(continuationInject = true))
                val claim = H3Continuation.declaredPicture(draft.prompt)
                message = if (claim != null && claim != picture) {
                    context.getString(R.string.h3_msg_conflict_declared, claim, picture)
                } else {
                    context.getString(R.string.h3_msg_already_declared, picture)
                }
                return
            }
            update(draft.copy(
                prompt = applied.prompt,
                continuationInject = true,
                continuationOwner = applied.declared,
                continuationFlag = applied.flagged
            ))
            message = context.getString(R.string.h3_msg_declare_written, picture)
        } else {
            val owned = draft.continuationOwner
            val flagged = draft.continuationFlag
            update(draft.copy(
                prompt = H3Continuation.revert(draft.prompt, owned, flagged),
                continuationInject = false,
                continuationOwner = H3Continuation.NONE,
                continuationFlag = false
            ))
            message = if (owned != H3Continuation.NONE || flagged) {
                context.getString(R.string.h3_msg_declare_reverted, picture)
            } else {
                context.getString(R.string.h3_msg_nothing_to_revert)
            }
        }
    }

    /**
     * 连拍模式 is the plugin's switch, so turning it on is also the moment to ask whether this
     * server has one. The knob flips at once — a switch that only moves after a round trip feels
     * broken — and flips back with a reason when the answer is no. Nothing is left in a state
     * where the user believes a chain will run and nothing is going to.
     */
    fun setRelayMode(on: Boolean) {
        if (!on) {
            update(draft.copy(continuationAuto = false))
            return
        }
        val live = ConnectionManager.clientOrNull ?: run {
            message = context.getString(R.string.h3_msg_connect_first)
            return
        }
        update(draft.copy(continuationAuto = true))
        scope.launch {
            val found = H3RelayClient.info(live)
            main.post {
                if (live !== ConnectionManager.clientOrNull) return@post
                H3RelayState.remember(ConnectionManager.currentServerId, found)
                if (found == null) {
                    update(draft.copy(continuationAuto = false))
                    message = context.getString(R.string.h3_relay_probe_failed)
                } else {
                    message = context.getString(R.string.h3_relay_probe_ok, found.version)
                }
            }
        }
    }

    /**
     * Stops the job this page put on the queue. Softer than killing the browser tab: the
     * server drops the prompt and whatever already finished stays in the cache.
     */
    fun abortOwnJob() {
        val live = ConnectionManager.clientOrNull ?: run {
            message = context.getString(R.string.h3_msg_connect_first)
            return
        }
        val targetPromptId = queueState.executingPromptId
            ?.takeIf { queueState.executingOwnerId == H3_OWNER_ID }
            ?: queueState.ownJobs.values.firstOrNull {
                it.ownerId == H3_OWNER_ID && it.status == sh.hnet.comfychair.queue.JobStatus.EXECUTING
            }?.promptId
        if (targetPromptId.isNullOrBlank()) {
            message = context.getString(R.string.h3_msg_abort_failed)
            return
        }
        live.interruptExecution(targetPromptId) { ok ->
            main.post {
                message = if (ok) context.getString(R.string.h3_msg_aborted) else context.getString(R.string.h3_msg_abort_failed)
                if (ok) {
                    lastPromptId = null
                    resultLoading = false
                }
            }
        }
    }

    /**
     * Uploads a captured frame and hangs it on the continuation slot. Split from the capture so the
     * manual video picker and the automatic path rewrite the prompt in exactly one place.
     */
    fun uploadTailFrame(png: ByteArray?) {
        if (png == null) {
            busy = false
            message = context.getString(R.string.h3_msg_frame_failed)
            return
        }
        val target = ConnectionManager.clientOrNull ?: run { busy = false; message = context.getString(R.string.h3_msg_connect_first); return }
        val name = "h3_tail_${UUID.randomUUID()}.png"
        val tailSource = H3RelayQueue.sourcePromptId(context)
        val tailIsRelay = relayStage == H3RelayQueue.STAGE_EXTRACTING_TAIL
        target.uploadImage(png, name, overwrite = false, returnFullPath = true) { ok, uploaded, err, _ ->
            main.post {
                busy = false
                if (tailIsRelay && (H3RelayQueue.stage(context) != H3RelayQueue.STAGE_EXTRACTING_TAIL || H3RelayQueue.sourcePromptId(context) != tailSource)) return@post
                if (target !== ConnectionManager.clientOrNull) {
                    message = context.getString(R.string.h3_msg_switched_tail)
                } else if (ok && uploaded != null) {
                    val picture = filledRefs.size + 1
                    val applied = H3Continuation.apply(
                        draft.prompt, picture, draft.continuationInject
                    )
                    // A skipped write keeps whatever we already owned for this picture; a write
                    // of our own takes ownership, and a prompt that was already the user's stays
                    // theirs.
                    val owned = when {
                        applied.declared != H3Continuation.NONE -> applied.declared
                        draft.continuationOwner == picture -> picture
                        else -> H3Continuation.NONE
                    }
                    val updatedDraft = draft.copy(
                        continuation = uploaded,
                        prompt = applied.prompt,
                        continuationOwner = owned,
                        continuationFlag = applied.flagged ||
                            (owned == picture && draft.continuationFlag)
                    )
                    update(updatedDraft)
                    // Keep the UI busy until the continuation filename is durably checkpointed.
                    busy = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            draftStore.saveDurable(workflowId, area, updatedDraft)
                        }
                        main.post {
                            busy = false
                            if (!saved) {
                                message = context.getString(R.string.h3_msg_relay_persist_failed)
                                return@post
                            }
                            message = when {
                                !updatedDraft.continuationInject ->
                                    context.getString(R.string.h3_tail_attached_need_declare, picture)
                                !applied.wrote ->
                                    context.getString(R.string.h3_tail_attached_already, picture)
                                else ->
                                    context.getString(R.string.h3_tail_attached, picture)
                            }
                            if (tailIsRelay && (H3RelayQueue.stage(context) != H3RelayQueue.STAGE_EXTRACTING_TAIL || H3RelayQueue.sourcePromptId(context) != tailSource)) return@post
                            if (relayStage == H3RelayQueue.STAGE_EXTRACTING_TAIL) {
                                relaySubmitAfterFrame = false
                                if (!commitRelayHead()) {
                                    message = context.getString(R.string.h3_msg_relay_persist_failed)
                                }
                            } else if (relaySubmitAfterFrame) {
                                relaySubmitAfterFrame = false
                                relaySubmitTick++
                            }
                        }
                    }
                } else {
                    message = context.getString(R.string.h3_msg_tail_upload_failed, err ?: context.getString(R.string.h3_msg_no_filename))
                }
            }
        }
    }

    /**
     * Pull the last frame out of the finished clip. The video is already in the local cache by the
     * time the bar shows a poster, so nothing of the previous clip is uploaded again -- only a PNG.
     */
    fun captureLastFrame(auto: Boolean) {
        val finished = result
        if (finished == null) {
            message = context.getString(R.string.h3_msg_no_prev_result)
            return
        }
        busy = true
        if (!auto) message = context.getString(R.string.h3_msg_taking_last_frame)
        val source = H3RelayQueue.sourcePromptId(context)
        val extractingRelay = relayStage == H3RelayQueue.STAGE_EXTRACTING_TAIL
        val capturedClient = ConnectionManager.clientOrNull
        scope.launch {
            val png = withContext(Dispatchers.IO) { H3Frame.lastFramePng(context, finished.uri) }
            if (capturedClient !== ConnectionManager.clientOrNull ||
                (extractingRelay && (H3RelayQueue.stage(context) != H3RelayQueue.STAGE_EXTRACTING_TAIL ||
                    H3RelayQueue.sourcePromptId(context) != source))) {
                busy = false
                return@launch
            }
            uploadTailFrame(png)
        }
    }

    // Fallback for a clip this page did not render: pick any video from the phone, take its last
    // frame locally and hang it exactly like an automatic one. Only the PNG leaves the device.
    val tailVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; message = context.getString(R.string.h3_msg_extracting_last)
        scope.launch {
            uploadTailFrame(withContext(Dispatchers.IO) { H3Frame.lastFramePng(context, uri) })
        }
    }

    // 连拍: refill the continuation frame as soon as a run finishes, so only the prompt and the
    // length need editing between segments. Never touches the references the user picked.
    // A run that finished while the app was in the background -- or after the process was killed --
    // still gets its tail frame: the submitted id is persisted and retried when this page returns.
    fun recoverRelayPrompt(promptId: String, live: ComfyUIClient, expectedRequestId: String) {
        if (autoPrefs.getString("relay_submit_request_id", null) != expectedRequestId) return
        if (live !== ConnectionManager.clientOrNull || relayStage != H3RelayQueue.STAGE_SUBMIT_UNKNOWN) return
        val saved = autoPrefs.edit()
            .putString("pending", promptId)
            .putString("relay_stage", H3RelayQueue.STAGE_WAITING_RESULT)
            .remove("relay_submit_fingerprint")
            .remove("relay_submit_client_id")
            .remove("relay_submit_request_id")
            .commit()
        if (saved) {
            pendingAutoId = promptId
            relayStage = H3RelayQueue.STAGE_WAITING_RESULT
            message = context.getString(R.string.h3_msg_relay_recovered_submit)
        } else {
            message = context.getString(R.string.h3_msg_relay_persist_failed)
        }
    }

    LaunchedEffect(relayStage, isVisible, relayRunMatches) {
        if (relayStage != H3RelayQueue.STAGE_SUBMIT_UNKNOWN || !isVisible || !relayRunMatches) return@LaunchedEffect
        val fingerprint = autoPrefs.getString("relay_submit_fingerprint", null) ?: return@LaunchedEffect
        val expectedClientId = autoPrefs.getString("relay_submit_client_id", null) ?: return@LaunchedEffect
        val expectedRequestId = autoPrefs.getString("relay_submit_request_id", null) ?: run {
            message = context.getString(R.string.h3_msg_relay_submit_unknown)
            return@LaunchedEffect
        }
        val live = ConnectionManager.clientOrNull ?: return@LaunchedEffect
        live.findQueuedPromptId(expectedClientId, fingerprint, expectedRequestId) { queuedId, queueReadSucceeded ->
            if (!queuedId.isNullOrBlank()) {
                main.post { recoverRelayPrompt(queuedId, live, expectedRequestId) }
            } else {
                live.findHistoricalPromptId(expectedClientId, fingerprint, expectedRequestId) { historyId, historyReadSucceeded ->
                    main.post {
                        if (live !== ConnectionManager.clientOrNull) return@post
                        if (!historyId.isNullOrBlank()) recoverRelayPrompt(historyId, live, expectedRequestId)
                        else if (queueReadSucceeded && historyReadSucceeded) {
                            message = context.getString(R.string.h3_msg_relay_submit_unknown)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(pendingAutoId, isVisible, relayRunMatches) {
        val id = pendingAutoId ?: return@LaunchedEffect
        if (!relayRunMatches) {
            message = context.getString(R.string.h3_msg_relay_context_mismatch)
            return@LaunchedEffect
        }
        if (result?.promptId == id) return@LaunchedEffect
        val live = ConnectionManager.clientOrNull ?: return@LaunchedEffect
        var elapsed = 0L
        var interval = 5_000L
        while (elapsed < 10 * 60_000L) {
            if (ConnectionManager.clientOrNull !== live) return@LaunchedEffect
            val fetched = withContext(Dispatchers.IO) { loadH3Result(context, live, id) }
            if (fetched != null) {
                result = fetched
                resultPoster = withContext(Dispatchers.IO) { h3Poster(context, fetched.uri) }
                return@LaunchedEffect
            }
            delay(interval)
            elapsed += interval
            interval = (interval * 2).coerceAtMost(30_000L)
        }
        // Keep the ID: the user can revisit the workspace later and the next composition retries.
    }

    // Declared before the cards so the first-frame notice can offer the one-tap fix.
    val ratioOn = { r: String -> draft.customW.isBlank() && draft.customH.isBlank() && draft.ratio == r }
    val ratioEnabled = !busy && spec.resolution != null
    fun pickRatio(value: String) =
        update(draft.copy(ratio = value, customW = "", customH = ""))

    /**
     * Ask the server how many `ref_images` sockets it accepts. Only ever called when the user
     * wants more than the template already wires — a failure here must never block the first
     * images, which the file already declares.
     */
    fun askCeiling(current: Int, onResult: (Int) -> Unit) {
        val live = ConnectionManager.clientOrNull
        if (live == null) { message = context.getString(R.string.h3_msg_connect_first); return }
        probing = true
        live.fetchRefImageCeiling { max ->
            main.post {
                probing = false
                if (live !== ConnectionManager.clientOrNull) return@post
                probedCeiling = max ?: 0
                if (max == null || max <= current)
                    message = context.getString(R.string.h3_msg_no_ref_limit, current)
                else onResult(max)
            }
        }
    }



    // ---- upload
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = pendingSlot
        val target = ConnectionManager.clientOrNull
        if (uri == null || slot == null || target == null) return@rememberLauncherForActivityResult
        busy = true; message = context.getString(R.string.h3_msg_uploading_image)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytes(32 * 1024 * 1024 + 1) }
                    require(bytes.size <= 32 * 1024 * 1024) { context.getString(R.string.h3_err_image_too_big) }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000L) { context.getString(R.string.h3_err_image_unsupported) }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error(context.getString(R.string.h3_err_image_decode))
                    val png = java.io.ByteArrayOutputStream().use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
                    }
                    bitmap.recycle(); png
                }
            }.onSuccess { png ->
                target.uploadImage(png, "h3_${UUID.randomUUID()}.png", overwrite = false, returnFullPath = true) { ok, name, error, _ ->
                    main.post {
                        busy = false
                        if (target !== ConnectionManager.clientOrNull) message = context.getString(R.string.h3_msg_switched_upload)
                        else if (ok && name != null) {
                            val preview = java.io.File(context.filesDir,
                                "h3_previews/$workflowId/${UUID.nameUUIDFromBytes(name.toByteArray())}.png")
                            preview.parentFile?.mkdirs(); preview.writeBytes(png)
                            previewRevision++
                            update(when {
                                slot == "first" -> draft.copy(first = name)
                                slot == "last" -> draft.copy(last = name)
                                else -> {
                                    val index = slot.removePrefix("ref:").toIntOrNull() ?: 0
                                    val refs = draft.refs.toMutableList()
                                    while (refs.size <= index) refs.add("")
                                    refs[index] = name
                                    draft.copy(refs = refs)
                                }
                            })
                            message = context.getString(R.string.h3_msg_uploaded)
                        } else message = context.getString(R.string.h3_msg_upload_failed, error ?: context.getString(R.string.h3_msg_no_filename))
                    }
                }
             }.onFailure { busy = false; message = context.getString(R.string.h3_msg_image_read_failed, it.message.orEmpty()) }
         }
     }

    /**
     * Reference audio is uploaded byte for byte: no decode, no re-encode, so the container the user
     * picked is the container the server receives. The declared type travels with it because the
     * upload endpoint writes whatever it is handed into the input folder.
     */
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = ConnectionManager.clientOrNull
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        busy = true; message = context.getString(R.string.h3_msg_uploading_audio)
        scope.launch {
            val picked = runCatching {
                withContext(Dispatchers.IO) {
                    val display = queryDisplayName(context, uri).orEmpty()
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val bytes = context.contentResolver.openInputStream(uri)!!
                        .use { it.readNBytes(64 * 1024 * 1024 + 1) }
                    require(bytes.size <= 64 * 1024 * 1024) { context.getString(R.string.h3_err_audio_too_big) }
                    H3PickedAudio(display, mime, bytes, audioDuration(context, uri))
                }
            }.getOrElse { busy = false; message = context.getString(R.string.h3_msg_audio_read_failed, it.message.orEmpty()); return@launch }
            val ext = picked.name.substringAfterLast('.', "")
                .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "m4a"
            val filename = "h3_${UUID.randomUUID()}.$ext"
            target.uploadImage(picked.bytes, filename, overwrite = false, returnFullPath = true,
                mimeType = picked.mime) { ok, name, error, _ ->
                main.post {
                    busy = false
                    if (target !== ConnectionManager.clientOrNull) message = context.getString(R.string.h3_msg_switched_upload)
                    else if (ok && name != null) {
                        val index = pendingAudio.coerceAtLeast(0)
                        val audios = draft.audios.toMutableList()
                        val seconds = draft.audioSeconds.toMutableList()
                        while (audios.size <= index) audios.add("")
                        while (seconds.size <= index) seconds.add("")
                        audios[index] = name
                        seconds[index] = picked.seconds?.let { "%.1fs".format(it) }.orEmpty()
                        update(draft.copy(audios = audios, audioSeconds = seconds))
                        message = context.getString(R.string.h3_msg_audio_added, index + 1)
                    } else message = context.getString(R.string.h3_msg_audio_upload_failed, error ?: context.getString(R.string.h3_msg_no_filename))
                }
            }
        }
    }

    // Poll the server for the finished output instead of waiting for a gallery refresh: /history
    // is cheap, and it also covers a job the WebSocket missed while the app was backgrounded.
    LaunchedEffect(lastPromptId) {
        val id = lastPromptId ?: return@LaunchedEffect
        val live = ConnectionManager.clientOrNull ?: return@LaunchedEffect
        resultLoading = true
        // Five seconds is right while the job is short, but a long clip means dozens of empty
        // queries. Back off to 30 s after the first minute, capped at ten minutes of watching.
        var elapsed = 0L
        var interval = 5_000L
        while (elapsed < 10 * 60_000L) {
            if (JobRegistry.queueState.value.lastFailure?.promptId == id) break
            val fetched = withContext(Dispatchers.IO) { loadH3Result(context, live, id) }
            if (fetched != null) {
                result = fetched
                resultPoster = withContext(Dispatchers.IO) { h3Poster(context, fetched.uri) }
                resultLoading = false
                return@LaunchedEffect
            }
            delay(interval)
            elapsed += interval
            interval = (interval * 2).coerceAtMost(30_000L)
        }
        resultLoading = false
    }

    /** Opens the picker for slot [index]; the list only grows after a successful upload. */
    fun startPick(index: Int) {
        sourcePickerSlot = "ref:$index"
    }

    /**
     * Add one reference slot. The first [templateSlots] images go straight to the picker — no
     * server probe, no dependence on a cached node definition — and only going past them asks.
     * A failed probe ends the attempt with a message; it never leaves the page with no way in.
     */
    fun addReference() {
        if (busy) return
        val live = ConnectionManager.clientOrNull ?: run { message = context.getString(R.string.h3_msg_connect_first); return }
        if (live !== ConnectionManager.clientOrNull) return
        // The live map may have been filled since this screen was composed; read it here.
        val known = knownRefCeiling
        // Tiles in use, not the backing list length: the draft always carries three empty
        // placeholders, and asking the server while two of them are still free is the bug.
        val used = draft.refs.indexOfLast { it.isNotBlank() } + 1
        // A continuing segment has to keep its last slot for the tail frame, so the ceiling is
        // reached one picture earlier and the answer is "reserved", not "server did not say".
        val ceiling = minOf(refCapacity, if (known > 0) known else refCapacity)
        if (used >= ceiling) {
            message = if (reservesTailSlot) context.getString(R.string.h3_refs_reserve_tail, ceiling)
            else context.getString(R.string.h3_refs_min_max, ceiling)
            return
        }
        when (val next = H3Plan.nextRefSlot(used, minOf(templateSlots, ceiling), known)) {
            is H3Plan.NextRef.Pick -> startPick(next.index)
            H3Plan.NextRef.AskServer -> askCeiling(used) { startPick(used) }
        }
    }

    // Two rapid submissions must not both read 01 from the store and produce the same prefix.
    val chainPositions = remember { mutableMapOf<String, Int>() }

    fun nextChainPosition(chain: String): Int {
        val next = (chainPositions[chain] ?: (SequenceStore.nextPosition(context, chain) - 1)) + 1
        chainPositions[chain] = next
        return next
    }

    /** Whether this mode may refill the tail frame right now. */
    fun autoCaptureAllowed(): Boolean = when (draft.continuationMode) {
        // One at a time and "queue without relaying" both wait until nothing else is ours.
        0, 1 -> queueState.ownActiveJobCount == 0
        else -> true
    }

    /** Clears only the segments this phone queued; the running one is cancelled separately. */
    fun clearOwnQueue() {
        finishRelay()
        val live = ConnectionManager.clientOrNull ?: run { message = context.getString(R.string.h3_msg_connect_first); return }
        val pending = queueState.ownJobs.values
            .filter { it.status == sh.hnet.comfychair.queue.JobStatus.PENDING }
            .map { it.promptId }
        if (pending.isEmpty()) {
            if (queueState.totalQueueSize > 0) {
                live.clearQueue { ok ->
                    main.post {
                        message = if (ok) context.getString(R.string.h3_msg_queue_cleared) else context.getString(R.string.h3_msg_queue_clear_failed)
                    }
                }
            } else {
                message = context.getString(R.string.h3_msg_queue_empty)
            }
            return
        }
        live.deleteQueueItems(pending) { ok ->
            main.post {
                message = if (ok) context.getString(R.string.h3_msg_own_queue_cleared, pending.size) else context.getString(R.string.h3_msg_queue_clear_failed)
                if (ok) pending.forEach { sh.hnet.comfychair.queue.JobRegistry.removeJob(it) }
            }
        }
    }

     fun submit(front: Boolean = false) {
        if (busy) return
        if (relayStage == H3RelayQueue.STAGE_SUBMIT_UNKNOWN || relayStage == H3RelayQueue.STAGE_SUBMITTING) {
            message = context.getString(R.string.h3_msg_relay_submit_unknown)
            return
        }
        if (!relayRunMatches && relayStage != H3RelayQueue.STAGE_IDLE) {
            message = context.getString(R.string.h3_msg_relay_context_mismatch)
            return
        }
        // 完整连拍: only one segment runs at a time, the rest wait here on the phone.
        if (draft.continuationAuto && relayStage != H3RelayQueue.STAGE_READY_TO_SUBMIT &&
            queueState.ownActiveJobCount + relayCount > 0) {
            enqueueSegment()
            message = context.getString(R.string.h3_msg_queued_segment, queueState.ownActiveJobCount + relayCount + 1)
            return
        }
        val live = ConnectionManager.clientOrNull ?: run { message = context.getString(R.string.h3_msg_connect_first); return }
        val errors = mutableListOf<String>()
        // Disabled slots and empty slots are compressed away, so the submitted order is
        // ref_image_0..N-1 exactly as shown by the P1..PN badges.
        val refs = draft.refs.filterIndexed { index, file ->
            file.isNotBlank() && index !in draft.disabledRefs
        }
        // The continuation frame stays last so it keeps the highest <Picture N> whatever
        // else is picked, and one slot serves every segment of a chain.
        val submittedRefs = if (draft.continuation.isNotBlank()) refs + draft.continuation else refs
        if (mode == H3Plan.Mode.R2V && submittedRefs.size > refCapacity) {
            errors.add(
                if (draft.continuationAuto) context.getString(R.string.h3_refs_reserve_tail, refCapacity)
                else context.getString(R.string.h3_err_tail_over_limit, submittedRefs.size, refCapacity)
            )
        }
        when (mode) {
            H3Plan.Mode.R2V -> if (refs.isEmpty()) errors.add(context.getString(R.string.h3_err_ref_required))
            H3Plan.Mode.I2V -> if (draft.first.isBlank()) errors.add(context.getString(R.string.h3_err_first_required))
            H3Plan.Mode.FL2V -> if (draft.first.isBlank() && draft.last.isBlank())
                errors.add(context.getString(R.string.h3_err_fl_required))
            else -> Unit
        }
        var customW: Int? = null
        var customH: Int? = null
        var ratio: String? = draft.ratio
        if (draft.customW.isNotBlank() || draft.customH.isNotBlank()) {
            val w = draft.customW.trim().toIntOrNull()
            val h = draft.customH.trim().toIntOrNull()
            if (w == null || h == null || w < 32 || h < 32) errors.add(context.getString(R.string.h3_err_custom_wh))
            else { customW = w; customH = h }
            ratio = null
        }
        val mpValue = draft.mp.toDoubleOrNull()
        if (mpValue == null && customW == null) errors.add(context.getString(R.string.h3_err_mp_invalid))
        if (mpValue != null && (mpValue < 0.1 || mpValue > 1.0))
            errors.add(context.getString(R.string.h3_err_mp_range))
        // One-at-a-time is the default 连拍 behaviour: relaying a queue would take the last frame
        // of whichever segment happened to finish, which is how a chain silently forks.
        if (draft.continuationAuto && draft.continuationMode == 0 && queueState.ownActiveJobCount > 0) {
            message = context.getString(R.string.h3_msg_relay_single)
            return
        }
        if (errors.isNotEmpty()) { message = errors.joinToString("；"); return }

        // Rolled here rather than at build time so the number on the page is the number that
        // goes out: the draft update and the submitted extras come from the same map.
        val seedKeys = spec.extras.filter { it.input in H3Seed.INPUTS }
        // A pinned seed can still be a leftover from the 64-bit build, and the server drops the
        // whole task over one out-of-range value — repair it on the way out either way.
        val seedPatch: Map<String, String> = if (H3Seed.autoRandom(context)) {
            seedKeys.associate { it.key to H3Seed.next().toString() }
        } else {
            seedKeys.mapNotNull { extra ->
                val raw = draft.extras[extra.key] ?: extra.value.toString()
                H3Seed.fit(raw)?.let { extra.key to it }
            }.toMap()
        }
        if (seedPatch.isNotEmpty()) update(draft.copy(extras = draft.extras + seedPatch))

        busy = true; message = context.getString(R.string.h3_msg_preparing)
        scope.launch {
            val built = runCatching {
                withContext(Dispatchers.IO) {
                    val exported = WorkflowManager.exportWorkflowToComfyUIFormat(workflowId).getOrThrow()
                    val expObj = JSONObject(exported)
                    val apiDoc = if (expObj.optJSONArray("nodes") != null || expObj.has("last_node_id") || expObj.has("links")) {
                        sh.hnet.comfychair.workflow.H3LiteGraphAdapter.convert(expObj)
                    } else {
                        expObj
                    }
                    val wrappedObj = if (apiDoc.optJSONObject("nodes") != null) apiDoc else JSONObject().put("nodes", apiDoc)
                    spec?.modes?.let { mList ->
                        wrappedObj.put("modes", org.json.JSONArray(mList.map { it.name }))
                    }
                    val wrapped = wrappedObj.toString()
                    val attributed = WorkflowManager.applyNodeAttributeEdits(wrapped, workflowId)
                    val plan = H3Plan.resolve(attributed) ?: error(context.getString(R.string.h3_err_workflow_unresolved))
                    H3Plan.build(attributed, plan, H3Plan.Request(
                        mode = mode,
                        prompt = draft.prompt,
                        ratio = ratio,
                        megapixels = mpValue,
                        customWidth = customW,
                        customHeight = customH,
                        multiple = draft.multiple.toIntOrNull(),
                        // The switch, the strength and the file picker all live in this draft;
                        // without this the workflow kept its own LoRA whatever the panel said.
                        loras = plan.loras.map { slot ->
                            val state = draft.loras[slot.id] ?: H3LoraDraft(slot.file, slot.strength, slot.enabled)
                            H3Plan.LoraEdit(
                                id = slot.id,
                                // Only a real change is written, or every run would add a note.
                                file = state.file.takeIf { it.isNotBlank() && it != slot.file },
                                strength = state.strength,
                                enabled = state.enabled
                            )
                        },
                        extraLoras = draft.extraLoras
                            .filter { it.name.isNotBlank() }
                            .take(H3Plan.LoraAdd.MAX_ENTRIES)
                            .map { H3Plan.LoraAdd(it.name, (it.strength.toDoubleOrNull() ?: 1.0)
                                .coerceIn(H3Plan.LoraAdd.MIN_STRENGTH, H3Plan.LoraAdd.MAX_STRENGTH)) },
                        seconds = draft.seconds.toDoubleOrNull(),
                        steps = draft.steps.toIntOrNull(),
                        references = if (mode == H3Plan.Mode.R2V) submittedRefs else emptyList(),
                        refAudios = if (mode == H3Plan.Mode.R2V) {
                            draft.audios.filter { it.isNotBlank() }
                        } else emptyList(),
                        firstFrame = if (mode == H3Plan.Mode.I2V || mode == H3Plan.Mode.FL2V)
                            draft.first.takeIf { it.isNotBlank() } else null,
                        lastFrame = if (mode == H3Plan.Mode.FL2V)
                            draft.last.takeIf { it.isNotBlank() } else null,
                        // An empty slot must also cut the template's own sample image.
                        clearFirstFrame = mode == H3Plan.Mode.FL2V && draft.first.isBlank(),
                        clearLastFrame = mode == H3Plan.Mode.FL2V && draft.last.isBlank(),
                         // Pass the server's declared autogrow ceiling. The request list itself
                         // contains only the picked references; H3Plan separately validates count.
                         maxReferences = if (mode == H3Plan.Mode.R2V)
                             knownRefCeiling.takeIf { it > 0 } else null,
                        extras = modelPayload(
                            plan,
                            if (seedPatch.isEmpty()) draft
                            else draft.copy(extras = draft.extras + seedPatch),
                            context
                        )
                    ), aspectOptions)
                }
            }.getOrElse {
                busy = false; message = context.getString(R.string.h3_msg_prepare_failed, it.localizedText(context)); return@launch
            }
            if (!built.ok) { busy = false; message = built.errors.joinToString("; ") { it.format(context) }; return@launch }
            val preflight = SubmitPreflight.prepare(built.json) { ct, key ->
                ConnectionManager.nodeTypeRegistry.getInputDefinition(ct, key)?.options
            }
            if (!preflight.ok) {
                busy = false
                message = context.getString(R.string.h3_msg_preflight_failed, preflight.issues.joinToString("; ") { it.format(context) })
                return@launch
            }
            // 连拍 naming: one prefix per chain, numbered per submission, so the gallery can tell
            // a chain apart and the files stay recognisable on the PC too.
            var prompt = preflight.prompt
            var chain: String? = null
            var chainPosition = 0
            if (draft.numbered) {
                // A named run is numbered on purpose: the project name is what the gallery groups
                // by and what the server keeps on disk, so both single runs and chains use it.
                chain = H3Naming.chainName(draft.sequenceName, true)
                chainPosition = nextChainPosition(chain)
                prompt = H3Naming.applyPrefix(prompt, H3Naming.prefixFor(chain, chainPosition, true))
            }
            // 采样预览: decided once per run, on the payload that actually goes out. The stored graph
            // is never edited, so a template keeps working whichever way the switch points.
            // Over mobile 5G the full 20 MB `/object_info` may still be downloading when the user
            // hits 生成; if the registry is not populated yet, probe the single 2 KB endpoint so the
            // two-pass Turbo templates still get the preview node injected.
            val previewWanted = AppSettings.isH3PreviewEnabled(context) && AppSettings.isLivePreviewEnabled(context)
            val registry = ConnectionManager.nodeTypeRegistry
            // The node's own schema is also the only place the installed vae_approx models are
            // listed. That list decides whether an animated preview is possible at all, so it is
            // fetched whenever the preview is on — 2 KB against a run that takes minutes.
            val previewSchema = if (!previewWanted) null else withContext(Dispatchers.IO) {
                runCatching {
                    val res = live.getText("/object_info/${H3SamplingPreview.NODE_TYPE}", 64 * 1024)
                    (res as? sh.hnet.comfychair.ComfyUIClient.TextResult.Ok)?.text
                }.getOrNull()
            }
            val nodeAvailable = when {
                !previewWanted -> false
                previewSchema != null -> previewSchema.contains(H3SamplingPreview.NODE_TYPE)
                registry.getNodeDefinition(H3SamplingPreview.NODE_TYPE) != null -> true
                else -> !registry.isPopulated()
            }
            // Frame rate and frame count come from the advanced settings cells, so the two fields
            // there are no longer decoration that this step overwrote with a constant.
            val previewExtras = spec.extras.filter { it.nodeClass == H3SamplingPreview.NODE_TYPE }
            fun previewInt(input: String, fallback: Int): Int =
                previewExtras.firstOrNull { it.input == input }
                    ?.let { draft.extras[it.key] ?: it.value.toString() }
                    ?.toDoubleOrNull()?.toInt() ?: fallback
            val patched = H3SamplingPreview.apply(
                prompt,
                enabled = previewWanted,
                nodeAvailable = nodeAvailable,
                tinyVae = H3SamplingPreview.pickTinyVae(previewSchema),
                frames = previewInt("preview_frames", H3SamplingPreview.ANIMATED_FRAMES),
                fps = previewInt("preview_fps", H3SamplingPreview.PREVIEW_FPS)
            )
            prompt = patched.json
            H3SamplingPreviewState.begin(patched.attached)
            // Keep the last few real payloads: without them a failed run cannot be reproduced.
            withContext(Dispatchers.IO) { archivePayload(context, mode, prompt) }
            val relaySubmission = draft.continuationAuto
            val requestId = UUID.randomUUID().toString()
            val onResult: (Boolean, String?, String?) -> Unit = { ok, id, error ->
                main.post {
                    busy = false
                    if (relaySubmission && autoPrefs.getString("relay_submit_request_id", null) != requestId) return@post
                    if (live !== ConnectionManager.clientOrNull) return@post
                    if (ok && id != null) {
                        chain?.let { SequenceStore.record(context, id, it, chainPosition) }
                        pendingStore.save(PendingJobStore.PendingJob(
                            id, live.getBaseUrl().orEmpty(), "H3 ${context.getString(mode.labelRes)}",
                            System.currentTimeMillis(), "QUEUED"))
                        sh.hnet.comfychair.queue.JobRegistry.clearFailure()
                        // H3 writes through SaveVideo, which stores no prompt in the file, so
                        // the parameters are captured here — this is the only moment they exist.
                        runCatching {
                            val unet = spec.unet?.let { ref ->
                                (draft.extras[ref.key] ?: ref.value)
                                    .substringAfterLast('\\').substringAfterLast('/')
                            }
                            val loras = buildList {
                                draft.loras.values
                                    .filter { it.enabled && it.file.isNotBlank() }
                                    .forEach {
                                        add(LoraInfo(
                                            it.file.substringAfterLast('\\').substringAfterLast('/'),
                                            it.strength
                                        ))
                                    }
                                draft.extraLoras
                                    .filter { it.name.isNotBlank() }
                                    .forEach {
                                        add(LoraInfo(
                                            it.name.substringAfterLast('\\').substringAfterLast('/'),
                                            it.strength.toDoubleOrNull() ?: 1.0
                                        ))
                                    }
                            }
                            val seed = spec.extras.firstOrNull { it.input in H3Seed.INPUTS }
                                ?.let { extra ->
                                    (draft.extras[extra.key] ?: extra.value.toString()).toLongOrNull()
                                }
                            MetadataStore.put(
                                context,
                                ConnectionManager.currentServerId ?: live.getBaseUrl().orEmpty(),
                                id,
                                MetadataStore.ANY_FILE,
                                GenerationMetadata(
                                    positivePrompt = prompt,
                                    seed = seed,
                                    steps = draft.steps.toIntOrNull(),
                                    unets = listOfNotNull(unet),
                                    loras = loras
                                )
                            )
                        }
                        lastPromptId = id; result = null; resultPoster = null
                        if (relaySubmission) {
                            val pendingSaved = autoPrefs.edit()
                                .putString("pending", id)
                                .putString("relay_stage", H3RelayQueue.STAGE_WAITING_RESULT)
                                .remove("relay_submit_fingerprint")
                                .remove("relay_submit_client_id")
                                .remove("relay_submit_request_id")
                                .commit()
                            if (pendingSaved) {
                                relayStage = H3RelayQueue.STAGE_WAITING_RESULT
                                pendingAutoId = id
                            } else {
                                setRelayStage(H3RelayQueue.STAGE_SUBMIT_UNKNOWN)
                                message = context.getString(R.string.h3_msg_relay_persist_failed)
                            }
                        }
                        message = context.getString(R.string.h3_msg_submitted, context.getString(mode.labelRes), id)
                    } else {
                        if (relaySubmission) setRelayStage(H3RelayQueue.STAGE_SUBMIT_UNKNOWN)
                        message = if (relaySubmission) context.getString(R.string.h3_msg_relay_submit_unknown) else context.getString(R.string.h3_msg_submit_failed, error ?: context.getString(R.string.h3_msg_no_prompt_id))
                    }
                }
            }
            if (relaySubmission) {
                if (live !== ConnectionManager.clientOrNull) {
                    busy = false
                    message = context.getString(R.string.h3_msg_relay_context_mismatch)
                    return@launch
                }
                val checkpointSaved = withContext(Dispatchers.IO) {
                    autoPrefs.edit()
                        .putString("relay_server_id", ConnectionManager.currentServerId)
                        .putString("relay_workflow_id", workflowId)
                        .putString("relay_area", area.name)
                        .putString("relay_submit_client_id", ConnectionManager.clientId)
                        .putString("relay_submit_request_id", requestId)
                        .putString("relay_submit_fingerprint", PromptBody.fingerprint(H3Plan.nodes(JSONObject(prompt))))
                        .putString("relay_stage", H3RelayQueue.STAGE_SUBMITTING)
                        .commit()
                }
                if (!checkpointSaved) {
                    busy = false
                    message = context.getString(R.string.h3_msg_relay_persist_failed)
                    return@launch
                }
                relayStage = H3RelayQueue.STAGE_SUBMITTING
            }
            val vm = generationViewModel
            if (vm != null && vm.getClient() === live) {
                vm.startGeneration(
                    prompt,
                    // Borrowing the ordinary pages' owner ids made their progress bars react to an
                    // H3 job and left this page unable to recognise its own.
                    H3_OWNER_ID,
                    ContentType.VIDEO, front = front, requestId = requestId,
                    onResult = { ok, id, error -> onResult(ok, id, error) })
            } else {
                live.submitPrompt(prompt, front = front, requestId = requestId) { ok, id, error, _, _ -> onResult(ok, id, error) }
            }
        }
    }

    // 完整连拍: when a segment finishes, the next queued one becomes the live draft before its
    // frame is captured, so the declaration and the picture number belong to that segment.
    LaunchedEffect(result?.promptId, draft.continuationAuto, relayPaused, pendingAutoId) {
        // Read the delegated state once: a smart cast is not available on a remember-delegated var.
        val finished = result ?: return@LaunchedEffect
        if (!draft.continuationAuto || busy) return@LaunchedEffect
        val expectedPrompt = if (relayStage == H3RelayQueue.STAGE_EXTRACTING_TAIL)
            H3RelayQueue.sourcePromptId(context) else pendingAutoId
        if (expectedPrompt == null || finished.promptId != expectedPrompt) return@LaunchedEffect
        if (!relayRunMatches) {
            message = context.getString(R.string.h3_msg_relay_context_mismatch)
            return@LaunchedEffect
        }
        if (relayPaused) return@LaunchedEffect

        when (relayStage) {
            H3RelayQueue.STAGE_EXTRACTING_TAIL -> {
                // If the uploaded filename was saved but the process died before committing the
                // queue cursor, finish that atomic transition now. Otherwise retry frame extraction.
                if (draft.continuation.isNotBlank()) {
                    if (!commitRelayHead()) message = context.getString(R.string.h3_msg_relay_persist_failed)
                } else captureLastFrame(auto = true)
                return@LaunchedEffect
            }
            H3RelayQueue.STAGE_READY_TO_SUBMIT,
            H3RelayQueue.STAGE_SUBMITTING,
            H3RelayQueue.STAGE_SUBMIT_UNKNOWN -> return@LaunchedEffect
        }

        if (relayCount > 0) {
            val nextDraft = relayPeek().copy(
                continuation = "",
                continuationOwner = H3Continuation.NONE,
                continuationFlag = false,
                continuationAuto = true
            )
            // Persist the new segment before marking the transition in progress. If the process
            // dies before this save, the previous pending prompt is still present and will replay
            // the transition without consuming the queue head.
            val savedNextDraft = withContext(Dispatchers.IO) {
                draftStore.saveDurable(workflowId, area, nextDraft)
            }
            if (!savedNextDraft) {
                message = context.getString(R.string.h3_msg_relay_persist_failed)
                return@LaunchedEffect
            }
            if (!setRelayStage(H3RelayQueue.STAGE_EXTRACTING_TAIL, finished.promptId)) {
                message = context.getString(R.string.h3_msg_relay_persist_failed)
                return@LaunchedEffect
            }
            draft = nextDraft
            relaySubmitAfterFrame = true
            captureLastFrame(auto = true)
        } else if (relayDone > 0) {
            finishRelay()
            // The final clip can still be used manually as the next chain's continuation frame.
            captureLastFrame(auto = true)
        } else {
            // Not part of an active chain; clear only the recovery pointer for this completed job.
            if (autoPrefs.edit().remove("pending").putString("relay_stage", H3RelayQueue.STAGE_IDLE).commit()) {
                relayStage = H3RelayQueue.STAGE_IDLE
                pendingAutoId = null
                captureLastFrame(auto = true)
            }
        }
    }

    LaunchedEffect(relayStage, relayPaused, relayRunMatches) {
        if (relayStage == H3RelayQueue.STAGE_READY_TO_SUBMIT && !relayPaused && !busy && relayRunMatches) {
            relaySubmitTick++
        }
    }

    LaunchedEffect(relaySubmitTick) {
        if (relaySubmitTick > 0) submit(front = false)
    }

    val frames = draft.seconds.toDoubleOrNull()?.let { sec -> spec.duration?.expression?.let { ComfyFrames(it, sec) } }
    val stepsShown = draft.steps.ifBlank { spec.steps?.steps?.toString().orEmpty() }
    // 种子原先长在基础参数展开体里，现在面板要用，提到函数级。
    val seedExtras = spec.extras.filter { it.input in H3Seed.INPUTS }
    val seedAutoState = remember(workflowId) { mutableStateOf(H3Seed.autoRandom(context)) }

    Column(Modifier.fillMaxSize()) {

        // A slim bar names the card you are inside once its own header has scrolled away. The
        // cards themselves are untouched -- folding their headers made every pass-through animate.
        val listState = rememberLazyListState()

        // Tab tapped again while on H3 workspace: smooth scroll back to top
        val reselects = sh.hnet.comfychair.navigation.LocalTabReselects.current
        LaunchedEffect(reselects) {
            reselects.collect { route ->
                if (route == sh.hnet.comfychair.navigation.MainRoute.H3Workspace) {
                    listState.animateScrollToItem(0)
                }
            }
        }

        // 索引必须和 LazyColumn 里的卡片一一对应：二采卡只在 Turbo 模板出现，
        // 少算它一格，吸顶标题会在 Turbo 工作流上整体错位一位。
        val cardTitleRes = buildList {
            add(R.string.h3_prompt_title)
            add(if (area == H3Plan.Area.R2V) R.string.h3_section_refs else R.string.h3_section_material)
            add(R.string.h3_panel_title)
            if (isTurbo) add(R.string.h3_second_pass_title)
            add(R.string.h3_section_models)
            add(R.string.h3_section_advanced)
        }
        val cardTitles = cardTitleRes.map { stringResource(it) }
        // Reading the scroll position in composition would recompose the page every frame, so the
        // bar owns the read and nothing else here touches layoutInfo.
        val stickyTitle = remember(listState, cardTitles) {
            derivedStateOf {
                cardTitles.getOrNull(listState.firstVisibleItemIndex)
                    .takeIf { listState.firstVisibleItemIndex > 0 }
            }
        }
        H3StickyTitle(stickyTitle)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. 提示词
            item {
                var showFullscreenPrompt by rememberSaveable { mutableStateOf(false) }
                H3Card(
                    title = stringResource(R.string.h3_prompt_title),
                    trailing = {
                        if (draft.prompt.isNotEmpty()) {
                            // 一个 28dp 的 × 太不起眼，问过两次「清空在哪」。改成有字的按钮，
                            // 顺手把热区做到 48dp。
                            TextButton(
                                onClick = { update(draft.copy(prompt = "")) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(
                                    stringResource(R.string.h3_prompt_clear),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                ) {
                    // Only where a tail frame can live: this switch writes the declaration for
                    // that slot, and the other tabs have no such slot at all. 连拍模式 shows it
                    // before a frame is attached, because that is when a continuing segment's
                    // prompt still has to be told which picture is its predecessor's last.
                    if (area == H3Plan.Area.R2V &&
                        (draft.continuation.isNotBlank() || draft.continuationAuto)
                    ) {
                        val picture = filledRefs.size + 1
                        val claim = H3Continuation.declaredPicture(draft.prompt)
                        val mismatch = claim != null && claim != picture
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.h3_declare_picture, picture),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                stringResource(R.string.h3_declare_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = draft.continuationInject,
                                onCheckedChange = { setContinuationInject(it) },
                                modifier = Modifier.scale(0.8f)
                            )
                            Box(
                                Modifier
                                    .padding(start = 4.dp)
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .border(
                                        0.8.dp,
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable { showDeclareHelp = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "?",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            when {
                                draft.continuationInject -> stringResource(R.string.h3_declare_state_written)
                                mismatch -> stringResource(R.string.h3_declare_state_mismatch, claim ?: 0, picture)
                                else -> stringResource(R.string.h3_declare_state_manual)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                draft.continuationInject -> MaterialTheme.colorScheme.onPrimaryContainer
                                mismatch -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = draft.prompt,
                            onValueChange = { update(draft.copy(prompt = it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(112.dp)
                                .padding(bottom = 4.dp),
                            // 浅灰填充 + 焦点主色边界（任务书 §6 提示词编辑框那一行）
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        )
                        IconButton(
                            onClick = { showFullscreenPrompt = true },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 4.dp, bottom = 8.dp)
                                .size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.OpenInFull,
                                contentDescription = stringResource(R.string.h3_prompt_fullscreen),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (showFullscreenPrompt) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showFullscreenPrompt = false },
                        properties = androidx.compose.ui.window.DialogProperties(
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false
                        )
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.safeDrawing)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.h3_prompt_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { showFullscreenPrompt = false }) {
                                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.h3_prompt_done))
                                    }
                                }
                                TextField(
                                    value = draft.prompt,
                                    onValueChange = { update(draft.copy(prompt = it)) },
                                    placeholder = { Text(stringResource(R.string.h3_prompt_hint)) },
                                    shape = RoundedCornerShape(24.dp),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(horizontal = 16.dp)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { update(draft.copy(prompt = "")) },
                                        enabled = draft.prompt.isNotEmpty()
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.h3_prompt_backspace))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Button(
                                        onClick = {
                                            showFullscreenPrompt = false
                                            submit(front = false)
                                        },
                                        enabled = !busy && client != null && !draft.mpOutOfRange &&
                                            !draft.relayNameMissing
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.h3_generate))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. 素材
            item {
                H3Card(
                    title = stringResource(if (area == H3Plan.Area.R2V) R.string.h3_section_refs else R.string.h3_section_material),
                    subtitle = if (area == H3Plan.Area.R2V) {
                        val maxCap = minOf(refCapacity, knownRefCeiling.takeIf { it > 0 } ?: refCapacity)
                        (if (filledRefs.isEmpty()) stringResource(R.string.h3_refs_min_max, maxCap)
                        else stringResource(R.string.h3_refs_selected, filledRefs.size, maxCap) +
                            (if (draft.continuation.isNotBlank()) stringResource(R.string.h3_includes_last) else "")) +
                            (if (reservesTailSlot) stringResource(R.string.h3_refs_tail_reserved) else "")
                    } else {
                        stringResource(R.string.h3_current_prefix, modeLabel) + when {
                            mode == H3Plan.Mode.T2V -> stringResource(R.string.h3_note_t2v)
                            mode == H3Plan.Mode.I2V -> stringResource(R.string.h3_note_first)
                            draft.first.isBlank() -> stringResource(R.string.h3_note_last_only)
                            draft.last.isBlank() -> stringResource(R.string.h3_note_first)
                            else -> stringResource(R.string.h3_note_both)
                        }
                    }
                ) {
                    if (area == H3Plan.Area.R2V) {
                        // Tiles, not rows: four references used to cost four full-width previews.
                        // Order is <Picture 1..N>, so the badges follow the real submitted order.
                        val shown = draft.refs.indexOfLast { it.isNotBlank() } + 1
                        val order = draft.refs.indices.filter {
                            draft.refs[it].isNotBlank() && it !in draft.disabledRefs
                        }

                        // The tail frame is a real picture, so it gets a tile of its own right
                        // after the last submitted reference. Its number is that position, never
                        // a hardcoded 4.
                        val tail = draft.continuation
                        val tailBadge = order.size + 1

                        if (shown == 0 && tail.isBlank()) {
                            H3RefEmptyTile(
                                enabled = !busy && client != null,
                                onClick = { addReference() }
                            )
                        } else {
                            val cells = mutableListOf<Int>()
                            if (tail.isNotBlank() && order.isEmpty()) cells += H3_CELL_TAIL
                            for (i in 0 until shown) {
                                cells += i
                                if (tail.isNotBlank() && order.isNotEmpty() && i == order.last()) {
                                    cells += H3_CELL_TAIL
                                }
                            }
                            cells += H3_CELL_ADD
                            cells.chunked(3).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { cell ->
                                        if (cell == H3_CELL_ADD) {
                                            H3RefAddTile(
                                                modifier = Modifier.weight(1f),
                                                // Always tappable: addReference() either opens the
                                                // picker or explains the limit. A disabled button is
                                                // how the page became unusable last time.
                                                enabled = true,
                                                busy = busy,
                                                onClick = { addReference() }
                                            )
                                        } else if (cell == H3_CELL_TAIL) {
                                            // Read-only: the frame is managed by the row below,
                                            // and tapping it must never reorder the chain.
                                            H3RefTile(
                                                modifier = Modifier.weight(1f),
                                                file = tail,
                                                workflowId = workflowId,
                                                revision = previewRevision,
                                                badge = tailBadge,
                                                disabled = false,
                                                selected = false,
                                                enabled = false,
                                                tail = true,
                                                onClick = {}
                                            )
                                        } else {
                                            val file = draft.refs[cell]
                                            H3RefTile(
                                                modifier = Modifier.weight(1f),
                                                file = file,
                                                workflowId = workflowId,
                                                revision = previewRevision,
                                                badge = order.indexOf(cell).takeIf { it >= 0 }?.plus(1),
                                                disabled = cell in draft.disabledRefs,
                                                selected = selectedRef == cell,
                                                enabled = !busy,
                                                onClick = {
                                                    selectedRef = if (selectedRef == cell) -1 else cell
                                                    if (file.isBlank() && selectedRef == cell) {
                                                        sourcePickerSlot = "ref:$cell"
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }

                        if (selectedRef in draft.refs.indices && draft.refs[selectedRef].isNotBlank()) {
                            val index = selectedRef
                            val disabled = index in draft.disabledRefs
                            H3RefActionBar(
                                enabled = !busy,
                                disabled = disabled,
                                canLeft = index > 0,
                                canRight = index < draft.refs.lastIndex,
                                onLeft = {
                                    val refs = draft.refs.toMutableList()
                                    val prev = refs[index - 1]
                                    refs[index - 1] = refs[index]; refs[index] = prev
                                    update(draft.copy(refs = refs))
                                    selectedRef = index - 1
                                },
                                onRight = {
                                    val refs = draft.refs.toMutableList()
                                    val next = refs[index + 1]
                                    refs[index + 1] = refs[index]; refs[index] = next
                                    update(draft.copy(refs = refs))
                                    selectedRef = index + 1
                                },
                                 onReplace = { sourcePickerSlot = "ref:$index" },
                                onToggle = {
                                    update(draft.copy(
                                        disabledRefs = if (disabled) draft.disabledRefs - index
                                        else draft.disabledRefs + index
                                    ))
                                },
                                onDelete = {
                                    update(draft.copy(
                                        refs = draft.refs.filterIndexed { i, _ -> i != index },
                                        disabledRefs = draft.disabledRefs
                                            .filter { it != index }
                                            .map { if (it > index) it - 1 else it }
                                    ))
                                    selectedRef = -1
                                    previewRevision++
                                }
                            )
                        }

                        Text(
                            if (shown == 0) stringResource(R.string.h3_order_hint_empty)
                            else stringResource(R.string.h3_order_hint),
                            style = MaterialTheme.typography.labelSmall
                        )


                        // Last-frame continuation: one slot, reused by every segment of a chain.
                        H3ContinuationSlot(
                            file = draft.continuation,
                            auto = draft.continuationAuto,
                            enabled = !busy,
                            hasResult = result != null,
                            mode = 0,
                        onRelayHelp = { showRelayHelp = true },
                            autoName = draft.numbered,
                            sequenceName = draft.sequenceName,
                            queuedCount = queueState.ownActiveJobCount + relayCount,
                            onMode = { update(draft.copy(continuationMode = it)) },
                            onSequenceName = { update(draft.copy(sequenceName = it)) },
                            onCapture = { captureLastFrame(auto = false) },
                            onPickVideo = { tailVideoPicker.launch("video/*") },
                            onToggleAuto = { setRelayMode(it) },
                            onClear = {
                                val owned = draft.continuationOwner
                                update(
                                    draft.copy(
                                        continuation = "",
                                        prompt = H3Continuation.revert(
                                            draft.prompt, owned, draft.continuationFlag
                                        ),
                                        continuationOwner = H3Continuation.NONE,
                                        continuationFlag = false
                                    )
                                )
                                message = when {
                                    owned != H3Continuation.NONE ->
                                        context.getString(R.string.h3_unlink_cleared_decl, owned)
                                    draft.continuationFlag ->
                                        context.getString(R.string.h3_unlink_cleared_summary)
                                    else ->
                                        context.getString(R.string.h3_unlink_cleared_manual)
                                }
                            }
                        )

                        // Standalone audio: one socket, closed by default, and nothing is submitted
                        // when it is empty — the loader is pruned with the stale sample it carried.
                        if (spec.supportsAudio) {
                            H3Divider()
                            H3AudioSection(
                                files = draft.audios,
                                seconds = draft.audioSeconds,
                                max = spec.audioSlots.size,
                                expanded = showAudio,
                                enabled = !busy && client != null,
                                onToggle = { showAudio = !showAudio },
                                onPick = { index -> pendingAudio = index; audioPicker.launch("audio/*") },
                                onRemove = { index ->
                                    update(draft.copy(
                                        audios = draft.audios.filterIndexed { i, _ -> i != index },
                                        audioSeconds = draft.audioSeconds.filterIndexed { i, _ -> i != index }
                                    ))
                                }
                            )
                            if (draft.audios.any { it.isNotBlank() }) Text(
                                stringResource(R.string.h3_audio_order_hint),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else {
                        // 首帧 / 尾帧 side by side: they are one pair, and a 16:9 half-width
                        // preview is still readable.
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            H3FrameSlot(
                                label = stringResource(R.string.h3_first_frame),
                                file = draft.first,
                                workflowId = workflowId,
                                revision = previewRevision,
                                enabled = !busy && client != null,
                                modifier = Modifier.weight(1f),
                                onPick = { sourcePickerSlot = "first" },
                                onRemove = { update(draft.copy(first = "")); previewRevision++ }
                            )
                            H3FrameSlot(
                                label = stringResource(R.string.h3_last_frame),
                                file = draft.last,
                                workflowId = workflowId,
                                revision = previewRevision,
                                enabled = !busy && client != null,
                                modifier = Modifier.weight(1f),
                                onPick = { sourcePickerSlot = "last" },
                                onRemove = { update(draft.copy(last = "")); previewRevision++ }
                            )
                        }
                        // The node stretches the first frame onto the canvas and cover-crops the
                        // last one, so a mismatched first frame arrives distorted.
                        frameAspectWarning(context, workflowId, draft.first, draft.ratio)?.let { notice ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(notice, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f))
                                val suggested = frameAspectSuggestedRatio(context, workflowId, draft.first, ratios)
                                if (suggested != null && suggested != draft.ratio) {
                                    TextButton(enabled = !busy, onClick = { pickRatio(suggested) }) {
                                        Text(stringResource(R.string.h3_follow_first_ratio), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        Text(
                            when (mode) {
                                H3Plan.Mode.T2V -> stringResource(R.string.h3_note_t2v_card)
                                H3Plan.Mode.I2V -> stringResource(R.string.h3_note_i2v_card)
                                else -> stringResource(R.string.h3_note_fl_card)
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // 3. 常用参数：四项常看的值以四宫格常驻主屏，卡头右上角的「参数面板」才是编辑入口。
            item {
                H3Card(
                    title = "◇ " + stringResource(R.string.h3_panel_card_title),
                    trailing = {
                        TextButton(onClick = { showPanel = true }) {
                            Text(stringResource(R.string.h3_panel_title) + " ›")
                        }
                    }
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        H3PanelTile(
                            label = stringResource(R.string.h3_panel_duration),
                            value = stringResource(R.string.h3_seconds_value, draft.seconds.ifBlank { "—" }),
                            icon = "◷",
                            selected = false,
                            modifier = Modifier.weight(1f)
                        ) { showPanel = true }
                        H3PanelTile(
                            label = stringResource(R.string.h3_aspect_ratio),
                            value = H3Resolution.label(context, draft.ratio),
                            icon = "▣",
                            selected = false,
                            modifier = Modifier.weight(1f)
                        ) { showPanel = true }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        H3PanelTile(
                            label = stringResource(R.string.h3_megapixels),
                            value = "${draft.mp} MP",
                            icon = "▧",
                            selected = false,
                            modifier = Modifier.weight(1f)
                        ) { showPanel = true }
                        H3PanelTile(
                            label = stringResource(R.string.h3_sampling_steps),
                            value = stringResource(R.string.h3_steps_value, stepsShown.ifBlank { "—" }),
                            icon = "◉",
                            selected = false,
                            modifier = Modifier.weight(1f)
                        ) { showPanel = true }
                    }
                }
            }

            // 3.5 二采与 Turbo 增强：只在含 94:value 的 Turbo 模板出现，默认折叠。
            // 折叠摘要全从 draft 现算，二采开关的正反语义在下面 H3TurboPipelineBody 里照旧。
            if (isTurbo) {
                item {
                    val passOn = draft.extras[turboKeys.skip]?.toBooleanStrictOrNull() != true
                    val passMp = draft.extras[turboKeys.mp] ?: "1"
                    val passSteps = draft.extras[turboKeys.steps] ?: "3"
                    val rtxScale = draft.extras[turboKeys.rtx] ?: "1.2"
                    val hasFrameGen = turboKeys.frameGen.isNotEmpty()
                    val frameGen = draft.extras[turboKeys.frameGen] ?: "1"
                    val summary = listOfNotNull(
                        stringResource(
                            R.string.h3_turbo_summary,
                            stringResource(if (passOn) R.string.h3_second_pass_on else R.string.h3_second_pass_off),
                            passMp, passSteps, rtxScale
                        ),
                        if (!hasFrameGen) null
                        else if (frameGen != "1") stringResource(R.string.h3_interp_on, frameGen)
                        else stringResource(R.string.h3_interp_off)
                    ).joinToString(" · ")
                    H3Card(
                        title = stringResource(R.string.h3_second_pass_title),
                        subtitle = summary,
                        trailing = {
                            TextButton(onClick = { showTurbo = !showTurbo }) {
                                Text(if (showTurbo) stringResource(R.string.h3_collapse) else stringResource(R.string.h3_expand))
                            }
                        }
                    ) {
                        if (showTurbo) H3TurboPipelineBody(
                            spec = spec,
                            draft = draft,
                            busy = busy,
                            onEditNumber = { param, current ->
                                editText = current
                                editing = param
                            },
                            onUpdate = { update(it) }
                        )
                    }
                }
            }

            // 4. 模型
            item {
                val loraOn = spec.loras.count { slot ->
                    draft.loras[slot.id]?.enabled ?: slot.enabled
                }
                H3Card(
                    stringResource(R.string.h3_section_models),
                    subtitle = null,
                    trailing = {
                        TextButton(onClick = { showModels = !showModels }) {
                            Text(if (showModels) stringResource(R.string.h3_collapse) else stringResource(R.string.h3_expand))
                        }
                    }
                ) {
                    if (showModels) {
                        H3Group(stringResource(R.string.h3_group_unet)) {
                            H3ModelDropdown("UNET", spec.unet, draft, models.unets, !busy) { update(it) }
                        }
                        H3Group(
                            title = stringResource(R.string.h3_group_lora),
                            hint = if (spec.loras.isEmpty()) null
                            else stringResource(R.string.h3_lora_enabled, loraOn, spec.loras.size)
                        ) {
                            if (spec.loras.isEmpty()) Text(stringResource(R.string.h3_no_lora_loader),
                                style = MaterialTheme.typography.labelSmall)
                            spec.loras.forEach { slot ->
                                val state = draft.loras[slot.id] ?: H3LoraDraft(slot.file, slot.strength, slot.enabled)
                                val label = if (slot.isPowerSlot) "LoRA ${slot.entryKey}" else "LoRA"
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(checked = state.enabled, enabled = !busy, onCheckedChange = {
                                            update(draft.copy(loras = draft.loras + (slot.id to state.copy(enabled = it))))
                                        })
                                        Spacer(Modifier.width(6.dp))
                                        Text(label, style = MaterialTheme.typography.labelLarge)
                                        Spacer(Modifier.weight(1f))
                                        H3CompactField(
                                            value = state.strength.toString(),
                                            onValueChange = {
                                                val v = it.toDoubleOrNull() ?: return@H3CompactField
                                                update(draft.copy(loras = draft.loras + (slot.id to state.copy(strength = v))))
                                            },
                                            placeholder = stringResource(R.string.h3_strength),
                                            enabled = !busy,
                                            keyboardType = KeyboardType.Decimal,
                                            modifier = Modifier.width(92.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    H3ModelChoiceBox(
                                        label = stringResource(R.string.h3_lora_file),
                                        selectedValue = state.file,
                                        options = models.loras,
                                        onValueChange = {
                                            update(draft.copy(loras = draft.loras + (slot.id to state.copy(file = it))))
                                        },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        // Extra LoRA chain: appended after the workflow's own loaders, only in the
                        // submission copy — same behaviour as ComfyChair's own LoRA injection.
                        H3Group(
                            title = stringResource(R.string.h3_group_extra_lora),
                            hint = stringResource(R.string.h3_lora_count_short,
                                draft.extraLoras.size, H3Plan.LoraAdd.MAX_ENTRIES),
                            action = {
                                TextButton(
                                    enabled = !busy && draft.extraLoras.size < H3Plan.LoraAdd.MAX_ENTRIES,
                                    onClick = { update(draft.copy(extraLoras = draft.extraLoras + H3LoraAdd())) }
                                ) { Text("+ " + stringResource(R.string.h3_add)) }
                            }
                        ) {
                            Text(stringResource(R.string.h3_append_hint),
                                style = MaterialTheme.typography.labelSmall)
                            draft.extraLoras.forEachIndexed { index, entry ->
                                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(Modifier.weight(1f)) {
                                            H3ModelChoiceBox(
                                                label = stringResource(R.string.h3_extra_lora_n, index + 1),
                                                selectedValue = entry.name,
                                                options = models.loras,
                                                onValueChange = { chosen ->
                                                    val list = draft.extraLoras.toMutableList()
                                                    list[index] = entry.copy(name = chosen)
                                                    update(draft.copy(extraLoras = list))
                                                },
                                                enabled = !busy,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(enabled = !busy, onClick = {
                                            update(draft.copy(extraLoras = draft.extraLoras.filterIndexed { i, _ -> i != index }))
                                        }) { Text(stringResource(R.string.h3_remove)) }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(R.string.h3_strength),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        H3CompactField(
                                            value = entry.strength,
                                            onValueChange = { text ->
                                                val list = draft.extraLoras.toMutableList()
                                                list[index] = entry.copy(
                                                    strength = text.filter { c -> c.isDigit() || c == '.' })
                                                update(draft.copy(extraLoras = list))
                                            },
                                            placeholder = "1.0",
                                            enabled = !busy,
                                            keyboardType = KeyboardType.Decimal,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                        // 模型列表刷新留在本卡底部：换的是这份工作流要用的文件，不该跑进系统设置页。
                        H3Divider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { ConnectionManager.refreshServerData() }) { Text(stringResource(R.string.h3_refresh_models)) }
                            Text(models.fileRefreshStatus, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // 5. 任务：不再有单独的卡片
            // The ordinary four pages show progress in the bar under the title, the result in the
            // preview area and the queue length next to the button. This page now does the same,
            // so a card listing the last three prompt ids has nothing left to say.

            // 6. 高级
            item {
                // 摘要只报能可靠解析到的项：采样器与一采调度有就报，缺一个就只报字段数。
                val visibleExtras = spec.extras.filter { !it.hidden && it.input !in H3Seed.INPUTS }
                val samplerRef = visibleExtras.firstOrNull { it.input == "sampler_name" }
                val schedulerRef = visibleExtras.firstOrNull { it.labelRes == R.string.h3_field_first_scheduler }
                    ?: visibleExtras.firstOrNull { it.input == "scheduler" }
                val samplerValue = samplerRef?.let { draft.extras[it.key] ?: it.value.toString() }
                val schedulerValue = schedulerRef?.let { draft.extras[it.key] ?: it.value.toString() }
                H3Card(
                    title = stringResource(R.string.h3_section_advanced),
                    subtitle = if (samplerValue != null && schedulerValue != null)
                        stringResource(R.string.h3_advanced_summary, samplerValue, schedulerValue, visibleExtras.size)
                    else stringResource(R.string.h3_extras_count, visibleExtras.size),
                    trailing = {
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text(if (showAdvanced) stringResource(R.string.h3_collapse) else stringResource(R.string.h3_expand))
                        }
                    }
                ) {
                    if (showAdvanced) {
                        // A. 模型配置：编码器与 VAE 极少换，放在这里，免得三只下拉把 UNET 和 LoRA 埋掉。
                        H3Group(stringResource(R.string.h3_group_model_files)) {
                            H3ModelDropdown(stringResource(R.string.h3_clip_encoder), spec.clip, draft,
                                models.clips.ifEmpty { models.textEncoders }, !busy) { update(it) }
                            H3ModelDropdown(stringResource(R.string.h3_video_vae), spec.videoVae, draft, models.vaes, !busy) { update(it) }
                            H3ModelDropdown(stringResource(R.string.h3_audio_vae), spec.audioVae, draft, models.vaes, !busy) { update(it) }
                        }

                        // C. 采样与优化：字段全部来自 spec.extras，两列排，不再一项一行。
                        H3Group(stringResource(R.string.h3_group_sampling)) {
                            if (visibleExtras.isEmpty()) Text(stringResource(R.string.h3_no_scalar_params),
                                style = MaterialTheme.typography.labelSmall)
                            // 种子在主页面有自己的控件；在这里再放一份是两份 seed 打架的来源。
                            // 选项表来自服务器对这类节点这一格的声明，不是我们猜的：采样器、采样调度
                            // 都是这样拿到全量可选项，换一套工作流也不用改代码。
                            val optionMap = remember(models.nodeDefinitionsRevision, visibleExtras) {
                                visibleExtras.associate {
                                    it.key to (ConnectionManager.nodeTypeRegistry
                                        .getInputDefinition(it.nodeClass, it.input)?.options.orEmpty())
                                }
                            }
                            val withOptions = visibleExtras.filter { !optionMap[it.key].isNullOrEmpty() }
                            val withoutOptions = visibleExtras.filter { optionMap[it.key].isNullOrEmpty() }
                            val switches = withoutOptions.filter { it.isSwitch }
                            val numbers = withoutOptions.filter { !it.isSwitch && isNumericExtra(it) }
                            val texts = withoutOptions.filter { !it.isSwitch && !isNumericExtra(it) }
                            H3AdvancedGrid(withOptions, optionMap, draft, !busy, ::update)
                            H3AdvancedGrid(switches, optionMap, draft, !busy, ::update)
                            H3AdvancedGrid(numbers, optionMap, draft, !busy, ::update)
                            H3AdvancedGrid(texts, optionMap, draft, !busy, ::update)
                            Text(stringResource(R.string.h3_advanced_kept),
                                style = MaterialTheme.typography.labelSmall)
                        }

                        // D1. 精确尺寸：直接给宽高，绕过比例选择器。对齐倍数只在高级设置里有一处
                        // ——原来这里也有一份，而填了宽高时那一份根本不参与（对齐被写死 32）。
                        H3Group(stringResource(R.string.h3_exact_size_title)) {
                            // 第一行：精确宽 + 精确高 + 清除
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                H3CompactField(
                                    value = draft.customW,
                                    onValueChange = { text ->
                                        update(draft.copy(customW = text.filter(Char::isDigit), mpOutOfRange = false))
                                    },
                                    placeholder = stringResource(R.string.h3_exact_width),
                                    enabled = !busy,
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f)
                                )
                                H3CompactField(
                                    value = draft.customH,
                                    onValueChange = { text ->
                                        update(draft.copy(customH = text.filter(Char::isDigit), mpOutOfRange = false))
                                    },
                                    placeholder = stringResource(R.string.h3_exact_height),
                                    enabled = !busy,
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    enabled = !busy,
                                    onClick = { update(draft.copy(customW = "", customH = "")) },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.h3_clear).chunked(1).joinToString("\n"),
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // D2. 参数预设：整套参数存成一份，与上面填尺寸是两件事，合成一张卡时
                        // 预设列表会被当成尺寸的一部分，越滚越长。
                        H3Group(stringResource(R.string.h3_presets_title)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                H3CompactField(
                                    value = presetName,
                                    onValueChange = { presetName = it },
                                    placeholder = stringResource(R.string.h3_preset_name),
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    enabled = !busy && presetName.isNotBlank(),
                                    onClick = {
                                        presetStore.save(presetName.trim(), draft)
                                        presets = presetStore.list()
                                        message = context.getString(R.string.h3_preset_saved, presetName.trim())
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                    modifier = Modifier.height(42.dp)
                                ) {
                                    Text(stringResource(R.string.h3_save_short), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            presets.forEach { (name, snapshot) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.weight(1f))
                                    TextButton(enabled = !busy, onClick = {
                                        update(snapshot.applyTo(draft))
                                        message = context.getString(R.string.h3_preset_applied, name)
                                    }) { Text(stringResource(R.string.h3_apply)) }
                                    TextButton(enabled = !busy, onClick = {
                                        presetStore.delete(name)
                                        presets = presetStore.list()
                                    }) { Text(stringResource(R.string.h3_delete)) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(enabled = !busy, onClick = {
                                    val name = WorkflowManager.generateUniqueDuplicateName(workflow.name)
                                    runCatching {
                                        WorkflowManager.duplicateWorkflow(workflowId, name,
                                            workflow.description.ifBlank { workflow.name } + context.getString(R.string.h3_copy_suffix, ""))
                                    }.onSuccess { message = context.getString(R.string.h3_saved_as, name) }
                                        .onFailure { message = context.getString(R.string.h3_save_failed, it.message.orEmpty()) }
                                }) { Text(stringResource(R.string.h3_save_as_new)) }
                                TextButton(onClick = onOpenWorkflowSettings) { Text(stringResource(R.string.h3_workflow_manage)) }
                            }
                        }
                    }
                    // 告警不受折叠影响：它说的是这份工作流会出问题，收起时更该看得见。
                    spec.warnings.forEach {
                        Text(it.format(context), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (showPanel) H3ParamPanel(
            draft = draft,
            ratioPairs = commonRatioPairs,
            extraRatioPairs = extraRatioPairs,
            ratioEnabled = ratioEnabled,
            busy = busy,
            seedPresent = seedExtras.isNotEmpty(),
            seedAuto = seedAutoState.value,
            seedKey = seedExtras.firstOrNull()?.key,
            seedValue = seedExtras.firstOrNull()?.let { draft.extras[it.key] ?: it.value.toString() },
            onUpdate = { update(it) },
            onPickRatio = { pickRatio(it) },
            onSeedAuto = { on -> H3Seed.setAutoRandom(context, on); seedAutoState.value = on },
            onSeedRoll = {
                seedExtras.forEach { e -> update(draft.copy(extras = draft.extras + (e.key to H3Seed.next().toString()))) }
            },
            onCustom = { param, current -> editText = current; editing = param },
            onDismiss = { showPanel = false }
        )

        // 数值弹窗放在页面级：原来它长在基础参数展开体里，收起二采卡片后自定义框会静默失效。
        when (editing) {
            H3NumberParam.SECONDS -> {
                val v = editText.toDoubleOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_duration), unit = stringResource(R.string.h3_unit_seconds),
                hint = stringResource(R.string.h3_hint_duration),
                value = editText,
                valid = v != null && v >= 4.0 && v <= 15.0,
                onChange = { editText = it },
                onConfirm = {
                    update(draft.copy(seconds = editText.trimEnd('.')))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.MEGAPIXELS -> {
                val v = editText.toDoubleOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_megapixels), unit = "MP",
                hint = stringResource(R.string.h3_hint_mp),
                value = editText,
                valid = v != null && v >= 0.1 && v <= 1.0,
                onChange = { editText = it },
                onConfirm = {
                    update(draft.copy(
                    mp = editText.trimEnd('.'), customW = "", customH = "",
                    mpOutOfRange = false
                    ))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.STEPS -> {
                val v = editText.toIntOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_steps), unit = stringResource(R.string.h3_unit_steps),
                hint = stringResource(R.string.h3_hint_steps),
                value = editText,
                valid = v != null && v in 1..100,
                wholeNumbers = true,
                onChange = { editText = it },
                onConfirm = {
                    update(draft.copy(steps = editText.trimEnd('.')))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.SECOND_PASS_MP -> {
                val v = editText.toDoubleOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_second_pass_mp), unit = "MP",
                hint = stringResource(R.string.h3_hint_second_pass_mp),
                value = editText,
                valid = v != null && v >= 0.1 && v <= 8.0,
                onChange = { editText = it },
                onConfirm = {
                    if (turboKeys.mp.isNotEmpty()) update(draft.copy(extras = draft.extras + (turboKeys.mp to editText.trimEnd('.'))))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.SECOND_PASS_STEPS -> {
                val v = editText.toIntOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_second_pass_steps), unit = stringResource(R.string.h3_unit_steps),
                hint = stringResource(R.string.h3_hint_second_pass_steps),
                value = editText,
                valid = v != null && v in 1..50,
                wholeNumbers = true,
                onChange = { editText = it },
                onConfirm = {
                    if (turboKeys.steps.isNotEmpty()) update(draft.copy(extras = draft.extras + (turboKeys.steps to editText.trimEnd('.'))))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.RTX_SCALE -> {
                val v = editText.toDoubleOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_rtx_scale), unit = stringResource(R.string.h3_unit_times),
                hint = stringResource(R.string.h3_hint_rtx_scale),
                value = editText,
                valid = v != null && v >= 1.0 && v <= 4.0,
                onChange = { editText = it },
                onConfirm = {
                    if (turboKeys.rtx.isNotEmpty()) update(draft.copy(extras = draft.extras + (turboKeys.rtx to editText.trimEnd('.'))))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            null -> Unit
        }

        // 数值弹窗放在页面级：原来它长在基础参数展开体里，收起二采卡片后自定义框会静默失效。
        when (editing) {
            H3NumberParam.SECONDS -> {
                val v = editText.toDoubleOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_duration), unit = stringResource(R.string.h3_unit_seconds),
                hint = stringResource(R.string.h3_hint_duration),
                value = editText,
                valid = v != null && v >= 4.0 && v <= 15.0,
                onChange = { editText = it },
                onConfirm = {
                    update(draft.copy(seconds = editText.trimEnd('.')))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.MEGAPIXELS -> {
                val v = editText.toDoubleOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_megapixels), unit = "MP",
                hint = stringResource(R.string.h3_hint_mp),
                value = editText,
                valid = v != null && v >= 0.1 && v <= 1.0,
                onChange = { editText = it },
                onConfirm = {
                    update(draft.copy(
                    mp = editText.trimEnd('.'), customW = "", customH = "",
                    mpOutOfRange = false
                    ))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.STEPS -> {
                val v = editText.toIntOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_steps), unit = stringResource(R.string.h3_unit_steps),
                hint = stringResource(R.string.h3_hint_steps),
                value = editText,
                valid = v != null && v in 1..100,
                wholeNumbers = true,
                onChange = { editText = it },
                onConfirm = {
                    update(draft.copy(steps = editText.trimEnd('.')))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.SECOND_PASS_MP -> {
                val v = editText.toDoubleOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_second_pass_mp), unit = "MP",
                hint = stringResource(R.string.h3_hint_second_pass_mp),
                value = editText,
                valid = v != null && v >= 0.1 && v <= 8.0,
                onChange = { editText = it },
                onConfirm = {
                    if (turboKeys.mp.isNotEmpty()) update(draft.copy(extras = draft.extras + (turboKeys.mp to editText.trimEnd('.'))))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.SECOND_PASS_STEPS -> {
                val v = editText.toIntOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_second_pass_steps), unit = stringResource(R.string.h3_unit_steps),
                hint = stringResource(R.string.h3_hint_second_pass_steps),
                value = editText,
                valid = v != null && v in 1..50,
                wholeNumbers = true,
                onChange = { editText = it },
                onConfirm = {
                    if (turboKeys.steps.isNotEmpty()) update(draft.copy(extras = draft.extras + (turboKeys.steps to editText.trimEnd('.'))))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            H3NumberParam.RTX_SCALE -> {
                val v = editText.toDoubleOrNull()
                H3NumberDialog(
                title = stringResource(R.string.h3_custom_rtx_scale), unit = stringResource(R.string.h3_unit_times),
                hint = stringResource(R.string.h3_hint_rtx_scale),
                value = editText,
                valid = v != null && v >= 1.0 && v <= 4.0,
                onChange = { editText = it },
                onConfirm = {
                    if (turboKeys.rtx.isNotEmpty()) update(draft.copy(extras = draft.extras + (turboKeys.rtx to editText.trimEnd('.'))))
                    editing = null
                },
                onDismiss = { editing = null }
                )
            }
            null -> Unit
        }


        if (showRelayHelp) AlertDialog(
            onDismissRequest = { showRelayHelp = false },
            title = { Text(stringResource(R.string.h3_relay_title)) },
            text = {
                Text(
                    stringResource(R.string.h3_relay_help)
                )
            },
            confirmButton = {
                TextButton(onClick = { showRelayHelp = false }) { Text(stringResource(R.string.h3_got_it)) }
            }
        )
        if (showDeclareHelp) AlertDialog(
            onDismissRequest = { showDeclareHelp = false },
            title = { Text(stringResource(R.string.h3_declare_title)) },
            text = {
                // Each question keeps its own small heading: the old copy packed all three into
                // one run of text, so a reader looking for "will it duplicate mine?" had to
                // parse the whole block.
                val tailPicture = filledRefs.size + 1
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.h3_declare_what),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.h3_declare_line, tailPicture))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.h3_declare_next),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.h3_declare_no_desc))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.h3_declare_dup),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.h3_declare_dup_body))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeclareHelp = false }) { Text(stringResource(R.string.h3_got_it)) }
            }
        )

        // 采样预览 sits directly on top of the bar: whatever the page is scrolled to, the picture and
        // the 中止 button are within one thumb's reach of each other.
        H3SamplingPreviewStrip(
            frame = previewFrame,
            running = ownJobExecuting && (previewAttached || previewFrame != null),
            runKey = lastPromptId,
            fallbackStep = if (genState?.stage == ProgressStage.SAMPLE) genState.progress else 0,
            fallbackTotal = if (genState?.stage == ProgressStage.SAMPLE) genState.maxProgress else 0,
            onCancel = { abortOwnJob() },
            visible = previewVisible,
            // The eye writes the same setting as 设置 → 生成. It only hides the picture for the run in
            // flight — that payload already carries the node, so the line stays and one tap brings it
            // back; the next submission is the one that goes out without it.
            onTogglePreview = { on ->
                AppSettings.setH3PreviewEnabled(context, on)
                H3SamplingPreviewState.setVisible(on)
                message = if (on) {
                    context.getString(R.string.h3_msg_preview_on)
                } else {
                    context.getString(R.string.h3_msg_preview_off)
                }
            }
        )

        // 底部固定条：只剩摘要和生成。切换模板与编辑结构都是低频动作，已移入高级设置，
        // 但模板名必须留在这里——按下生成的那一刻要看得见这条命是哪个模板跑的。
        // 白面 + 一道轻阴影分界。原来用 tonalElevation，等于把品牌色染进这条底栏，
        // 05/06/08 下会看到一条米色、蓝色、紫色带子，跟导航之间断层。
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = LocalAppElevations.current.floating
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Feedback sits next to the button that produced it: a failed preflight used to
                // appear at the top of the page, which a scrolled-down user never saw.
                if (message.isNotEmpty()) Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    // 提示文字的颜色按内容判定：错误类消息统一用 error 色。
                    // 两种语言的关键词都列上，切换语言后判定不变。
                    color = if (MESSAGE_ERROR_WORDS.any { message.contains(it) }) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        // The template name is already the big line at the top of the page; the
                        // bar carries parameters only, first pass then second pass on its own line.
                        val baseSummary = listOfNotNull(
                            H3Resolution.label(
                                context,
                                if (draft.customW.isNotBlank()) "${draft.customW}:${draft.customH}"
                                else draft.ratio
                            ),
                            "${draft.mp} MP",
                            "${draft.seconds}s",
                            if (stepsShown.isBlank()) null else stringResource(R.string.h3_steps_count, stepsShown)
                        ).joinToString(" · ")
                        // Second pass gets its own two lines: one line of everything ran off the
                        // edge on a phone, and the values that fell off were the ones that matter.
                        val secondPassOn = isTurbo &&
                            draft.extras[turboKeys.skip]?.toBooleanStrictOrNull() != true
                        val secondPassLine = when {
                            !isTurbo -> null
                            !secondPassOn -> stringResource(R.string.h3_second_pass_off)
                            else -> listOfNotNull(
                                stringResource(R.string.h3_second_pass_short),
                                (draft.extras[turboKeys.mp] ?: "1").let { "$it MP" },
                                (draft.extras[turboKeys.steps] ?: "3").let { stringResource(R.string.h3_steps_count, it) },
                                draft.extras[turboKeys.denoise]?.let { stringResource(R.string.h3_denoise_short, it) }
                            ).joinToString(" · ")
                        }
                        val accelLine = if (!isTurbo) null else {
                            val frameGen = draft.extras[turboKeys.frameGen] ?: "1"
                            listOfNotNull(
                                "RTX ${draft.extras[turboKeys.rtx] ?: "1.2"}×",
                                if (turboKeys.frameGen.isNotEmpty() && frameGen != "1") {
                                    stringResource(R.string.h3_frame_gen_short, frameGen)
                                } else null
                            ).joinToString(" · ").ifBlank { null }
                        }
                        Text(
                            baseSummary,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (secondPassLine != null) Text(
                            secondPassLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (accelLine != null) Text(
                            accelLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val failure = queueState.lastFailure
                            ?.takeIf { it.ownerId == H3_OWNER_ID }
                        val queueLine = when {
                            failure != null -> context.getString(R.string.h3_status_failed, h3FailureGroup(failure.nodeType, context)) +
                                (failure.message.take(46).let { if (it.isBlank()) "" else "：$it" })
                            ownJobExecuting -> context.getString(R.string.h3_status_generating, queueState.totalQueueSize)
                            ownJobWaiting && queueState.ownQueuePosition != null ->
                                context.getString(R.string.h3_status_queued, queueState.ownQueuePosition)
                            queueState.totalQueueSize > 0 -> context.getString(R.string.h3_status_queue, queueState.totalQueueSize)
                            resultLoading -> context.getString(R.string.h3_status_fetching)
                            spec.warnings.isNotEmpty() -> context.getString(R.string.h3_status_warnings, spec.warnings.size)
                            else -> null
                        }
                        if (queueLine != null) Text(
                            queueLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failure != null || (spec.warnings.isNotEmpty() && !ownJobExecuting &&
                                queueState.totalQueueSize == 0)
                            ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // The poster row that used to live here (open / save / share / continue) is
                    // gone: everything it offered is in the gallery or on the tail-frame row, and
                    // its height is what the split button needs. The leading half only ever
                    // submits, so a second and third segment can be queued while one runs.
                    GenerationButton(
                        modifier = Modifier.width(180.dp),
                        canCancelOwnJob = ownJobExecuting,
                        isEnabled = !busy && client != null && !draft.mpOutOfRange &&
                            !draft.relayNameMissing,
                        isUploading = busy,
                        disabledReason = if (draft.relayNameMissing)
                            context.getString(R.string.h3_msg_project_required) else null,
                        onGenerate = { submit(front = false) },
                        onAddToFrontOfQueue = { submit(front = true) },
                        onCancelOwnJob = { abortOwnJob() },
                        onClearQueue = { clearOwnQueue() }
                    )
                }
            }
        }

        if (sourcePickerSlot != null) {
            val slot = sourcePickerSlot
            val title = when {
                slot == "first" -> context.getString(R.string.h3_pick_first_frame)
                slot == "last" -> context.getString(R.string.h3_pick_last_frame)
                slot?.startsWith("ref:") == true -> context.getString(R.string.h3_pick_ref)
                else -> context.getString(R.string.h3_pick_material)
            }
            val currentFile = when {
                slot == "first" -> draft.first
                slot == "last" -> draft.last
                slot?.startsWith("ref:") == true -> {
                    val index = slot.removePrefix("ref:").toIntOrNull() ?: 0
                    draft.refs.getOrNull(index)
                }
                else -> null
            }
            sh.hnet.comfychair.ui.components.H3SourcePickerSheet(
                title = title,
                currentSelectedFile = currentFile,
                onDismiss = { sourcePickerSlot = null },
                onPickFromAlbum = {
                    val s = sourcePickerSlot
                    sourcePickerSlot = null
                    pendingSlot = s
                    picker.launch("image/*")
                },
                onSelectFile = { filename, localPath, item ->
                    val s = sourcePickerSlot
                    sourcePickerSlot = null
                    fun applySlotName(name: String) {
                        update(when {
                            s == "first" -> draft.copy(first = name)
                            s == "last" -> draft.copy(last = name)
                            else -> {
                                val index = s?.removePrefix("ref:")?.toIntOrNull() ?: 0
                                val refs = draft.refs.toMutableList()
                                while (refs.size <= index) refs.add("")
                                refs[index] = name
                                draft.copy(refs = refs)
                            }
                        })
                        previewRevision++
                    }
                    when {
                        // 服务器素材：文件本来就在 input 里，按名字用，什么都不用传
                        item == null && localPath == null -> {
                            applySlotName(filename)
                            message = context.getString(R.string.h3_msg_loaded_server_file, filename)
                        }

                        // 提取帧：文件只在这台手机上，读出来传给服务器
                        localPath != null -> {
                            val target = ConnectionManager.clientOrNull
                            val bytes = runCatching { java.io.File(localPath).readBytes() }.getOrNull()
                            if (target == null || bytes == null || bytes.isEmpty()) {
                                message = context.getString(R.string.h3_msg_frame_unreadable)
                            } else {
                                busy = true
                                message = context.getString(R.string.h3_msg_uploading_frame)
                                target.uploadImage(
                                    bytes, "h3_${UUID.randomUUID()}.png",
                                    overwrite = false, returnFullPath = true
                                ) { ok, name, error, _ ->
                                    main.post {
                                        busy = false
                                        if (ok && name != null) {
                                            applySlotName(name)
                                            message = context.getString(R.string.h3_msg_loaded_frame, filename)
                                        } else message = context.getString(R.string.h3_msg_upload_failed, error ?: context.getString(R.string.h3_msg_no_filename))
                                    }
                                }
                            }
                        }

                        // 图库图片：原图在服务器的 output 里，工作流只认 input。
                        // 从图库缓存取字节再传成 input 素材，比回 output 下载更稳：手机上已有
                        // 缓存时不用再请求一次，服务器把 output 清了也照样能用。
                        else -> {
                            val target = ConnectionManager.clientOrNull
                            val picked = item
                            if (target == null || picked == null) {
                                message = context.getString(R.string.h3_msg_no_connection)
                            } else {
                                busy = true
                                message = context.getString(R.string.h3_msg_reading_gallery)
                                scope.launch {
                                    val key = picked.toCacheKey()
                                    val bmp = MediaCache.fetchImage(key, picked.subfolder, picked.type)
                                    val bytes = bmp?.let { b ->
                                        java.io.ByteArrayOutputStream().use { out ->
                                            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                            out.toByteArray()
                                        }
                                    }
                                    if (bytes == null || bytes.isEmpty()) {
                                        main.post {
                                            busy = false
                                            message = context.getString(R.string.h3_msg_gallery_gone)
                                        }
                                        return@launch
                                    }
                                    // 文件名取缓存键的哈希：同一张图反复选用，服务器上只留一份。
                                    val name = "h3_ref_${key.keyString.hashCode().toUInt().toString(16)}.png"
                                    target.uploadImage(
                                        bytes, name, subfolder = "h3_refs",
                                        overwrite = true, returnFullPath = true
                                    ) { ok, stored, error, _ ->
                                        main.post {
                                            busy = false
                                            if (ok && stored != null) {
                                                applySlotName(stored)
                                                message = context.getString(R.string.h3_msg_used_gallery, filename)
                                            } else message = context.getString(R.string.h3_msg_upload_failed, error ?: context.getString(R.string.h3_msg_no_filename))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

/** Keeps the most recent five submitted payloads for diagnosis. */
private fun archivePayload(context: android.content.Context, mode: H3Plan.Mode, payload: String) {
    runCatching {
        val dir = java.io.File(context.filesDir, "h3_payloads").apply { mkdirs() }
        java.io.File(dir, "${System.currentTimeMillis()}_${mode.name}.json").writeText(payload)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
    }
}

/** Everything the plan knows about a model file cell, written back through the same `extras` map. */
private fun modelPayload(plan: H3Plan.Spec, draft: H3Draft, context: Context): Map<String, Any> {
    val originals = LinkedHashMap<String, String>()
    plan.extras.forEach { originals[it.key] = it.value.toString() }
    listOfNotNull(plan.unet, plan.clip, plan.videoVae, plan.audioVae).forEach { originals[it.key] = it.value }
    val typed = plan.extras.associateBy { it.key }
    val out = LinkedHashMap<String, Any>()
    draft.extras.forEach { (key, text) ->
        val original = originals[key] ?: return@forEach
        if (text == original) return@forEach
        val value: Any = typed[key]?.let { extra ->
            typedValue(extra.value, text, seed = extra.input in H3Seed.INPUTS)
                ?: error(
                    context.getString(
                        R.string.h3_err_extra_invalid,
                        if (extra.labelRes != 0) context.getString(extra.labelRes) else extra.label
                    )
                )
        } ?: text
        out[key] = value
    }
    return out
}

private fun ComfyFrames(expression: String, seconds: Double): Int? =
    sh.hnet.comfychair.workflow.ComfyExpr.frames(expression, seconds)

/** Keep the JSON type of a scalar: integers stay integers, combos stay strings. */
private fun typedValue(original: Any, text: String, seed: Boolean = false): Any? {
    val raw = text.trim()
    if (raw.isEmpty()) return null
    return when (original) {
        is Boolean -> raw.toBooleanStrictOrNull()
        // A seed cell can start at 0 (an Int) or be handed a 64-bit roll, and the node accepts
        // either. Rejecting the wider form was what stopped submission with "需要合法数值".
        // Also: float parameters initialized as Int in template (e.g. value=1 in PrimitiveFloat) must accept decimals.
        is Int -> raw.toIntOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: raw.toLongOrNull()?.takeIf { seed }
        is Long -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        is Number -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        // The template may store the seed as text; keep the 64-bit value as a number either way.
        else -> if (seed) raw.toLongOrNull() ?: raw else raw
    }
}

// ---------------------------------------------------------------- small parts

private data class H3LoraDraft(val file: String, val strength: Double, val enabled: Boolean)

private data class H3Draft(
    val prompt: String = "",
    val ratio: String = "16:9",
    val mp: String = "0.5",
    val seconds: String = "6",
    val steps: String = "",
    val customW: String = "",
    val customH: String = "",
    val refs: List<String> = emptyList(),
    val first: String = "",
    val last: String = "",
    /** Standalone reference audio in slot order; the durations are display-only. */
    val audios: List<String> = emptyList(),
    val audioSeconds: List<String> = emptyList(),
    val unet: String = "",
    val loras: Map<String, H3LoraDraft> = emptyMap(),
    /** `nodeId:input` -> draft text: advanced scalars and model files share one map. */
    val extras: Map<String, String> = emptyMap(),
    val mpOutOfRange: Boolean = false,
    val multiple: String = "",
    val extraLoras: List<H3LoraAdd> = emptyList(),
    /** Slots kept but not submitted this time. */
    val disabledRefs: List<Int> = emptyList(),
    /** Last frame of the previous clip, uploaded and submitted as the final reference image. */
    val continuation: String = "",
    /** 连拍: after every finished run, refill the continuation frame without being asked. */
    val continuationAuto: Boolean = false,
    /**
     * What 连拍 does while other segments are still queued:
     * 0 = one at a time, 1 = queue freely but stop relaying, 2 = relay every finished segment.
     */
    val continuationMode: Int = 0,
    /** Name the files of a chain automatically (`h3_seq_01`); off means use [sequenceName]. */
    val sequenceAutoName: Boolean = true,
    /** Custom prefix for a chain; blank keeps the default. */
    val sequenceName: String = "",
    /** Write the continuation claim into the prompt automatically; off leaves the text alone. */
    val continuationInject: Boolean = true,
    /** Picture number of the declaration this app wrote, or -1 when the prompt is the user's. */
    val continuationOwner: Int = H3Continuation.NONE,
    /** True when the `[video continuation]` summary marker is ours and may be taken back. */
    val continuationFlag: Boolean = false
) {
    /**
     * Whether files carry a `_01` segment number. 连拍模式 only: a plain single run keeps the
     * template's own `h3_00001` name and stays outside any chain group, exactly as before. The
     * project name is a chain's identity, so it must not start renaming or grouping single runs.
     */
    val numbered: Boolean get() = continuationAuto

    /** 连拍模式 cannot start without a project name: it is how the server tells chains apart. */
    val relayNameMissing: Boolean get() = continuationAuto && H3Naming.sanitize(sequenceName).isBlank()
}

private data class H3LoraAdd(val name: String = "", val strength: String = "1.0")

/**
 * Turn the failing node's class into the card the user would go and change. The raw class name is
 * kept in the parenthetical so a report is still traceable.
 */
private fun h3FailureGroup(nodeType: String?, context: Context): String {
    val t = nodeType.orEmpty()
    val group = when {
        t.isBlank() -> context.getString(R.string.h3_failure_exec)
        t.contains("Lora", true) -> "LoRA"
        t.contains("VAE", true) -> "VAE"
        t.contains("UNET", true) || t.contains("Checkpoint", true) -> context.getString(R.string.h3_failure_model)
        t.contains("CLIP", true) || t.contains("TextEncode", true) -> context.getString(R.string.h3_failure_prompt)
        t.contains("LoadImage", true) || t.startsWith("VHS_", true) -> context.getString(R.string.h3_failure_material)
        t.contains("Sampler", true) || t.contains("Scheduler", true) ||
            t.contains("Guider", true) || t.contains("Noise", true) -> context.getString(R.string.h3_failure_sampling)
        t.contains("VideoCombine", true) -> context.getString(R.string.h3_failure_output)
        t.contains("MiniMaxH3", true) -> context.getString(R.string.h3_failure_h3)
        else -> t
    }
    return if (group == t) t else "$group（$t）"
}

/** The numbers that share one dialog. */
private enum class H3NumberParam { SECONDS, MEGAPIXELS, STEPS, SECOND_PASS_MP, SECOND_PASS_STEPS, RTX_SCALE }

/** Resolve semantic Turbo control keys; incomplete/ambiguous custom graphs stay in Advanced. */
private data class H3TurboKeys(
    val skip: String, val mp: String, val steps: String, val denoise: String,
    val rtx: String, val frameGen: String
) {
    val complete get() = listOf(skip, mp, steps, rtx).all { it.isNotEmpty() }
    companion object {
        fun from(spec: H3Plan.Spec): H3TurboKeys {
            fun key(label: Int) = spec.extras.filter { it.labelRes == label }.singleOrNull()?.key.orEmpty()
            return H3TurboKeys(key(R.string.h3_field_second_skip), key(R.string.h3_field_second_mp),
                key(R.string.h3_field_second_steps), key(R.string.h3_field_second_denoise),
                key(R.string.h3_field_rtx_video_scale), key(R.string.h3_field_frame_gen))
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun H3TurboPipelineBody(
    spec: H3Plan.Spec,
    draft: H3Draft,
    busy: Boolean,
    onEditNumber: (H3NumberParam, String) -> Unit,
    onUpdate: (H3Draft) -> Unit
) {
    val turboKeys = remember(spec) { H3TurboKeys.from(spec) }
    if (!turboKeys.complete) return
    // True = skip second pass, resolved from the uniquely labelled workflow field.
    // UI 正向语义：二采开关开 = false (不跳过，执行二采)；关 = true (跳过二采)
    val secondPassEnabled = draft.extras[turboKeys.skip]?.toBooleanStrictOrNull() != true
    val secondPassMp = draft.extras[turboKeys.mp] ?: "1"
    val secondPassSteps = draft.extras[turboKeys.steps] ?: "3"
    val rtxScale = draft.extras[turboKeys.rtx] ?: "1.2"
    val hasFrameGen = turboKeys.frameGen.isNotEmpty()
    val frameGen = draft.extras[turboKeys.frameGen] ?: "1"

    // 外层卡片和折叠壳由调用处持有，这里只出内容。
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 1. 二采开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.h3_second_pass_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = secondPassEnabled,
                enabled = !busy,
                onCheckedChange = { checked ->
                    onUpdate(draft.copy(extras = draft.extras + (turboKeys.skip to (!checked).toString())))
                }
            )
        }

        // 二采子参数（仅在二采开启时展示）
        if (secondPassEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.h3_second_pass_mp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                H3PanelChips(
                    // The second pass is a refinement at or below the first pass size, not a
                    // second upscale; 1.0 and up lived here and only ever produced bigger files.
                    presets = listOf("0.5", "0.6", "0.7", "0.8"),
                    value = secondPassMp,
                    enabled = !busy,
                    onPreset = { picked ->
                        onUpdate(draft.copy(extras = draft.extras + (turboKeys.mp to picked)))
                    },
                    onCustom = { onEditNumber(H3NumberParam.SECOND_PASS_MP, secondPassMp) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.h3_second_pass_steps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                H3PanelChips(
                    presets = listOf("3", "4", "5"),
                    value = secondPassSteps,
                    enabled = !busy,
                    onPreset = { picked ->
                        onUpdate(draft.copy(extras = draft.extras + (turboKeys.steps to picked)))
                    },
                    onCustom = { onEditNumber(H3NumberParam.SECOND_PASS_STEPS, secondPassSteps) }
                )
            }
        }

        // 2. RTX 超分倍率（五等分）
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.h3_rtx_scale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val rtxPresets = listOf("1.0", "1.2", "1.5", "2.0")
            val rtxCustomActive = rtxScale.isNotBlank() && rtxScale !in rtxPresets
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rtxPresets.forEach { preset ->
                    H3ParamChip(preset, preset == rtxScale, !busy, Modifier.weight(1f)) {
                        onUpdate(draft.copy(extras = draft.extras + (turboKeys.rtx to preset)))
                    }
                }
                H3ParamChip(
                    if (rtxCustomActive) rtxScale else stringResource(R.string.h3_custom),
                    rtxCustomActive, !busy, Modifier.weight(1f)
                ) { onEditNumber(H3NumberParam.RTX_SCALE, rtxScale) }
            }
        }

        // 3. 插帧增强 (#173, 若存在：完整保留 1x 关、2x、3x 三档)
        if (hasFrameGen) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.h3_interp_toggle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val interpOptions = listOf(
                        "1" to stringResource(R.string.h3_interp_option_off),
                        "2" to stringResource(R.string.h3_interp_option_2x),
                        "3" to stringResource(R.string.h3_interp_option_3x)
                    )
                    interpOptions.forEach { (optVal, optLabel) ->
                        H3ParamChip(
                            text = optLabel,
                            selected = frameGen == optVal,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            onUpdate(draft.copy(extras = draft.extras + (turboKeys.frameGen to optVal)))
                        }
                    }
                }
            }
        }

        Text(
            stringResource(R.string.h3_second_pass_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One picked audio clip, read once off the main thread before it is uploaded. */
private data class H3PickedAudio(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val seconds: Double?
)

/** The picker's own display name; only its extension is used, to keep the server suffix right. */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

/** Container duration in seconds, or null when the file cannot be probed. Display only. */
private fun audioDuration(context: android.content.Context, uri: android.net.Uri): Double? =
    runCatching {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()?.div(1000.0)
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrNull()

/** The knobs a preset carries: everything that is not a workflow-bound model file. */
private data class H3Snapshot(
    val ratio: String,
    val mp: String,
    val seconds: String,
    val steps: String,
    val multiple: String,
    val extraLoras: List<H3LoraAdd>
) {
    fun applyTo(draft: H3Draft): H3Draft = draft.copy(
        ratio = ratio, mp = mp, seconds = seconds, steps = steps,
        multiple = multiple, extraLoras = extraLoras,
        customW = "", customH = "", mpOutOfRange = false
    )

    fun toJson(): JSONObject = JSONObject()
        .put("ratio", ratio).put("mp", mp).put("seconds", seconds)
        .put("steps", steps).put("multiple", multiple)
        .put("extraLoras", JSONArray().also { arr ->
            extraLoras.forEach { arr.put(JSONObject().put("name", it.name).put("strength", it.strength)) }
        })

    companion object {
        fun of(draft: H3Draft) = H3Snapshot(
            draft.ratio, draft.mp, draft.seconds, draft.steps, draft.multiple, draft.extraLoras)

        fun from(json: JSONObject?) = json?.let {
            H3Snapshot(
                it.optString("ratio", "16:9"), it.optString("mp", "0.5"),
                it.optString("seconds", "6"), it.optString("steps", ""),
                it.optString("multiple", ""),
                it.optJSONArray("extraLoras")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { e -> H3LoraAdd(e.optString("name"), e.optString("strength", "1.0")) }
                    }
                }.orEmpty()
            )
        }
    }
}

/** Named parameter presets, shared by every H3 template. */
private class H3PresetStore(context: android.content.Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("h3_presets", android.content.Context.MODE_PRIVATE)

    fun list(): List<Pair<String, H3Snapshot>> {
        val raw = prefs.getString("presets", null) ?: return emptyList()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        return obj.keys().asSequence()
            .mapNotNull { key -> H3Snapshot.from(obj.optJSONObject(key))?.let { key to it } }
            .toList()
    }

    fun save(name: String, draft: H3Draft) {
        val raw = prefs.getString("presets", null)
        val obj = runCatching { raw?.let { JSONObject(it) } }.getOrNull() ?: JSONObject()
        obj.put(name, H3Snapshot.of(draft).toJson())
        prefs.edit().putString("presets", obj.toString()).apply()
    }

    fun delete(name: String) {
        val raw = prefs.getString("presets", null) ?: return
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        obj.remove(name)
        prefs.edit().putString("presets", obj.toString()).apply()
    }
}

/** Per workflow + mode draft, so a mode switch restores exactly what the user had. */
private class H3DraftStore(context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences("h3_studio_draft", android.content.Context.MODE_PRIVATE)

    fun load(workflowId: String, area: H3Plan.Area, spec: H3Plan.Spec): H3Draft {
        val defaults = H3Draft(
            prompt = spec.prompt?.value.orEmpty(),
            ratio = spec.resolution?.aspectRatio?.substringBefore('(')?.trim() ?: "16:9",
            mp = (spec.resolution?.megapixels ?: 0.5).toString(),
            seconds = spec.duration?.seconds?.let { fmt(it) } ?: "6",
            steps = spec.steps?.steps?.toString() ?: ""
        )
        val empty = defaults.copy(
            refs = List(spec.refIndices.size) { "" }
        )
        val raw = prefs.getString("$workflowId|${area.name}", null) ?: return empty
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return defaults
        val refs = mutableListOf<String>()
        o.optJSONArray("refs")?.let { arr -> for (i in 0 until arr.length()) refs.add(arr.optString(i)) }
        if (area == H3Plan.Area.R2V && refs.isEmpty()) {
            repeat(spec.refIndices.size) { refs.add("") }
        }
        val loras = mutableMapOf<String, H3LoraDraft>()
        o.optJSONObject("loras")?.let { obj ->
            obj.keys().forEach { key ->
                val e = obj.optJSONObject(key) ?: return@forEach
                loras[key] = H3LoraDraft(e.optString("file"), e.optDouble("strength", 1.0), e.optBoolean("on", true))
            }
        }
        val extras = mutableMapOf<String, String>()
        o.optJSONObject("extras")?.let { obj -> obj.keys().forEach { extras[it] = obj.optString(it) } }
        return H3Draft(
            prompt = o.optString("prompt", defaults.prompt),
            ratio = o.optString("ratio", defaults.ratio),
            mp = o.optString("mp", defaults.mp),
            seconds = o.optString("seconds", defaults.seconds),
            steps = o.optString("steps", defaults.steps),
            customW = o.optString("customW", ""),
            customH = o.optString("customH", ""),
            refs = refs,
            first = o.optString("first", ""),
            last = o.optString("last", ""),
            audios = o.optJSONArray("audios")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }.orEmpty(),
            audioSeconds = o.optJSONArray("audioSeconds")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }.orEmpty(),
            unet = o.optString("unet", ""),
            loras = loras,
            extras = extras,
            multiple = o.optString("multiple", ""),
            extraLoras = o.optJSONArray("extraLoras")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { e -> H3LoraAdd(e.optString("name"), e.optString("strength", "1.0")) }
                }
            }.orEmpty(),
            disabledRefs = o.optJSONArray("disabledRefs")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }
            }.orEmpty(),
            continuation = o.optString("continuation", ""),
            continuationAuto = o.optBoolean("continuationAuto", false),
            continuationMode = o.optInt("continuationMode", 0),
            sequenceAutoName = o.optBoolean("sequenceAutoName", true),
            sequenceName = o.optString("sequenceName", ""),
            continuationInject = o.optBoolean("continuationInject", true),
            continuationOwner = o.optInt("continuationOwner", H3Continuation.NONE),
            continuationFlag = o.optBoolean("continuationFlag", false)
        )
    }

    private fun encode(draft: H3Draft): String {
        val o = JSONObject()
            .put("prompt", draft.prompt).put("ratio", draft.ratio).put("mp", draft.mp)
            .put("seconds", draft.seconds).put("steps", draft.steps)
            .put("customW", draft.customW).put("customH", draft.customH)
            .put("first", draft.first).put("last", draft.last).put("unet", draft.unet)
            .put("continuation", draft.continuation).put("continuationAuto", draft.continuationAuto)
            .put("continuationMode", draft.continuationMode)
            .put("sequenceAutoName", draft.sequenceAutoName)
            .put("sequenceName", draft.sequenceName)
            .put("continuationInject", draft.continuationInject)
            .put("continuationOwner", draft.continuationOwner)
            .put("continuationFlag", draft.continuationFlag)
        o.put("audios", JSONArray().also { arr -> draft.audios.forEach { arr.put(it) } })
        o.put("audioSeconds", JSONArray().also { arr -> draft.audioSeconds.forEach { arr.put(it) } })
        o.put("refs", JSONArray().also { arr -> draft.refs.forEach { arr.put(it) } })
        val loras = JSONObject()
        draft.loras.forEach { (key, value) ->
            loras.put(key, JSONObject().put("file", value.file)
                .put("strength", value.strength).put("on", value.enabled))
        }
        o.put("loras", loras)
        val extras = JSONObject()
        draft.extras.forEach { (key, value) -> extras.put(key, value) }
        o.put("extras", extras)
        o.put("multiple", draft.multiple)
        o.put("extraLoras", JSONArray().also { arr ->
            draft.extraLoras.forEach { arr.put(JSONObject().put("name", it.name).put("strength", it.strength)) }
        })
        o.put("disabledRefs", JSONArray().also { arr -> draft.disabledRefs.forEach { arr.put(it) } })
        return o.toString()
    }

    fun save(workflowId: String, area: H3Plan.Area, draft: H3Draft) {
        prefs.edit().putString("$workflowId|${area.name}", encode(draft)).apply()
    }

    /** Synchronous durable save for continuation checkpoints; call from Dispatchers.IO. */
    fun saveDurable(workflowId: String, area: H3Plan.Area, draft: H3Draft): Boolean =
        prefs.edit().putString("$workflowId|${area.name}", encode(draft)).commit()

    private fun fmt(v: Double): String =
        if (v == Math.floor(v)) v.toInt().toString() else v.toString()
}

@Composable
private fun H3StickyTitle(title: State<String?>) {
    val current = title.value
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 2.dp)
        ) {
            Text(
                text = current ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun H3Card(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        // 单层分离：只用 1.5dp 轻阴影，不再同时压一条灰描边——两者叠在一起边缘会显脏。
        elevation = CardDefaults.cardElevation(
            defaultElevation = LocalAppElevations.current.card,
            pressedElevation = LocalAppElevations.current.card,
            focusedElevation = LocalAppElevations.current.card,
            hoveredElevation = LocalAppElevations.current.card,
            draggedElevation = LocalAppElevations.current.card
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun H3Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp)
        .background(MaterialTheme.colorScheme.outlineVariant))
}

/**
 * 参数面板：四项基础参数（有种子时再加一项）收进底部弹层。
 *
 * 主屏只留四宫格，想看细节才进来；但每一项的预设仍是一格点到位，
 * 不因为进了弹层就退化成「先开数值弹窗再输入」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun H3ParamPanel(
    draft: H3Draft,
    ratioPairs: List<Pair<String, Pair<Int, Int>>>,
    extraRatioPairs: List<Pair<String, Pair<Int, Int>>>,
    ratioEnabled: Boolean,
    busy: Boolean,
    seedPresent: Boolean,
    seedAuto: Boolean,
    seedKey: String?,
    seedValue: String?,
    onUpdate: (H3Draft) -> Unit,
    onPickRatio: (String) -> Unit,
    onSeedAuto: (Boolean) -> Unit,
    onSeedRoll: () -> Unit,
    onCustom: (H3NumberParam, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isPicked = { value: String ->
        draft.customW.isBlank() && draft.customH.isBlank() && draft.ratio == value
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶栏：标题 + 右上角关闭图标按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.h3_panel_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.content_description_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 1. 视频时长
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.h3_panel_duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                H3PanelChips(
                    presets = listOf("6", "10", "15"),
                    value = draft.seconds,
                    enabled = !busy,
                    onPreset = { onUpdate(draft.copy(seconds = it)) },
                    onCustom = { onCustom(H3NumberParam.SECONDS, draft.seconds) }
                )
            }

            // 2. 画面比例
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.h3_aspect_ratio),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ratioPairs.isEmpty()) {
                    Text(
                        stringResource(R.string.h3_no_ratio_editor),
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    H3ChipGrid(
                        items = ratioPairs.map { H3Resolution.label(context, it.first) to it.first },
                        selected = isPicked,
                        enabled = ratioEnabled
                    ) { onPickRatio(it) }
                    if (extraRatioPairs.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        H3ChipGrid(
                            items = extraRatioPairs.map { H3Resolution.label(context, it.first) to it.first },
                            selected = isPicked,
                            enabled = ratioEnabled
                        ) { onPickRatio(it) }
                    }
                }
            }

            // 3. 像素量
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.h3_megapixels),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                H3PanelChips(
                    presets = H3Resolution.megapixelPresets.map { it.toString() },
                    value = draft.mp,
                    enabled = ratioEnabled,
                    onPreset = { picked ->
                        onUpdate(draft.copy(mp = picked, customW = "", customH = "", mpOutOfRange = false))
                    },
                    onCustom = { onCustom(H3NumberParam.MEGAPIXELS, draft.mp) }
                )
            }

            // 4. 采样步数
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.h3_sampling_steps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                H3PanelChips(
                    presets = listOf("4", "8", "20"),
                    value = draft.steps,
                    enabled = !busy,
                    onPreset = { onUpdate(draft.copy(steps = it)) },
                    onCustom = { onCustom(H3NumberParam.STEPS, draft.steps) }
                )
            }

            // 5. 随机种子（若存在）
            if (seedPresent) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.h3_panel_seed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    H3SeedField(
                        value = seedValue.orEmpty(),
                        auto = seedAuto,
                        enabled = !busy,
                        onAutoChange = onSeedAuto,
                        onRoll = onSeedRoll,
                        onValueChange = { text ->
                            if (seedKey != null) {
                                onUpdate(draft.copy(extras = draft.extras + (seedKey to text)))
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 面板里的一行：左边名称、右边当前值，点一下展开它自己的设置。 */
@Composable
private fun H3PanelRow(
    label: String,
    value: String,
    expanded: Boolean,
    divider: Boolean = true,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (divider) Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 四宫格里的一格：标签在上、当前值在下，主屏上只读，点开进面板。 */
@Composable
private fun H3PanelTile(
    label: String,
    value: String,
    icon: String = "",
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon.isNotBlank()) {
                Text(
                    icon,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(3.dp))
        // 关键参数值按 20sp 拉一级：四宫格里最先被看的就是这四个数。
        Text(
            value,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 三个预设 + 一格自定义，四格等宽。 */
@Composable
private fun H3PanelChips(
    presets: List<String>,
    value: String,
    enabled: Boolean,
    onPreset: (String) -> Unit,
    onCustom: () -> Unit
) {
    val customActive = value.isNotBlank() && value !in presets
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { preset ->
            H3ParamChip(preset, preset == value, enabled, Modifier.weight(1f)) { onPreset(preset) }
        }
        H3ParamChip(
            if (customActive) value else stringResource(R.string.h3_custom),
            customActive, enabled, Modifier.weight(1f), onCustom
        )
    }
}

/**
 * 卡片内部的一层分组：比外卡片更浅的底、一行小标题、右侧可带说明或一个动作。
 *
 * 字段多的时候靠它分层，而不是把每个字段都做成一张卡——后者会让一屏放不下三项。
 */
@Composable
private fun H3Group(
    title: String,
    hint: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            // 卡内一层：比白卡略深一档即可，不再叠描边；否则「卡 + 组 + 边」三层边线挤在一起。
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (hint != null) Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            action?.invoke()
        }
        content()
    }
}

/**
 * 双列用的紧凑字段：标签在上、值在下。
 *
 * 不用 OutlinedTextField 是因为它自带 56dp 的最小高度，两列排下来反而比一行一个更高；
 * 这里用 BasicTextField 自己画边框，40dp 够手指点，六项能从六行压到三行。
 */
/** A cell whose declared type is a number; anything else stays a free text box. */
private fun isNumericExtra(extra: H3Plan.Extra): Boolean =
    extra.value is Number || (extra.value as? String)?.toDoubleOrNull() != null

/**
 * Renders one kind of parameter at a time, two per row.
 *
 * Grouping by control rather than by position is the point: a run of dropdowns, then switches,
 * then numbers, reads as three lists the eye can scan, while the file order mixes them and makes
 * every row a fresh guess. Inside a kind the workflow's own order is kept, so a workflow always
 * puts the same thing in the same place. An odd one out keeps the left cell and the right cell is
 * left empty instead of being padded with something unrelated.
 */
@Composable
private fun H3AdvancedGrid(
    items: List<H3Plan.Extra>,
    options: Map<String, List<String>>,
    draft: H3Draft,
    enabled: Boolean,
    onDraft: (H3Draft) -> Unit
) {
    items.chunked(2).forEach { pair ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            pair.forEach { extra ->
                H3AdvancedCell(
                    extra = extra,
                    options = options[extra.key].orEmpty(),
                    draft = draft,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onDraft = onDraft
                )
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun H3AdvancedCell(
    extra: H3Plan.Extra,
    options: List<String>,
    draft: H3Draft,
    enabled: Boolean,
    modifier: Modifier,
    onDraft: (H3Draft) -> Unit
) {
    val text = draft.extras[extra.key] ?: extra.value.toString()
    val label = if (extra.labelRes != 0) stringResource(extra.labelRes) else extra.label
    val write: (String) -> Unit = { onDraft(draft.copy(extras = draft.extras + (extra.key to it))) }
    when {
        // The server already narrowed this cell to a fixed list, so a picker can never send a
        // value it would reject.
        options.isNotEmpty() -> Column(modifier) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            H3ModelChoiceBox(
                label = label,
                selectedValue = text,
                options = options,
                onValueChange = write,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
        extra.isSwitch -> Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Switch(
                checked = text.equals("true", ignoreCase = true),
                onCheckedChange = { write(it.toString()) },
                enabled = enabled
            )
        }
        else -> H3AdvancedField(
            label = label,
            value = text,
            enabled = enabled,
            modifier = modifier,
            // A number cell keeps only characters a number can hold, so a stray letter never
            // makes it into the payload and comes back as a server-side type error.
            onChange = { raw ->
                write(if (isNumericExtra(extra)) raw.filter { it.isDigit() || it == '.' || it == '-' } else raw)
            }
        )
    }
}

@Composable
private fun H3AdvancedField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 11.dp)
        )
    }
}

/** 紧凑单行输入框（42dp 高、10dp 圆角、浅底配细边框），消灭默认 56dp 的笨重感。 */
@Composable
private fun H3CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                innerTextField()
            }
        },
        modifier = modifier
    )
}

/**
 * One parameter block: the current value on the label row, then three presets plus a custom cell.
 *
 * The custom cell doubles as the readout: it says「自定义」while a preset is in force, and shows
 * the number itself — lit — once the value is off-preset. So "am I on a preset?" is answered by
 * which cell is highlighted, not by a number the user has to compare by eye.
 */
@Composable
private fun H3ParamBlock(
    label: String,
    detail: String,
    hint: String,
    presets: List<String>,
    value: String,
    enabled: Boolean,
    onPreset: (String) -> Unit,
    onCustom: () -> Unit
) {
    val customActive = value.isNotBlank() && value !in presets
    Column(Modifier.fillMaxWidth()) {
        // Label and reading share one line, the explanation gets its own. They used to share a
        // single line with `softWrap = false` on both readings, so an English hint long enough to
        // fill the width squeezed the label down to a few characters and stacked it letter by
        // letter. Now the label ellipsises instead of collapsing, and the hint can never push it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (detail.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        if (hint.isNotEmpty()) Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        // Four equal cells: three presets plus the custom slot, so the row can never overflow.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { preset ->
                H3ParamChip(
                    text = preset,
                    selected = preset == value,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { onPreset(preset) }
            }
            H3ParamChip(
                text = if (customActive) value else stringResource(R.string.h3_custom),
                selected = customActive,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onCustom
            )
        }
    }
}

@Composable
private fun H3ParamChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(text, maxLines = 1, softWrap = false)
            }
        }
    )
}

/**
 * One dialog for all three numbers. 确定 is the only write, so 取消 is a true discard rather than
 * a reverse patch. The unit rides inside the field so the number itself stays centred.
 */
@Composable
private fun H3NumberDialog(
    title: String,
    unit: String,
    hint: String,
    value: String,
    valid: Boolean,
    wholeNumbers: Boolean = false,
    onChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { raw ->
                        onChange(raw.filter { c -> c.isDigit() || (!wholeNumbers && c == '.') })
                    },
                    singleLine = true,
                    isError = !valid,
                    trailingIcon = { Text(unit, style = MaterialTheme.typography.bodyMedium) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (valid) hint else stringResource(R.string.h3_out_of_range),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = onConfirm) { Text(stringResource(R.string.h3_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.h3_cancel)) } }
    )
}

/** One model file cell: shows the current file and lets the user pick from the server list. */
@Composable
private fun H3ModelChoiceBox(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val headline = remember(selectedValue) {
        selectedValue.substringAfterLast('\\').substringAfterLast('/')
    }
    val density = LocalDensity.current
    var rowWidth by remember { mutableStateOf<Dp?>(null) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrowRotation"
    )

    Box(
        modifier = modifier
            .onSizeChanged { rowWidth = with(density) { it.width.toDp() } }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (headline.isNotBlank()) headline else label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (headline.isNotBlank()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(arrowRotation)
            )
        }
        ChoiceMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = options,
            selectedValue = selectedValue,
            onSelect = {
                expanded = false
                onValueChange(it)
            },
            width = rowWidth
        )
    }
}

@Composable
private fun H3ModelDropdown(
    label: String,
    ref: H3Plan.ModelRef?,
    draft: H3Draft,
    options: List<String>,
    enabled: Boolean,
    onDraft: (H3Draft) -> Unit
) {
    if (ref == null) {
        Text(stringResource(R.string.h3_no_loader_for, label), style = MaterialTheme.typography.labelSmall)
        return
    }
    val current = draft.extras[ref.key] ?: ref.value
    H3ModelChoiceBox(
        label = label,
        selectedValue = current,
        options = options,
        onValueChange = { chosen ->
            onDraft(draft.copy(extras = draft.extras + (ref.key to chosen)))
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
    // Known options that do not contain the current file: flag it before generation, not after.
    val normCurrent = current.replace('\\', '/')
    if (options.isNotEmpty() && options.none { it.replace('\\', '/') == normCurrent }) {
        Text(stringResource(R.string.h3_file_missing), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    } else if (options.isEmpty()) {
        Text(stringResource(R.string.h3_no_file_list),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun H3PickerButton(
    label: String,
    options: List<String>,
    enabled: Boolean,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    TextButton(enabled = enabled && options.isNotEmpty(), onClick = { open = true }) {
        Text(if (options.isEmpty()) stringResource(R.string.h3_no_list) else label)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options) { option ->
                        TextButton(onClick = { onPick(option); open = false }) {
                            Text(option.substringAfterLast('\\').substringAfterLast('/'))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.h3_close)) } }
        )
    }
}

@Composable
private fun H3PickerField(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(value.substringAfterLast('\\').substringAfterLast('/').ifBlank { stringResource(R.string.h3_not_selected) },
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        H3PickerButton(if (options.isEmpty()) stringResource(R.string.h3_no_list) else stringResource(R.string.h3_select), options, enabled, onChange)
    }
}

@Composable
private fun H3SlotRow(
    title: String,
    file: String,
    workflowId: String,
    revision: Int,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: (() -> Unit)?,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    badge: String? = null,
    disabled: Boolean = false,
    onToggle: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badge != null) {
                Surface(
                    color = if (disabled) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(badge, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (disabled) stringResource(R.string.h3_skip_this, title) else title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(enabled = enabled, onClick = onPick) { Text(if (file.isBlank()) stringResource(R.string.h3_upload) else stringResource(R.string.h3_replace)) }
            if (onToggle != null) TextButton(enabled = enabled, onClick = onToggle) {
                Text(if (disabled) stringResource(R.string.h3_enable) else stringResource(R.string.h3_disable))
            }
            if (onRemove != null) TextButton(enabled = enabled, onClick = onRemove) { Text(stringResource(R.string.h3_delete)) }
            if (onUp != null) TextButton(enabled = enabled, onClick = onUp) { Text(stringResource(R.string.h3_move_up)) }
            if (onDown != null) TextButton(enabled = enabled, onClick = onDown) { Text(stringResource(R.string.h3_move_down)) }
        }
        if (file.isBlank()) {
            Box(
                Modifier.fillMaxWidth().height(84.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.h3_no_image), style = MaterialTheme.typography.labelSmall) }
        } else Box(Modifier.fillMaxWidth().alpha(if (disabled) 0.4f else 1f)) {
            H3LocalOrRemotePreview(workflowId, file, revision)
        }
    }
}

@Composable
private fun H3LocalOrRemotePreview(
    workflowId: String,
    filename: String,
    revision: Int,
    modifier: Modifier = Modifier.fillMaxWidth().height(120.dp),
    scale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Fit
) {
    val context = LocalContext.current
    val client = ConnectionManager.clientOrNull
    var bitmap by remember(filename, revision) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(filename, revision) { mutableStateOf(false) }
    DisposableEffect(filename, revision, client) {
        var active = true
        val main = Handler(Looper.getMainLooper())
        val cached = java.io.File(context.filesDir,
            "h3_previews/$workflowId/${UUID.nameUUIDFromBytes(filename.toByteArray())}.png")
        if (cached.exists()) bitmap = BitmapFactory.decodeFile(cached.path)
        else if (client != null) {
            val normalized = filename.replace('\\', '/')
            client.fetchImage(filename = normalized.substringAfterLast('/'),
                subfolder = normalized.substringBeforeLast('/', ""), type = "input") { image, _ ->
                main.post { if (active) { bitmap = image; failed = image == null } }
            }
        }
        onDispose { active = false }
    }
    val bmp = bitmap
    if (bmp != null) Image(bmp.asImageBitmap(), filename, modifier, contentScale = scale)
    else Text(if (failed) stringResource(R.string.h3_preview_failed) else stringResource(R.string.h3_preview_loading),
        style = MaterialTheme.typography.labelSmall)
}

// ---------------------------------------------------------------- small parts

/**
 * Equal-width chip grid. A single Row squeezed six ratios into ~49dp each, which wrapped "21:9"
 * across two lines; three per row keeps every label on one line.
 */
@Composable
private fun H3ChipGrid(
    items: List<Pair<String, String>>,
    selected: (String) -> Boolean,
    enabled: Boolean,
    columns: Int = 3,
    onClick: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, value) ->
                    FilterChip(
                        selected = selected(value),
                        onClick = { onClick(value) },
                        label = {
                            Text(
                                label,
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------- material tiles

/**
 * 首帧 / 尾帧 slot: a tappable preview with its own clear button.
 * Both are optional — an empty pair means text-to-video.
 */
@Composable
private fun H3FrameSlot(
    label: String,
    file: String,
    workflowId: String,
    revision: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (file.isNotBlank()) IconButton(
                enabled = enabled,
                onClick = onRemove,
                // 40dp target: the bare "×" was a ~24dp hit area, easy to miss on a phone.
                modifier = Modifier.size(40.dp)
            ) { Text("×", style = MaterialTheme.typography.titleMedium) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled) { if (file.isBlank()) onPick() },
            contentAlignment = Alignment.Center
        ) {
            if (file.isBlank()) {
                // 空态给一个看得懂的落点：图形 + 文字，而不是一个灰块加「+ 选择」。
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.h3_choose),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else H3LocalOrRemotePreview(workflowId, file, revision, Modifier.fillMaxSize(), ContentScale.Crop)
        }
    }
}

/** One square reference tile: badge from the real submitted order, selection ring, disabled veil. */
@Composable
private fun H3RefTile(
    modifier: Modifier = Modifier,
    file: String,
    workflowId: String,
    revision: Int,
    badge: Int?,
    disabled: Boolean,
    selected: Boolean,
    enabled: Boolean,
    /** Marks the continuation frame: same slot every segment, labelled so it cannot be mistaken. */
    tail: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                if (selected || tail) 2.dp else 1.dp,
                if (selected || tail) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        if (file.isNotBlank()) {
            H3LocalOrRemotePreview(workflowId, file, revision, Modifier.fillMaxSize(), ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (disabled) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.h3_not_submitted), style = MaterialTheme.typography.labelSmall) }
        }
        if (badge != null && !disabled) Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                "P$badge", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        if (tail) Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(7.dp)
        ) {
            Text(
                stringResource(R.string.h3_last_frame_short), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun H3RefAddTile(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    // Three states, three labels. "上限" used to double as "busy", which told the user they had
    // run out of slots while six were still free.
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (enabled) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 与首尾帧空态同一套落点：图形在上、文字在下，空位不再只是一个「+」。
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                busy -> Text(
                    "…",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                enabled -> Icon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp)
                )
                else -> Text(
                    stringResource(R.string.h3_cap),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (enabled) {
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.h3_choose),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The standalone audio sockets, collapsed to one line by default.
 *
 * Audio is used on far fewer runs than images, so an always-open control would cost every run
 * vertical space for a feature most runs do not touch. Collapsed it is a title and a count; the
 * list only appears behind a tap.
 */
@Composable
private fun H3AudioSection(
    files: List<String>,
    seconds: List<String>,
    max: Int,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onPick: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.h3_audio_ref), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (files.isEmpty()) stringResource(R.string.h3_not_selected_short) else stringResource(R.string.h3_audio_count, files.count { it.isNotBlank() }, max),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1, softWrap = false,
                color = if (files.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(if (expanded) "  \u25be" else "  \u25b8", style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) {
            files.forEachIndexed { index, file ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("A${index + 1}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(file, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val shown = seconds.getOrNull(index).orEmpty()
                    if (shown.isNotEmpty()) Text(
                        " · $shown", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onPick(index) }, enabled = enabled) {
                        Text(stringResource(R.string.h3_replace), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onRemove(index) }, enabled = enabled) {
                        Text(stringResource(R.string.h3_remove), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (files.size < max) {
                OutlinedButton(
                    onClick = { onPick(files.size) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                ) {
                    Text(stringResource(R.string.h3_add_audio_n, files.size + 1), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Nothing uploaded yet: one wide target beats a row of three empty squares. */
@Composable
private fun H3RefEmptyTile(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The plus is the affordance: a bare text label did not read as "you can add here".
            Text(
                "+",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (enabled) stringResource(R.string.h3_add_first_ref) else stringResource(R.string.h3_msg_connect_first),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * Reorder lives here rather than in a drag gesture: a grid has no built-in reorder in Compose,
 * and two taps beat a long-press drag for a list this short.
 */
@Composable
private fun H3RefActionBar(
    enabled: Boolean,
    disabled: Boolean,
    canLeft: Boolean,
    canRight: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onReplace: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(enabled = enabled && canLeft, onClick = onLeft,
                modifier = Modifier.weight(1f)) { Text(stringResource(R.string.h3_move_forward), style = MaterialTheme.typography.labelMedium) }
            TextButton(enabled = enabled && canRight, onClick = onRight,
                modifier = Modifier.weight(1f)) { Text(stringResource(R.string.h3_move_back), style = MaterialTheme.typography.labelMedium) }
            TextButton(enabled = enabled, onClick = onReplace,
                modifier = Modifier.weight(1f)) { Text(stringResource(R.string.h3_replace), style = MaterialTheme.typography.labelMedium) }
            TextButton(enabled = enabled, onClick = onToggle,
                modifier = Modifier.weight(1f)) {
                Text(if (disabled) stringResource(R.string.h3_enable) else stringResource(R.string.h3_not_use), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(enabled = enabled, onClick = onDelete,
                modifier = Modifier.weight(1f)) { Text(stringResource(R.string.h3_delete), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/**
 * `MiniMaxH3ImageToVideo` stretches the first frame onto the canvas (`_resize(..., "disabled")`)
 * and cover-crops the last one (`"center"`), so only the first frame can arrive distorted.
 * Only cached in-app uploads can be measured here; a template's own file is left alone.
 */
private fun frameAspectWarning(
    context: android.content.Context,
    workflowId: String,
    file: String,
    ratio: String
): String? {
    if (file.isBlank()) return null
    val cached = java.io.File(
        context.filesDir,
        "h3_previews/$workflowId/${java.util.UUID.nameUUIDFromBytes(file.toByteArray())}.png"
    )
    if (!cached.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(cached.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val imageAspect = bounds.outWidth.toDouble() / bounds.outHeight
    val rw = ratio.substringBefore(':').toDoubleOrNull() ?: return null
    val rh = ratio.substringAfter(':').toDoubleOrNull() ?: return null
    val outAspect = rw / rh
    if (Math.abs(imageAspect - outAspect) / outAspect < 0.02) return null
    return context.getString(R.string.h3_err_aspect_mismatch, bounds.outWidth, bounds.outHeight, ratio)
}

/** 错误提示的关键词，中英各一份：message 里出现任一即按错误色显示。 */
private val MESSAGE_ERROR_WORDS = listOf(
    "失败", "未通过", "无法", "不在", "请先", "需要", "无效",
    "failed", "Failed", "Cannot", "cannot", "Could not", "Not connected",
    "Connect to", "gone", "Invalid", "invalid", "Out of range", "too big"
)

// ---------------------------------------------------------------- last result

/** One finished run, as shown in the bottom bar. */
private data class H3Result(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val bytes: Long,
    val duration: String?,
    val uri: Uri
)

/**
 * Pull the finished video out of `/history/<promptId>` into the media cache. Returns null while the
 * server has not written the output yet, which is the signal for the caller to poll again.
 *
 * The cache is the same one the gallery uses, so the file is there for offline playback; saving to
 * the phone's gallery stays an explicit action.
 */
private suspend fun loadH3Result(
    context: android.content.Context,
    client: sh.hnet.comfychair.ComfyUIClient,
    promptId: String
): H3Result? {
    val deferred = CompletableDeferred<JSONObject?>()
    client.fetchHistory(promptId) { json -> deferred.complete(json) }
    val json = withTimeoutOrNull(20_000L) { deferred.await() } ?: return null
    val outputs = json.optJSONObject(promptId)?.optJSONObject("outputs") ?: return null

    var found: Triple<String, String, String>? = null
    for (nodeId in outputs.keys()) {
        val node = outputs.optJSONObject(nodeId) ?: continue
        val arr = node.optJSONArray("videos") ?: node.optJSONArray("gifs") ?: node.optJSONArray("images")
        val info = arr?.optJSONObject(0) ?: continue
        val name = info.optString("filename")
        if (name.isBlank()) continue
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("mp4", "webm", "mov", "mkv")) continue
        found = Triple(name, info.optString("subfolder"), info.optString("type").ifBlank { "output" })
        break
    }
    val output = found ?: return null
    val key = MediaCacheKey(promptId, output.first)
    val byteCount = MediaCache.ensureVideoCached(key, output.second, output.third) ?: return null
    val uri = MediaCache.getVideoUri(key, context) ?: return null
    return H3Result(
        promptId = promptId,
        filename = output.first,
        subfolder = output.second,
        type = output.third,
        bytes = byteCount,
        duration = videoSeconds(context, uri),
        uri = uri
    )
}

private fun videoSeconds(context: android.content.Context, uri: Uri): String? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        if (ms == null || ms <= 0L) null else "${ms / 1000}s"
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun h3Poster(context: android.content.Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun h3FormatBytes(bytes: Long): String = when {
    bytes >= (1L shl 30) -> "%.1f GB".format(bytes.toDouble() / (1L shl 30).toDouble())
    bytes >= (1L shl 20) -> "%.1f MB".format(bytes.toDouble() / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

/** The last run, one line tall: poster, length and size, then play / save / share. */
@Composable
private fun H3ResultRow(
    result: H3Result,
    poster: Bitmap?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onContinue: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center
        ) {
            if (poster != null) {
                Image(
                    bitmap = poster.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            listOfNotNull(result.duration, h3FormatBytes(result.bytes)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpen, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.h3_play), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onSave, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.h3_save_album), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.h3_share), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onContinue, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.h3_continue_last), modifier = Modifier.size(16.dp))
        }
    }
}

/** Ratio closest to the first frame's own shape, or null when it cannot be measured. */
private fun frameAspectSuggestedRatio(
    context: android.content.Context,
    workflowId: String,
    file: String,
    ratios: List<Pair<String, Pair<Int, Int>>>
): String? {
    if (file.isBlank() || ratios.isEmpty()) return null
    val cached = java.io.File(
        context.filesDir,
        "h3_previews/$workflowId/${java.util.UUID.nameUUIDFromBytes(file.toByteArray())}.png"
    )
    if (!cached.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(cached.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val imageAspect = bounds.outWidth.toDouble() / bounds.outHeight
    return ratios.minByOrNull { (_, wh) -> Math.abs(imageAspect - wh.first.toDouble() / wh.second) }?.first
}

/** 26dp pill for the tail-frame row: three of them plus the title must fit a 304dp card. */
@Composable
private fun H3MiniAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                0.8.dp,
                if (enabled) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
    }
}

/**
 * One-line continuation control. The frame is kept out of the reference grid on purpose: it is
 * replaced on every segment, while the picked references stay put.
 */
@Composable
private fun H3ContinuationSlot(
    file: String,
    auto: Boolean,
    enabled: Boolean,
    hasResult: Boolean,
    mode: Int,
    autoName: Boolean,
    sequenceName: String,
    queuedCount: Int,
    onMode: (Int) -> Unit,
    onRelayHelp: () -> Unit,
    onSequenceName: (String) -> Unit,
    onCapture: () -> Unit,
    onPickVideo: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    val attached = file.isNotBlank()
    // 10sp against a 170dp column fits 15 Chinese characters, which is what keeps the
    // explanation at two lines on a 320dp phone instead of wrapping to four.
    val noteStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 16.sp)
    val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    // The server appends its own _00001 counter, so the whole name is predictable here and the
    // prefix alone was ambiguous about what would actually land on disk.
    val fileName = H3Naming.prefixFor(
        H3Naming.chainName(sequenceName, autoName), 1, autoName
    ) + "_00001.mp4 …"

    Column(Modifier.fillMaxWidth()) {
        // Title and actions share one line: 取帧 / 上传 answer "where did this frame come from",
        // which belongs next to the state rather than in a second row further down.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.h3_tail_link),
                style = MaterialTheme.typography.labelMedium,
                color = if (attached) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            // Only when something is attached: colour already says "empty", and a permanent
            // 「未挂」 badge read like part of the section name.
            if (attached) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.h3_tail_attached_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            H3MiniAction(
                label = when {
                    attached -> stringResource(R.string.h3_tail_retake)
                    hasResult -> stringResource(R.string.h3_tail_continue)
                    else -> stringResource(R.string.h3_tail_none)
                },
                enabled = enabled && hasResult,
                onClick = onCapture
            )
            Spacer(Modifier.width(4.dp))
            H3MiniAction(label = stringResource(R.string.h3_upload_video), enabled = enabled, onClick = onPickVideo)
            if (attached) {
                Spacer(Modifier.width(2.dp))
                Text(
                    stringResource(R.string.h3_clear_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = enabled, onClick = onClear)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        H3Divider()

        // One switch, one required field. The project name is the chain's identity: the server
        // writes it into every filename and the gallery groups by it, so an unnamed chain is a
        // chain that cannot be handed back. The old 「自动编号」 switch is gone — a name implies
        // numbering, and a blank one simply keeps the plain single-run filename.
        Row(
            Modifier.height(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.h3_relay_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = auto,
                enabled = enabled,
                onCheckedChange = onToggleAuto
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(
                        0.8.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable { onRelayHelp() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            stringResource(R.string.h3_relay_auto_tail),
            style = noteStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.h3_relay_fail_wait),
            style = noteStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        // Only the chain is named. With the switch off this cell is dead weight — it would change
        // nothing about the file — so it dims and stops taking input rather than accepting a name
        // that the submission is going to ignore.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.h3_project_name),
                style = noteStyle,
                color = if (auto) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (auto) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .border(
                        0.8.dp,
                        when {
                            !auto -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            sequenceName.isBlank() -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = sequenceName,
                    onValueChange = onSequenceName,
                    singleLine = true,
                    enabled = enabled && auto,
                    textStyle = noteStyle.copy(
                        color = if (auto) MaterialTheme.colorScheme.onSurface else faint
                    ),
                    decorationBox = { inner ->
                        if (sequenceName.isBlank()) Text(
                            stringResource(R.string.h3_relay_prefix_hint),
                            style = noteStyle.copy(color = faint)
                        )
                        inner()
                    }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            fileName,
            style = noteStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // The button is already greyed out on an unnamed chain; this says why, next to the
        // field that fixes it rather than in a toast that has scrolled away.
        if (auto && sequenceName.isBlank()) Text(
            stringResource(R.string.h3_msg_project_required),
            style = noteStyle,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp)
        )

        if (auto && queuedCount > 0) Text(
            stringResource(R.string.h3_relay_queued_n, queuedCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
