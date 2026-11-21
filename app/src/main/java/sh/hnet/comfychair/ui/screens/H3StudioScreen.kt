package sh.hnet.comfychair.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import sh.hnet.comfychair.ui.components.shared.ModelDropdown
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
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.storage.MetadataStore
import sh.hnet.comfychair.storage.SequenceStore
import sh.hnet.comfychair.queue.H3RelayService
import sh.hnet.comfychair.workflow.ProgressStage
import sh.hnet.comfychair.queue.H3RelayStatus
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.H3SeedField
import sh.hnet.comfychair.workflow.H3Naming
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.H3Frame
import sh.hnet.comfychair.util.LoraInfo
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.workflow.H3Continuation
import sh.hnet.comfychair.workflow.H3Plan
import sh.hnet.comfychair.workflow.H3Resolution
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
    onPickWorkflow: () -> Unit = {}
) {
    val context = LocalContext.current
    val workflow = remember(workflowId) { WorkflowManager.getWorkflowById(workflowId) }
    if (workflow == null) {
        Text("工作流不存在，请重新导入", Modifier.padding(16.dp))
        return
    }
    val baseSource = workflow.jsonContent
    val spec = remember(baseSource) { H3Plan.resolve(baseSource) }
    if (spec == null) {
        Text("该工作流当前无法识别为 H3，请在专业编辑器中检查节点结构", Modifier.padding(16.dp))
        return
    }
    val draftStore = remember { H3DraftStore(context) }
    val presetStore = remember { H3PresetStore(context) }
    var presetName by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(presetStore.list()) }
    var draft by remember(workflowId, area) { mutableStateOf(draftStore.load(workflowId, area, spec)) }
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
    val modeLabel = if (mode == H3Plan.Mode.FL2V && draft.first.isBlank()) "尾帧视频" else mode.label

    val models by ConnectionManager.modelCache.collectAsState()
    val client = ConnectionManager.clientOrNull
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val pendingStore = remember { PendingJobStore(context) }
    // Survives a backgrounded or killed process: the id is written at submit time.
    val autoPrefs = remember {
        context.getSharedPreferences("h3_continuation", android.content.Context.MODE_PRIVATE)
    }
    var pendingAutoId by remember { mutableStateOf(autoPrefs.getString("pending", null)) }

    var message by remember { mutableStateOf("") }
    var showDeclareHelp by remember { mutableStateOf(false) }
    var showRelayHelp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showBasic by remember { mutableStateOf(true) }
    var showModels by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    // Reference audio is on almost no run, so the section stays a single line until tapped.
    var showAudio by remember(workflowId) { mutableStateOf(false) }
    // 「更多比例」and the megapixel dialog are transient; neither is persisted with the draft.
    var showMoreRatios by remember(workflowId) { mutableStateOf(false) }
    // Which number is being edited in the shared dialog; null closes it. The draft is only
    // touched on 确定, so 取消 is a true discard instead of a reverse patch.
    var editing by remember(workflowId) { mutableStateOf<H3NumberParam?>(null) }
    var editText by remember(workflowId) { mutableStateOf("") }
    var pendingSlot by remember { mutableStateOf<String?>(null) }
    var sourcePickerSlot by remember { mutableStateOf<String?>(null) }
    /** Which reference-audio slot the picker is filling; -1 means no pick is in flight. */
    var pendingAudio by remember { mutableStateOf(-1) }
    var previewRevision by remember { mutableStateOf(0) }
    val isTurbo = remember(spec) { spec.extras.any { it.key == "94:value" } }
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
                        "${stage.label} · 第 ${state.progress}/${state.maxProgress} 步"
                    else -> stage.label
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

    // 完整连拍: queued segments are draft snapshots, so each keeps the settings it was added with.
    var relayCount by remember { mutableStateOf(autoPrefs.getInt("relay_count", 0)) }
    var relaySubmitAfterFrame by remember { mutableStateOf(false) }
    var relaySubmitTick by remember { mutableStateOf(0) }
    var relayDone by remember { mutableStateOf(autoPrefs.getInt("relay_done", 0)) }
    val relayPaused by H3RelayStatus.paused.collectAsState()

    fun persistRelay(n: Int) {
        autoPrefs.edit().putInt("relay_count", n).apply()
        relayCount = n
    }

    fun relaySave(index: Int, d: H3Draft) = draftStore.save("__relay$index", area, d)

    fun relayLoad(index: Int): H3Draft = draftStore.load("__relay$index", area, spec)

    /** Pops the head of the queue and shifts the rest down. */
    fun relayShift(): H3Draft {
        val head = relayLoad(0)
        for (i in 1 until relayCount) relaySave(i - 1, relayLoad(i))
        persistRelay(relayCount - 1)
        return head
    }

    fun enqueueSegment() {
        relaySave(relayCount, draft)
        persistRelay(relayCount + 1)
        H3RelayStatus.report(relayDone, relayCount)
        H3RelayService.update(context)
    }

    fun finishRelay() {
        relayDone = 0
        autoPrefs.edit().putInt("relay_done", 0).apply()
        H3RelayStatus.clear()
        H3RelayService.stop(context)
    }

    // Reference tiles. The template itself wires `ref_image_0..N`, so those slots are always
    // legal without asking anyone; only going past them needs the server's own declaration.
    var probedCeiling by remember(workflowId) { mutableStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var selectedRef by remember(workflowId, area) { mutableStateOf(-1) }
    val templateSlots = spec.refIndices.size.coerceAtLeast(3)
    val filledRefs = draft.refs.filterIndexed { i, f -> f.isNotBlank() && i !in draft.disabledRefs }

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
                    "正文声明的 <Picture $claim> 与本次尾帧 <Picture $picture> 不一致，未写入；先改正文"
                } else {
                    "检测到你已声明 <Picture $picture>，本次未写入"
                }
                return
            }
            update(draft.copy(
                prompt = applied.prompt,
                continuationInject = true,
                continuationOwner = applied.declared,
                continuationFlag = applied.flagged
            ))
            message = "已写入尾帧声明：<Picture $picture> 是上一段尾帧（正文只写下一步动作）"
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
                "已撤回尾帧声明，正文恢复原样；需自行声明 <Picture $picture> 是上一段尾帧"
            } else {
                "正文里没有我们写入的声明，未改动"
            }
        }
    }

    /**
     * Stops the job this page put on the queue. Softer than killing the browser tab: the
     * server drops the prompt and whatever already finished stays in the cache.
     */
    fun abortOwnJob() {
        val live = ConnectionManager.clientOrNull ?: run {
            message = "请先连接服务器"
            return
        }
        live.interruptExecution { ok ->
            main.post {
                message = if (ok) "已中止当前任务" else "中止失败：服务器未响应"
                lastPromptId = null
                resultLoading = false
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
            message = "取帧失败：视频无法解码，可先播放确认文件是否完整"
            return
        }
        val target = ConnectionManager.clientOrNull ?: run { busy = false; message = "请先连接服务器"; return }
        val name = "h3_tail_${UUID.randomUUID()}.png"
        target.uploadImage(png, name, overwrite = false, returnFullPath = true) { ok, uploaded, err, _ ->
            main.post {
                busy = false
                if (target !== ConnectionManager.clientOrNull) {
                    message = "服务器已切换，本次尾帧未应用"
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
                    update(
                        draft.copy(
                            continuation = uploaded,
                            prompt = applied.prompt,
                            continuationOwner = owned,
                            continuationFlag = applied.flagged ||
                                (owned == picture && draft.continuationFlag)
                        )
                    )
                    message = when {
                        !draft.continuationInject ->
                            "尾帧已挂为 <Picture $picture>：需在 subject_definitions 声明它是前段尾帧，正文直接写下一步动作（勿复述画面）"
                        !applied.wrote ->
                            "尾帧已挂为 <Picture $picture>；正文已有同样声明，未重复写入"
                        else ->
                            "尾帧已挂为 <Picture $picture>，正文只写下一步动作（勿复述画面）"
                    }
                    // The queued segment can only start once its frame is on the server.
                    if (relaySubmitAfterFrame) {
                        relaySubmitAfterFrame = false
                        relaySubmitTick++
                    }
                } else {
                    message = "尾帧上传失败：${err ?: "服务器未返回文件名"}"
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
            message = "先取回成片：本页还没有可用的上一段结果"
            return
        }
        if (!auto) { busy = true; message = "正在取末帧…" }
        scope.launch {
            uploadTailFrame(withContext(Dispatchers.IO) { H3Frame.lastFramePng(context, finished.uri) })
        }
    }

    // Fallback for a clip this page did not render: pick any video from the phone, take its last
    // frame locally and hang it exactly like an automatic one. Only the PNG leaves the device.
    val tailVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; message = "正在从视频取末帧…"
        scope.launch {
            uploadTailFrame(withContext(Dispatchers.IO) { H3Frame.lastFramePng(context, uri) })
        }
    }

    // 连拍: refill the continuation frame as soon as a run finishes, so only the prompt and the
    // length need editing between segments. Never touches the references the user picked.
    // A run that finished while the app was in the background -- or after the process was killed --
    // still gets its tail frame: the submitted id is persisted and retried when this page returns.
    LaunchedEffect(pendingAutoId) {
        val id = pendingAutoId ?: return@LaunchedEffect
        if (result?.promptId == id) {
            autoPrefs.edit().remove("pending").apply(); pendingAutoId = null
            return@LaunchedEffect
        }
        val live = ConnectionManager.clientOrNull ?: return@LaunchedEffect
        val fetched = withContext(Dispatchers.IO) { loadH3Result(context, live, id) }
        if (fetched == null) return@LaunchedEffect
        result = fetched
        resultPoster = withContext(Dispatchers.IO) { h3Poster(context, fetched.uri) }
        autoPrefs.edit().remove("pending").apply()
        pendingAutoId = null
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
        if (live == null) { message = "请先连接服务器"; return }
        probing = true
        live.fetchRefImageCeiling { max ->
            main.post {
                probing = false
                probedCeiling = max ?: 0
                if (max == null || max <= current)
                    message = "服务器未返回参考图上限，当前最多 $current 张"
                else onResult(max)
            }
        }
    }



    // ---- upload
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = pendingSlot
        val target = ConnectionManager.clientOrNull
        if (uri == null || slot == null || target == null) return@rememberLauncherForActivityResult
        busy = true; message = "正在上传图片…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytes(32 * 1024 * 1024 + 1) }
                    require(bytes.size <= 32 * 1024 * 1024) { "图片超过 32 MB" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000L) { "不支持的图片或图片像素过大" }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片解码失败")
                    val png = java.io.ByteArrayOutputStream().use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
                    }
                    bitmap.recycle(); png
                }
            }.onSuccess { png ->
                target.uploadImage(png, "h3_${UUID.randomUUID()}.png", overwrite = false, returnFullPath = true) { ok, name, error, _ ->
                    main.post {
                        busy = false
                        if (target !== ConnectionManager.clientOrNull) message = "服务器已切换，本次上传未应用"
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
                            message = "上传成功"
                        } else message = "上传失败：${error ?: "服务器未返回文件名"}"
                    }
                }
             }.onFailure { busy = false; message = "图片读取失败：${it.message}" }
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
        busy = true; message = "正在上传音频…"
        scope.launch {
            val picked = runCatching {
                withContext(Dispatchers.IO) {
                    val display = queryDisplayName(context, uri).orEmpty()
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val bytes = context.contentResolver.openInputStream(uri)!!
                        .use { it.readNBytes(64 * 1024 * 1024 + 1) }
                    require(bytes.size <= 64 * 1024 * 1024) { "音频超过 64 MB" }
                    H3PickedAudio(display, mime, bytes, audioDuration(context, uri))
                }
            }.getOrElse { busy = false; message = "音频读取失败：${it.message}"; return@launch }
            val ext = picked.name.substringAfterLast('.', "")
                .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "m4a"
            val filename = "h3_${UUID.randomUUID()}.$ext"
            target.uploadImage(picked.bytes, filename, overwrite = false, returnFullPath = true,
                mimeType = picked.mime) { ok, name, error, _ ->
                main.post {
                    busy = false
                    if (target !== ConnectionManager.clientOrNull) message = "服务器已切换，本次上传未应用"
                    else if (ok && name != null) {
                        val index = pendingAudio.coerceAtLeast(0)
                        val audios = draft.audios.toMutableList()
                        val seconds = draft.audioSeconds.toMutableList()
                        while (audios.size <= index) audios.add("")
                        while (seconds.size <= index) seconds.add("")
                        audios[index] = name
                        seconds[index] = picked.seconds?.let { "%.1fs".format(it) }.orEmpty()
                        update(draft.copy(audios = audios, audioSeconds = seconds))
                        message = "音频 ${index + 1} 已添加"
                    } else message = "音频上传失败：${error ?: "服务器未返回文件名"}"
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
        val live = ConnectionManager.clientOrNull ?: run { message = "请先连接服务器"; return }
        if (live !== ConnectionManager.clientOrNull) return
        // The live map may have been filled since this screen was composed; read it here.
        val known = maxOf(spec.maxRefSlots, H3Plan.maxRefSlots(), probedCeiling)
        // Tiles in use, not the backing list length: the draft always carries three empty
        // placeholders, and asking the server while two of them are still free is the bug.
        val used = draft.refs.indexOfLast { it.isNotBlank() } + 1
        when (val next = H3Plan.nextRefSlot(used, templateSlots, known)) {
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
        val live = ConnectionManager.clientOrNull ?: run { message = "请先连接服务器"; return }
        val pending = queueState.ownJobs.values
            .filter { it.status == sh.hnet.comfychair.queue.JobStatus.PENDING }
            .map { it.promptId }
        if (pending.isEmpty()) {
            if (queueState.totalQueueSize > 0) {
                live.clearQueue { ok ->
                    main.post {
                        message = if (ok) "已清空服务器队列" else "清空队列失败：服务器未响应"
                    }
                }
            } else {
                message = "队列里没有排着的任务"
            }
            return
        }
        live.deleteQueueItems(pending) { ok ->
            main.post {
                message = if (ok) "已清空自己排队的 ${pending.size} 条" else "清空队列失败：服务器未响应"
                if (ok) pending.forEach { sh.hnet.comfychair.queue.JobRegistry.removeJob(it) }
            }
        }
    }

     fun submit(front: Boolean = false) {
        // 完整连拍: only one segment runs at a time, the rest wait here on the phone.
        if (draft.continuationAuto && queueState.ownActiveJobCount + relayCount > 0) {
            enqueueSegment()
            message = "已排入第 ${queueState.ownActiveJobCount + relayCount + 1} 段，前一段跑完自动接上"
            return
        }
        val live = ConnectionManager.clientOrNull ?: run { message = "请先连接服务器"; return }
        val errors = mutableListOf<String>()
        // Disabled slots and empty slots are compressed away, so the submitted order is
        // ref_image_0..N-1 exactly as shown by the P1..PN badges.
        val refs = draft.refs.filterIndexed { index, file ->
            file.isNotBlank() && index !in draft.disabledRefs
        }
        // The continuation frame stays last so it keeps the highest <Picture N> whatever
        // else is picked, and one slot serves every segment of a chain.
        val submittedRefs = if (draft.continuation.isNotBlank()) refs + draft.continuation else refs
        if (mode == H3Plan.Mode.R2V && submittedRefs.size > H3_MAX_PICTURES)
            errors.add("尾帧是第 ${refs.size + 1} 张图，超出 $H3_MAX_PICTURES 张上限，请先禁用一张参考图")
        when (mode) {
            H3Plan.Mode.R2V -> if (refs.isEmpty()) errors.add("参考生视频至少需要一张参考图")
            H3Plan.Mode.I2V -> if (draft.first.isBlank()) errors.add("图生视频需要首帧图片")
            H3Plan.Mode.FL2V -> if (draft.first.isBlank() && draft.last.isBlank())
                errors.add("首尾帧视频至少要选一张图片")
            else -> Unit
        }
        var customW: Int? = null
        var customH: Int? = null
        var ratio: String? = draft.ratio
        if (draft.customW.isNotBlank() || draft.customH.isNotBlank()) {
            val w = draft.customW.trim().toIntOrNull()
            val h = draft.customH.trim().toIntOrNull()
            if (w == null || h == null || w < 32 || h < 32) errors.add("自定义宽高需要两个不小于 32 的整数")
            else { customW = w; customH = h }
            ratio = null
        }
        val mpValue = draft.mp.toDoubleOrNull()
        if (mpValue == null && customW == null) errors.add("像素量无效")
        if (mpValue != null && (mpValue < 0.1 || mpValue > 1.0))
            errors.add("自定义像素量需在 0.1–1.0 MP 之间")
        // One-at-a-time is the default 连拍 behaviour: relaying a queue would take the last frame
        // of whichever segment happened to finish, which is how a chain silently forks.
        if (draft.continuationAuto && draft.continuationMode == 0 && queueState.ownActiveJobCount > 0) {
            message = "连拍一次只跑一条：等这一段跑完再提交，或到「连拍」里改成排队模式"
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

        busy = true; message = "正在准备提交…"
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
                    val plan = H3Plan.resolve(attributed) ?: error("无法解析当前工作流")
                    H3Plan.build(attributed, plan, H3Plan.Request(
                        mode = mode,
                        prompt = draft.prompt,
                        ratio = ratio,
                        megapixels = mpValue,
                        customWidth = customW,
                        customHeight = customH,
                        multiple = draft.multiple.toIntOrNull(),
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
                         // Submitted count == what is actually wired, not the number of tiles shown.
                         maxReferences = if (mode == H3Plan.Mode.R2V) submittedRefs.size else null,
                        extras = modelPayload(
                            plan,
                            if (seedPatch.isEmpty()) draft
                            else draft.copy(extras = draft.extras + seedPatch)
                        )
                    ), aspectOptions)
                }
            }.getOrElse {
                busy = false; message = "无法准备提交：${it.message}"; return@launch
            }
            if (!built.ok) { busy = false; message = built.errors.joinToString("；"); return@launch }
            val preflight = SubmitPreflight.prepare(built.json) { ct, key ->
                ConnectionManager.nodeTypeRegistry.getInputDefinition(ct, key)?.options
            }
            if (!preflight.ok) {
                busy = false
                message = "提交前检查未通过：" + preflight.issues.joinToString("；") { it.toString() }
                return@launch
            }
            // 连拍 naming: one prefix per chain, numbered per submission, so the gallery can tell
            // a chain apart and the files stay recognisable on the PC too.
            var prompt = preflight.prompt
            var chain: String? = null
            var chainPosition = 0
            if (draft.continuationAuto) {
                chain = H3Naming.chainName(draft.sequenceName, draft.sequenceAutoName)
                chainPosition = nextChainPosition(chain)
                prompt = H3Naming.applyPrefix(prompt, H3Naming.prefixFor(chain, chainPosition, draft.sequenceAutoName))
            }
            // Keep the last few real payloads: without them a failed run cannot be reproduced.
            withContext(Dispatchers.IO) { archivePayload(context, mode, prompt) }
            val onResult: (Boolean, String?, String?) -> Unit = { ok, id, error ->
                main.post {
                    busy = false
                    if (ok && id != null) {
                        chain?.let { SequenceStore.record(context, id, it, chainPosition) }
                        pendingStore.save(PendingJobStore.PendingJob(
                            id, live.getBaseUrl().orEmpty(), "H3 ${mode.label}",
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
                        if (draft.continuationAuto) {
                            autoPrefs.edit().putString("pending", id).apply()
                            pendingAutoId = id
                        }
                        message = "已提交 ${mode.label} · prompt_id=$id"
                    } else message = "提交失败：${error ?: "服务器未返回 prompt_id"}"
                }
            }
            val vm = generationViewModel
            if (vm != null && vm.getClient() === live) {
                vm.startGeneration(
                    prompt,
                    // Borrowing the ordinary pages' owner ids made their progress bars react to an
                    // H3 job and left this page unable to recognise its own.
                    H3_OWNER_ID,
                    ContentType.VIDEO, front = false,
                    onResult = { ok, id, error -> onResult(ok, id, error) })
            } else {
                live.submitPrompt(prompt, front = front) { ok, id, error, _ -> onResult(ok, id, error) }
            }
        }
    }

    // 完整连拍: when a segment finishes, the next queued one becomes the live draft before its
    // frame is captured, so the declaration and the picture number belong to that segment.
    LaunchedEffect(result?.promptId, draft.continuationAuto, relayPaused) {
        if (result == null || !draft.continuationAuto || busy) return@LaunchedEffect
        // Paused from the notification: keep the queue, do nothing until resumed.
        if (relayPaused) return@LaunchedEffect
        if (relayCount > 0) {
            draft = relayShift().copy(continuationAuto = true)
            relaySubmitAfterFrame = true
            relayDone += 1
            autoPrefs.edit().putInt("relay_done", relayDone).apply()
            H3RelayStatus.report(relayDone, relayCount)
            H3RelayService.update(context)
        } else if (relayDone > 0) {
            finishRelay()
        }
        captureLastFrame(auto = true)
    }

    LaunchedEffect(relaySubmitTick) {
        if (relaySubmitTick > 0) submit(front = false)
    }

    val frames = draft.seconds.toDoubleOrNull()?.let { sec -> spec.duration?.expression?.let { ComfyFrames(it, sec) } }
    val stepsShown = draft.steps.ifBlank { spec.steps?.steps?.toString().orEmpty() }

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

        val cardTitles = listOf(
            "视频提示词",
            if (area == H3Plan.Area.R2V) "参考素材" else "素材",
            "基础参数",
            "模型与 LoRA",
            "高级设置"
        )
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
                H3Card("视频提示词") {
                    // Only where a tail frame can live: this switch writes the declaration for
                    // that slot, and the other tabs have no such slot at all.
                    if (area == H3Plan.Area.R2V && draft.continuation.isNotBlank()) {
                        val picture = filledRefs.size + 1
                        val claim = H3Continuation.declaredPicture(draft.prompt)
                        val mismatch = claim != null && claim != picture
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "<Picture $picture> 是上一段尾帧",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "代写尾帧声明",
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
                                draft.continuationInject -> "已写入 · 正文只写下一步动作"
                                mismatch -> "未写入 · 正文声明的是 <Picture $claim>，本次尾帧是 <Picture $picture>"
                                else -> "未写入 · 需自己声明这张图"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                draft.continuationInject -> MaterialTheme.colorScheme.primary
                                mismatch -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    OutlinedTextField(
                        value = draft.prompt,
                        onValueChange = { update(draft.copy(prompt = it)) },
                        minLines = 5, maxLines = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 2. 素材
            item {
                H3Card(
                    title = if (area == H3Plan.Area.R2V) "参考素材" else "素材",
                    subtitle = if (area == H3Plan.Area.R2V) {
                        if (filledRefs.isEmpty()) "至少 1 张，最多 ${draft.refs.size}"
                        else "已选 ${filledRefs.size} / ${draft.refs.size} 张" +
                            (if (draft.continuation.isNotBlank()) " · 含尾帧" else "")
                    } else {
                        "当前：$modeLabel · " + when {
                            mode == H3Plan.Mode.T2V -> "未选图片，纯文生"
                            mode == H3Plan.Mode.I2V -> "提交首帧"
                            draft.first.isBlank() -> "只提交尾帧，画面朝它收尾"
                            draft.last.isBlank() -> "只提交首帧"
                            else -> "提交首帧 + 尾帧"
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
                            if (shown == 0) "点方框上传第一张；顺序即 <Picture 1..N>。"
                            else "顺序即 <Picture 1..N>；点方块选中后可前移、后移、禁用或删除。",
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
                            autoName = draft.sequenceAutoName,
                            sequenceName = draft.sequenceName,
                            queuedCount = queueState.ownActiveJobCount + relayCount,
                            onMode = { update(draft.copy(continuationMode = it)) },
                            onToggleAutoName = { update(draft.copy(sequenceAutoName = it)) },
                            onSequenceName = { update(draft.copy(sequenceName = it)) },
                            onCapture = { captureLastFrame(auto = false) },
                            onPickVideo = { tailVideoPicker.launch("video/*") },
                            onToggleAuto = { update(draft.copy(continuationAuto = it)) },
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
                                        "已清除尾帧衔接（<Picture $owned> 声明同时移除）"
                                    draft.continuationFlag ->
                                        "已清除尾帧衔接（summary 标记同时移除）"
                                    else ->
                                        "已清除尾帧衔接；正文里那行是你写的，未动"
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
                                "提示词里按顺序用 <Audio 1..N> 指代这些音频。",
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
                                label = "首帧",
                                file = draft.first,
                                workflowId = workflowId,
                                revision = previewRevision,
                                enabled = !busy && client != null,
                                modifier = Modifier.weight(1f),
                                onPick = { sourcePickerSlot = "first" },
                                onRemove = { update(draft.copy(first = "")); previewRevision++ }
                            )
                            H3FrameSlot(
                                label = "尾帧",
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
                                        Text("按首帧比例", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        Text(
                            when (mode) {
                                H3Plan.Mode.T2V -> "两张都空着就是文生视频，不提交任何图片。"
                                H3Plan.Mode.I2V -> "只填首帧就是图生视频；再填尾帧即变成首尾帧视频。"
                                else -> "首帧锁定开头、尾帧锁定结尾；只填尾帧也能生成，画面朝那张收尾。"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // 3. 生成参数
            item {
                // 基础参数 stays open by default: duration is re-read on almost every run, so
                // hiding it behind a tap only makes the bottom bar the sole visible number.
                H3Card("基础参数", trailing = {
                    TextButton(onClick = { showBasic = !showBasic }) {
                        Text(if (showBasic) "收起" else "展开")
                    }
                }) {
                    if (!showBasic) Text(
                        "时长 ${draft.seconds}s · ${draft.ratio} · ${draft.mp} MP · 步数 ${stepsShown.ifBlank { "默认" }}",
                        style = MaterialTheme.typography.labelSmall
                    ) else {
                    val size = runCatching {
                        H3Resolution.size(
                            draft.ratio.substringBefore(':').toIntOrNull() ?: 16,
                            draft.ratio.substringAfter(':').toIntOrNull() ?: 9,
                            draft.mp.toDoubleOrNull() ?: 0.5
                        )
                    }.getOrNull()

                    H3ParamBlock(
                        label = "视频时长（秒）",
                        detail = if (draft.seconds.isBlank()) "" else "${draft.seconds} 秒",
                        hint = when {
                            frames == null -> ""
                            else -> "≈ $frames 帧 @ ${spec.fps}fps"
                        },
                        presets = listOf("6", "10", "15"),
                        value = draft.seconds,
                        enabled = !busy && spec.duration != null,
                        onPreset = { update(draft.copy(seconds = it)) },
                        onCustom = { editText = draft.seconds; editing = H3NumberParam.SECONDS }
                    )
                    H3Divider()
                    Text("画面比例", style = MaterialTheme.typography.labelLarge)
                    // Six everyday ratios stay visible; 21:9 and 2:3 live behind one text row so the
                    // grid costs two rows instead of three. The selected one is named in that row
                    // even when collapsed, so collapsing can never hide the current choice.
                    H3ChipGrid(
                        items = commonRatioPairs.map { H3Resolution.label(it.first) to it.first },
                        selected = ratioOn,
                        enabled = ratioEnabled
                    ) { pickRatio(it) }
                    if (showMoreRatios) H3ChipGrid(
                        items = extraRatioPairs.map { H3Resolution.label(it.first) to it.first },
                        selected = ratioOn,
                        enabled = ratioEnabled
                    ) { pickRatio(it) }
                    Text(
                        if (showMoreRatios) "收起更多比例 \u25b4"
                        else buildString {
                            append("更多比例　")
                            append(extraRatioPairs.joinToString(" · ") { (value, _) ->
                                val shown = H3Resolution.label(value)
                                if (draft.ratio == value) "$shown（当前）" else shown
                            })
                            append("　\u25be")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ratioEnabled) { showMoreRatios = !showMoreRatios }
                            .padding(vertical = 4.dp)
                    )
                    if (ratios.isEmpty()) Text("工作流没有可编辑的比例选择器",
                        style = MaterialTheme.typography.labelSmall)
                    H3Divider()
                    H3ParamBlock(
                        label = "像素量",
                        detail = "${draft.mp} MP",
                        hint = when {
                            draft.customW.isNotBlank() || draft.customH.isNotBlank() ->
                                "自定义 ${draft.customW}×${draft.customH}"
                            size != null -> "${size.width} × ${size.height}"
                            else -> ""
                        },
                        presets = H3Resolution.megapixelPresets.map { "$it MP" },
                        value = "${draft.mp} MP",
                        enabled = ratioEnabled,
                        onPreset = { picked ->
                            update(draft.copy(
                                mp = picked.removeSuffix(" MP"),
                                customW = "", customH = "", mpOutOfRange = false
                            ))
                        },
                        onCustom = { editText = draft.mp; editing = H3NumberParam.MEGAPIXELS }
                    )
                    H3Divider()
                    H3ParamBlock(
                        label = "采样步数",
                        detail = if (draft.steps.isBlank()) "" else "${draft.steps} 步",
                        hint = "",
                        presets = listOf("4", "8", "20"),
                        value = draft.steps,
                        enabled = !busy && spec.steps != null,
                        onPreset = { update(draft.copy(steps = it)) },
                        onCustom = { editText = draft.steps; editing = H3NumberParam.STEPS }
                    )

                    // The seed belongs with the sampling knobs, not behind the advanced fold: it is
                    // the one value that decides whether two runs of the same prompt differ.
                    val seedExtras = spec.extras.filter { it.input in H3Seed.INPUTS }
                    if (seedExtras.isNotEmpty()) {
                        H3Divider()
                        val seedAuto = remember(workflowId) {
                            mutableStateOf(H3Seed.autoRandom(context))
                        }
                        seedExtras.forEach { extra ->
                            H3SeedField(
                                value = draft.extras[extra.key] ?: extra.value.toString(),
                                auto = seedAuto.value,
                                enabled = !busy,
                                onAutoChange = { on ->
                                    H3Seed.setAutoRandom(context, on)
                                    seedAuto.value = on
                                },
                                onRoll = {
                                    update(draft.copy(
                                        extras = draft.extras + (extra.key to H3Seed.next().toString())
                                    ))
                                },
                                onValueChange = { text ->
                                    update(draft.copy(extras = draft.extras + (extra.key to text)))
                                }
                            )
                        }
                    }

                    when (editing) {
                        H3NumberParam.SECONDS -> {
                            val v = editText.toDoubleOrNull()
                            H3NumberDialog(
                                title = "自定义视频时长", unit = "秒",
                                hint = "时长支持 4 – 15 秒；超过 15 秒请分段生成，段间用「尾帧继续」衔接，或纯文字硬接",
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
                                title = "自定义像素量", unit = "MP",
                                hint = "推荐 0.1 – 1.0 MP；越大画质越好，但显存占用越高，超过 1.0 MP 容易爆显存",
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
                                title = "自定义采样步数", unit = "步",
                                hint = "1 – 100；步数越高越慢",
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
                                title = "自定义二采像素量", unit = "MP",
                                hint = "推荐 0.5 – 2.0 MP；两阶段放大二采精修目标像素量",
                                value = editText,
                                valid = v != null && v >= 0.1 && v <= 8.0,
                                onChange = { editText = it },
                                onConfirm = {
                                    update(draft.copy(extras = draft.extras + ("23:value" to editText.trimEnd('.'))))
                                    editing = null
                                },
                                onDismiss = { editing = null }
                            )
                        }
                        H3NumberParam.SECOND_PASS_STEPS -> {
                            val v = editText.toIntOrNull()
                            H3NumberDialog(
                                title = "自定义二采采样步数", unit = "步",
                                hint = "推荐 2 – 10 步；Turbo 加速二采去噪精修步数",
                                value = editText,
                                valid = v != null && v in 1..50,
                                wholeNumbers = true,
                                onChange = { editText = it },
                                onConfirm = {
                                    update(draft.copy(extras = draft.extras + ("102:steps" to editText.trimEnd('.'))))
                                    editing = null
                                },
                                onDismiss = { editing = null }
                            )
                        }
                        H3NumberParam.RTX_SCALE -> {
                            val v = editText.toDoubleOrNull()
                            H3NumberDialog(
                                title = "自定义 RTX 超分倍率", unit = "倍",
                                hint = "填 1.0 即为不放大；1.2 为 1.2 倍放大；1.5 为 1.5 倍放大",
                                value = editText,
                                valid = v != null && v >= 1.0 && v <= 4.0,
                                onChange = { editText = it },
                                onConfirm = {
                                    update(draft.copy(extras = draft.extras + ("39:resize_type.scale" to editText.trimEnd('.'))))
                                    editing = null
                                },
                                onDismiss = { editing = null }
                            )
                        }
                        null -> Unit
                    }
                    }
                }
            }

            // 3.5 二采与 Turbo 增强流水线设置（仅在 Turbo 工作流存在 94:value 时智能显示）
            if (isTurbo) {
                item {
                    H3TurboPipelineCard(
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

            // 4. 模型
            item {
                val loraOn = spec.loras.count { slot ->
                    draft.loras[slot.id]?.enabled ?: slot.enabled
                }
                H3Card(
                    "模型与 LoRA",
                     subtitle = listOfNotNull(
                         // The dropdown can hold a pick that differs from the template's own file;
                      // the summary has to name what will actually be submitted.
                      spec.unet?.let { ref ->
                          (draft.extras[ref.key] ?: ref.value)
                              .substringAfterLast('\\').substringAfterLast('/')
                      } ?: "未识别基础模型",
                         if (spec.loras.isEmpty()) "无 LoRA" else "$loraOn/${spec.loras.size} LoRA 启用"
                     ).joinToString(" · "),
                     trailing = {
                         TextButton(onClick = { showModels = !showModels }) {
                             Text(if (showModels) "收起" else "展开")
                         }
                     }
                 ) {
                     if (!showModels) {
                         Text("只有基础模型和 LoRA 在这里；CLIP、VAE 等在高级设置里。",
                             style = MaterialTheme.typography.labelSmall)
                     } else {
                         H3ModelDropdown("UNET", spec.unet, draft, models.unets, !busy) { update(it) }
                         H3Divider()
                        if (spec.loras.isEmpty()) Text("当前工作流没有可编辑的 LoRA 加载器",
                            style = MaterialTheme.typography.labelSmall)
                        spec.loras.forEach { slot ->
                            val state = draft.loras[slot.id] ?: H3LoraDraft(slot.file, slot.strength, slot.enabled)
                            val label = if (slot.isPowerSlot) "LoRA ${slot.entryKey}" else "LoRA"
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = state.enabled, enabled = !busy, onCheckedChange = {
                                        update(draft.copy(loras = draft.loras + (slot.id to state.copy(enabled = it))))
                                    })
                                    Text(label, style = MaterialTheme.typography.labelLarge)
                                    Spacer(Modifier.weight(1f))
                                    OutlinedTextField(
                                        value = state.strength.toString(),
                                        onValueChange = {
                                            val v = it.toDoubleOrNull() ?: return@OutlinedTextField
                                            update(draft.copy(loras = draft.loras + (slot.id to state.copy(strength = v))))
                                        },
                                        label = { Text("强度") }, singleLine = true, enabled = !busy,
                                        modifier = Modifier.width(96.dp)
                                    )
                                }
                                ModelDropdown(
                                    label = "LoRA 文件",
                                    selectedValue = state.file,
                                    options = models.loras,
                                    onValueChange = {
                                        update(draft.copy(loras = draft.loras + (slot.id to state.copy(file = it))))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        H3Divider()
                        // Extra LoRA chain: appended after the workflow's own loaders, only in the
                        // submission copy — same behaviour as ComfyChair's own LoRA injection.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("额外 LoRA ${draft.extraLoras.size}/${H3Plan.LoraAdd.MAX_ENTRIES}",
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            TextButton(
                                enabled = !busy && draft.extraLoras.size < H3Plan.LoraAdd.MAX_ENTRIES,
                                onClick = { update(draft.copy(extraLoras = draft.extraLoras + H3LoraAdd())) }
                            ) { Text("+ 添加") }
                        }
                        Text("追加在现有 LoRA 之后，只影响本次提交，不改工作流。",
                            style = MaterialTheme.typography.labelSmall)
                        draft.extraLoras.forEachIndexed { index, entry ->
                            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                ModelDropdown(
                                    label = "额外 LoRA ${index + 1}",
                                    selectedValue = entry.name,
                                    options = models.loras,
                                    onValueChange = { chosen ->
                                        val list = draft.extraLoras.toMutableList()
                                        list[index] = entry.copy(name = chosen)
                                        update(draft.copy(extraLoras = list))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = entry.strength,
                                        onValueChange = { text ->
                                            val list = draft.extraLoras.toMutableList()
                                            list[index] = entry.copy(
                                                strength = text.filter { c -> c.isDigit() || c == '.' })
                                            update(draft.copy(extraLoras = list))
                                        },
                                        label = { Text("强度") }, singleLine = true, enabled = !busy,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(enabled = !busy, onClick = {
                                        update(draft.copy(extraLoras = draft.extraLoras.filterIndexed { i, _ -> i != index }))
                                    }) { Text("移除") }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { ConnectionManager.refreshServerData() }) { Text("刷新模型列表") }
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
                H3Card("高级设置", trailing = {
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "收起" else "展开")
                    }
                }) {
                    if (showAdvanced) {
                        // Encoders and VAEs rarely change, so they belong here rather than in the
                        // model card, where three dropdowns buried the UNET and the LoRA chain.
                        H3ModelDropdown("CLIP 文本编码器", spec.clip, draft,
                            models.clips.ifEmpty { models.textEncoders }, !busy) { update(it) }
                        H3ModelDropdown("视频 VAE", spec.videoVae, draft, models.vaes, !busy) { update(it) }
                        H3ModelDropdown("音频 VAE", spec.audioVae, draft, models.vaes, !busy) { update(it) }
                        H3Divider()
                        // 模板与结构：低频动作，从底栏搬到这里，仍然先于其它高级参数出现。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("工作流", style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = onPickWorkflow) {
                                Text("${workflow.name} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("结构", style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                context.startActivity(
                                    sh.hnet.comfychair.WorkflowEditorActivity
                                        .createIntentForEditingExisting(context, workflowId)
                                )
                            }) { Text("编辑结构") }
                        }
                        H3Divider()
                        if (spec.extras.isEmpty()) Text("当前工作流没有其他可编辑的标量参数",
                            style = MaterialTheme.typography.labelSmall)
                        // The seed has its own control on the basic page; repeating it here as a
                        // plain text cell was how two "seed" fields ended up disagreeing.
                        spec.extras.filter { !it.hidden && it.input !in H3Seed.INPUTS }.forEach { extra ->
                            val text = draft.extras[extra.key] ?: extra.value.toString()
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(extra.label, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { update(draft.copy(extras = draft.extras + (extra.key to it))) },
                                    singleLine = true, enabled = !busy,
                                    modifier = Modifier.width(150.dp)
                                )
                            }
                        }
                        spec.warnings.forEach {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        Text("SageAttention、FirstBlockCache、LowVRAM、缓存清理、模型预览与视频合成链路原样保留。",
                            style = MaterialTheme.typography.labelSmall)
                        H3Divider()
                        // Exact size and alignment bypass the ratio selector; both live here so the
                        // everyday page stays a single MP control.
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.customW,
                                onValueChange = { text ->
                                    update(draft.copy(customW = text.filter(Char::isDigit), mpOutOfRange = false))
                                },
                                label = { Text("精确宽") }, singleLine = true, enabled = !busy,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = draft.customH,
                                onValueChange = { text ->
                                    update(draft.copy(customH = text.filter(Char::isDigit), mpOutOfRange = false))
                                },
                                label = { Text("精确高") }, singleLine = true, enabled = !busy,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(enabled = !busy, onClick = {
                                update(draft.copy(customW = "", customH = ""))
                            }) { Text("清除") }
                        }
                        Text("填了就绕过比例选择器，按 32 对齐写入本次提交副本。", style = MaterialTheme.typography.labelSmall)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.multiple,
                                onValueChange = { text -> update(draft.copy(multiple = text.filter(Char::isDigit))) },
                                label = { Text("对齐倍数 (8–128)") }, singleLine = true, enabled = !busy,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                spec.resolution?.multiple?.let { "工作流用 $it" } ?: "工作流未声明",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else {
                        Text(
                            "Scheduler / Denoise / Sampler / 输出帧率 等 ${spec.extras.count { !it.hidden && it.input !in H3Seed.INPUTS }} 项可改",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    H3Divider()
                    // Parameter presets are workflow-independent: they only hold the four knobs
                    // plus the extra LoRA chain, so they can be reused across templates.
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = presetName,
                            onValueChange = { presetName = it },
                            label = { Text("预设名称") }, singleLine = true, enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(enabled = !busy && presetName.isNotBlank(), onClick = {
                            presetStore.save(presetName.trim(), draft)
                            presets = presetStore.list()
                            message = "已保存预设「${presetName.trim()}」"
                        }) { Text("保存当前参数") }
                    }
                    presets.forEach { (name, snapshot) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = {
                                update(snapshot.applyTo(draft))
                                message = "已套用预设「$name」"
                            }) { Text("套用") }
                            TextButton(enabled = !busy, onClick = {
                                presetStore.delete(name)
                                presets = presetStore.list()
                            }) { Text("删除") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, onClick = {
                            val name = WorkflowManager.generateUniqueDuplicateName(workflow.name)
                            runCatching {
                                WorkflowManager.duplicateWorkflow(workflowId, name,
                                    workflow.description.ifBlank { workflow.name } + "（副本）")
                            }.onSuccess { message = "已另存为「$name」" }
                                .onFailure { message = "另存失败：${it.message}" }
                        }) { Text("另存新版本") }
                        TextButton(onClick = onOpenWorkflowSettings) { Text("工作流管理") }
                    }
                }
            }
        }

        if (showRelayHelp) AlertDialog(
            onDismissRequest = { showRelayHelp = false },
            title = { Text("完整连拍") },
            text = {
                Text(
                    "用途\n" +
                        "一次排好几段，每段跑完自动接上一段最后一帧，把长视频拆开连续生成。\n\n" +
                        "怎么用\n" +
                        "1. 打开开关\n" +
                        "2. 写好提示词，点「添加到队列」，点几次就是几段\n" +
                        "3. 只把当前这段交给服务器；跑完自动取帧、换到参考槽、提交下一段\n\n" +
                        "注意\n" +
                        "· 每段用的是点「添加」那一刻的参数（时长 / 比例 / 步数）\n" +
                        "· 关掉开关会暂停，剩余段不丢，重新打开继续\n" +
                        "· 连拍期间请不要把 App 从后台划掉：取帧和提交都要靠手机，划掉会停住\n" +
                        "· 某段失败会停下等你，不会跳过\n\n" +
                        "相关设置\n" +
                        "设置 → 连拍通知（默认关闭）\n" +
                        "打开后：通知栏会显示「已跑完 2 段，还剩 3 段」，可以在通知里暂停 / 继续；\n" +
                        "锁屏久了也不容易被系统回收。\n" +
                        "不开也能用完整连拍，只是后台待太久时可能被系统停下，\n" +
                        "重开 App 会从剩下的段继续。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showRelayHelp = false }) { Text("知道了") }
            }
        )
        if (showDeclareHelp) AlertDialog(
            onDismissRequest = { showDeclareHelp = false },
            title = { Text("代写尾帧声明") },
            text = {
                // Each question keeps its own small heading: the old copy packed all three into
                // one run of text, so a reader looking for "will it duplicate mine?" had to
                // parse the whole block.
                val tailPicture = filledRefs.size + 1
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("它帮你写什么",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("在提示词开头补一行，说明第 $tailPicture 张图" +
                            "就是上一段视频的最后一帧。")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("正文你只需写下一步",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("不用再描述画面，也不用重复长相、衣服，" +
                            "那一行已经交代过了。")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("会不会重复",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("你已经手写过同样的声明，就跳过不写；" +
                            "关掉开关也不会删掉你写的那一行。")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeclareHelp = false }) { Text("知道了") }
            }
        )

        // 底部固定条：只剩摘要和生成。切换模板与编辑结构都是低频动作，已移入高级设置，
        // 但模板名必须留在这里——按下生成的那一刻要看得见这条命是哪个模板跑的。
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Feedback sits next to the button that produced it: a failed preflight used to
                // appear at the top of the page, which a scrolled-down user never saw.
                if (message.isNotEmpty()) Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.contains("失败") || message.contains("未通过") ||
                        message.contains("无法") || message.contains("不在") || message.contains("请先") ||
                        message.contains("需要") || message.contains("无效")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
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
                                if (draft.customW.isNotBlank()) "${draft.customW}:${draft.customH}"
                                else draft.ratio
                            ),
                            "${draft.mp} MP",
                            "${draft.seconds}s",
                            if (stepsShown.isBlank()) null else "${stepsShown} 步"
                        ).joinToString(" · ")
                        // Second pass gets its own two lines: one line of everything ran off the
                        // edge on a phone, and the values that fell off were the ones that matter.
                        val secondPassOn = isTurbo &&
                            draft.extras["94:value"]?.toBooleanStrictOrNull() != true
                        val secondPassLine = when {
                            !isTurbo -> null
                            !secondPassOn -> "二采 关"
                            else -> listOfNotNull(
                                "二采",
                                (draft.extras["23:value"] ?: "1").let { "$it MP" },
                                (draft.extras["102:steps"] ?: "3").let { "$it 步" },
                                draft.extras["102:denoise"]?.let { "降噪 $it" }
                            ).joinToString(" · ")
                        }
                        val accelLine = if (!isTurbo) null else {
                            val frameGen = draft.extras["173:value"] ?: "1"
                            listOfNotNull(
                                "RTX ${draft.extras["39:resize_type.scale"] ?: "1.2"}×",
                                if (spec.extras.any { it.key == "173:value" } && frameGen != "1") {
                                    "${frameGen}×插帧"
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
                            failure != null -> "失败 · ${h3FailureGroup(failure.nodeType)}" +
                                (failure.message.take(46).let { if (it.isBlank()) "" else "：$it" })
                            ownJobExecuting -> "生成中 · 队列 ${queueState.totalQueueSize}"
                            ownJobWaiting && queueState.ownQueuePosition != null ->
                                "排队第 ${queueState.ownQueuePosition} 位"
                            queueState.totalQueueSize > 0 -> "队列 ${queueState.totalQueueSize}"
                            resultLoading -> "正在取回结果…"
                            spec.warnings.isNotEmpty() -> "${spec.warnings.size} 项待确认（高级设置）"
                            else -> null
                        }
                        if (queueLine != null) Text(
                            queueLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failure != null || (spec.warnings.isNotEmpty() && !ownJobExecuting &&
                                queueState.totalQueueSize == 0)
                            ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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
                        isExecuting = ownJobExecuting,
                        isEnabled = !busy && client != null && !draft.mpOutOfRange,
                        isUploading = busy,
                        onGenerate = { submit(front = false) },
                        onAddToFrontOfQueue = { submit(front = true) },
                        onCancelCurrent = { abortOwnJob() },
                        onClearQueue = { clearOwnQueue() }
                    )
                }
            }
        }

        if (sourcePickerSlot != null) {
            val slot = sourcePickerSlot
            val title = when {
                slot == "first" -> "选择首帧素材"
                slot == "last" -> "选择尾帧素材"
                slot?.startsWith("ref:") == true -> "选择参考图素材"
                else -> "选择素材"
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
                onSelectFile = { filename, localPath ->
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
                    if (localPath == null) {
                        // A server file is used by its name: nothing is uploaded
                        applySlotName(filename)
                        message = "已载入服务器素材：$filename"
                    } else {
                        // A frame this app wrote is only on the phone, so the server gets a copy
                        val target = ConnectionManager.clientOrNull
                        val bytesResult = runCatching { java.io.File(localPath).readBytes() }
                        val bytes = bytesResult.getOrNull()
                        if (target == null || bytes == null || bytes.isEmpty()) {
                            message = "该帧无法读取，未能上传"
                        } else {
                            busy = true
                            message = "正在上传提取帧…"
                            target.uploadImage(
                                bytes, "h3_${UUID.randomUUID()}.png",
                                overwrite = false, returnFullPath = true
                            ) { ok, name, error, _ ->
                                main.post {
                                    busy = false
                                    if (ok && name != null) {
                                        applySlotName(name)
                                        message = "已载入提取帧：$filename"
                                    } else message = "上传失败：${error ?: "服务器未返回文件名"}"
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
private fun modelPayload(plan: H3Plan.Spec, draft: H3Draft): Map<String, Any> {
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
                ?: error("${extra.label} 需要合法数值")
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
)

private data class H3LoraAdd(val name: String = "", val strength: String = "1.0")

/**
 * Turn the failing node's class into the card the user would go and change. The raw class name is
 * kept in the parenthetical so a report is still traceable.
 */
private fun h3FailureGroup(nodeType: String?): String {
    val t = nodeType.orEmpty()
    val group = when {
        t.isBlank() -> "执行失败"
        t.contains("Lora", true) -> "LoRA"
        t.contains("VAE", true) -> "VAE"
        t.contains("UNET", true) || t.contains("Checkpoint", true) -> "模型"
        t.contains("CLIP", true) || t.contains("TextEncode", true) -> "提示词"
        t.contains("LoadImage", true) || t.startsWith("VHS_", true) -> "素材"
        t.contains("Sampler", true) || t.contains("Scheduler", true) ||
            t.contains("Guider", true) || t.contains("Noise", true) -> "采样"
        t.contains("VideoCombine", true) -> "输出合成"
        t.contains("MiniMaxH3", true) -> "H3 核心节点"
        else -> t
    }
    return if (group == t) t else "$group（$t）"
}

/** The numbers that share one dialog. */
private enum class H3NumberParam { SECONDS, MEGAPIXELS, STEPS, SECOND_PASS_MP, SECOND_PASS_STEPS, RTX_SCALE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun H3TurboPipelineCard(
    spec: H3Plan.Spec,
    draft: H3Draft,
    busy: Boolean,
    onEditNumber: (H3NumberParam, String) -> Unit,
    onUpdate: (H3Draft) -> Unit
) {
    // 节点 94:value 是 Boolean。原作者语义：True = skip second pass。
    // UI 正向语义：二采开关开 = false (不跳过，执行二采)；关 = true (跳过二采)
    val secondPassEnabled = draft.extras["94:value"]?.toBooleanStrictOrNull() != true
    val secondPassMp = draft.extras["23:value"] ?: "1"
    val secondPassSteps = draft.extras["102:steps"] ?: "3"
    val rtxScale = draft.extras["39:resize_type.scale"] ?: "1.2"
    val hasFrameGen = spec.extras.any { it.key == "173:value" }
    val frameGen = draft.extras["173:value"] ?: "1"

    H3Card(
        title = "二采与 Turbo 增强设置",
        subtitle = "两阶段采样精修 · RTX 视频超分" + (if (hasFrameGen) " · 动态插帧" else "")
    ) {
        // 顶部流程指示条
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (secondPassEnabled) "一采+二采精修" else "一采草稿",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (secondPassEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("➔", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = "RTX 超分 (${rtxScale}×)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rtxScale != "1" && rtxScale != "1.0") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasFrameGen) {
                    Text("➔", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = if (frameGen != "1") "插帧 (${frameGen}×)" else "无插帧",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (frameGen != "1") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("➔", style = MaterialTheme.typography.labelSmall)
                Text("保存视频", style = MaterialTheme.typography.labelSmall)
            }
        }

        H3Divider()

        // 1. 二采开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("二采高清精修（Second Pass）", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (secondPassEnabled) "开启：一采低清生成后经 Latent 放大由二采精修，#39 放大二采画面"
                           else "关闭：跳过二采，#39 将直接对一采画面进行倍率放大",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = secondPassEnabled,
                enabled = !busy,
                onCheckedChange = { checked ->
                    onUpdate(draft.copy(extras = draft.extras + ("94:value" to (!checked).toString())))
                }
            )
        }

        // 二采子参数（仅在二采开启时展示）
        if (secondPassEnabled) {
            H3Divider()
            H3ParamBlock(
                label = "二采目标像素",
                detail = "${secondPassMp} MP",
                hint = "两阶段放大的目标像素量",
                presets = listOf("0.5", "1", "1.5"),
                value = secondPassMp,
                enabled = !busy,
                onPreset = { picked ->
                    onUpdate(draft.copy(extras = draft.extras + ("23:value" to picked)))
                },
                onCustom = { onEditNumber(H3NumberParam.SECOND_PASS_MP, secondPassMp) }
            )

            H3Divider()
            H3ParamBlock(
                label = "二采采样步数",
                detail = "${secondPassSteps} 步",
                hint = "Simple 调度器二次去噪步数（默认降噪 0.3）",
                presets = listOf("3", "4", "5"),
                value = secondPassSteps,
                enabled = !busy,
                onPreset = { picked ->
                    onUpdate(draft.copy(extras = draft.extras + ("102:steps" to picked)))
                },
                onCustom = { onEditNumber(H3NumberParam.SECOND_PASS_STEPS, secondPassSteps) }
            )

            H3Divider()
            Column(Modifier.fillMaxWidth()) {
                Text("二采 Latent 放大模型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Color(0xFF2E7D32), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "minimax_h3_latent_upscaler_3d_fp16.safetensors（内置默认）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        H3Divider()

        // 2. RTX 超分倍率
        H3ParamBlock(
            label = "RTX 视频超分倍率",
            detail = if (rtxScale == "1" || rtxScale == "1.0") "1.0×" else "${rtxScale}×",
            hint = "Ultra 档超分辨率放大；填 1.0 即为原样不放大",
            presets = listOf("1.0", "1.2", "1.5", "2.0"),
            value = rtxScale,
            enabled = !busy,
            onPreset = { picked ->
                onUpdate(draft.copy(extras = draft.extras + ("39:resize_type.scale" to picked)))
            },
            onCustom = { onEditNumber(H3NumberParam.RTX_SCALE, rtxScale) }
        )

        // 3. 插帧倍率 (#173, 若存在)
        if (hasFrameGen) {
            H3Divider()
            Column(Modifier.fillMaxWidth()) {
                Text("视频插帧平滑", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = when (frameGen) {
                        "2" -> "2× 帧率倍增（原生 24fps 提升至 48fps）"
                        "3" -> "3× 帧率倍增（原生 24fps 提升至 72fps）"
                        else -> "关闭插帧（保持原生 24fps）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1" to "1× (关)", "2" to "2× (48fps)", "3" to "3× (72fps)").forEach { (optVal, optLabel) ->
                        val selected = frameGen == optVal
                        FilterChip(
                            selected = selected,
                            enabled = !busy,
                            onClick = {
                                onUpdate(draft.copy(extras = draft.extras + ("173:value" to optVal)))
                            },
                            label = { Text(optLabel) }
                        )
                    }
                }
            }
        }
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
            refs = List(spec.refIndices.size.coerceAtLeast(if (area == H3Plan.Area.R2V) 3 else 0)) { "" }
        )
        val raw = prefs.getString("$workflowId|${area.name}", null) ?: return empty
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return defaults
        val refs = mutableListOf<String>()
        o.optJSONArray("refs")?.let { arr -> for (i in 0 until arr.length()) refs.add(arr.optString(i)) }
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

    fun save(workflowId: String, area: H3Plan.Area, draft: H3Draft) {
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
        prefs.edit().putString("$workflowId|${area.name}", o.toString()).apply()
    }

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
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (detail.isNotEmpty()) Text(
                detail, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            if (hint.isNotEmpty()) Text(
                if (detail.isEmpty()) hint else " $hint",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
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
                text = if (customActive) value else "自定义",
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
                    if (valid) hint else "超出允许范围",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** One model file cell: shows the current file and lets the user pick from the server list. */
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
        Text("$label：工作流里没有可编辑的加载器", style = MaterialTheme.typography.labelSmall)
        return
    }
    val current = draft.extras[ref.key] ?: ref.value
    ModelDropdown(
        label = label,
        selectedValue = current,
        options = options,
        onValueChange = { chosen ->
            onDraft(draft.copy(extras = draft.extras + (ref.key to chosen)))
        },
        modifier = Modifier.fillMaxWidth()
    )
    // Known options that do not contain the current file: flag it before generation, not after.
    if (options.isNotEmpty() && current !in options) {
        Text("服务器上找不到这个文件，生成会失败", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    } else if (options.isEmpty()) {
        Text("服务器未返回可选文件列表，可先点“刷新模型列表”",
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
        Text(if (options.isEmpty()) "无列表" else label)
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
            confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } }
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
            Text(value.substringAfterLast('\\').substringAfterLast('/').ifBlank { "未选择" },
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        H3PickerButton(if (options.isEmpty()) "无列表" else "选择", options, enabled, onChange)
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
                if (disabled) "$title · 本次不提交" else title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(enabled = enabled, onClick = onPick) { Text(if (file.isBlank()) "上传" else "替换") }
            if (onToggle != null) TextButton(enabled = enabled, onClick = onToggle) {
                Text(if (disabled) "启用" else "禁用")
            }
            if (onRemove != null) TextButton(enabled = enabled, onClick = onRemove) { Text("删除") }
            if (onUp != null) TextButton(enabled = enabled, onClick = onUp) { Text("上移") }
            if (onDown != null) TextButton(enabled = enabled, onClick = onDown) { Text("下移") }
        }
        if (file.isBlank()) {
            Box(
                Modifier.fillMaxWidth().height(84.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) { Text("未选择图片", style = MaterialTheme.typography.labelSmall) }
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
    else Text(if (failed) "预览失败，请确认服务器素材仍在" else "正在加载预览…",
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
                        label = { Text(label, maxLines = 1, softWrap = false) },
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
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { if (file.isBlank()) onPick() },
            contentAlignment = Alignment.Center
        ) {
            if (file.isBlank()) Text("+ 选择", style = MaterialTheme.typography.labelMedium)
            else H3LocalOrRemotePreview(workflowId, file, revision, Modifier.fillMaxSize(), ContentScale.Crop)
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
            ) { Text("不提交", style = MaterialTheme.typography.labelSmall) }
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
                "尾帧", style = MaterialTheme.typography.labelSmall,
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
    val shape = RoundedCornerShape(10.dp)
    // Three states, three labels. "上限" used to double as "busy", which told the user they had
    // run out of slots while six were still free.
    val label = when {
        busy -> "…"
        enabled -> "+"
        else -> "上限"
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
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
            Text("音频参考（可选）", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (files.isEmpty()) "未选" else "${files.count { it.isNotBlank() }} / $max",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1, softWrap = false,
                color = if (files.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary
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
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(file, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val shown = seconds.getOrNull(index).orEmpty()
                    if (shown.isNotEmpty()) Text(
                        " · $shown", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onPick(index) }, enabled = enabled) {
                        Text("更换", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onRemove(index) }, enabled = enabled) {
                        Text("移除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (files.size < max) {
                OutlinedButton(
                    onClick = { onPick(files.size) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                ) {
                    Text("添加第 ${files.size + 1} 段音频", maxLines = 1, softWrap = false)
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
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (enabled) "添加第 1 张参考图" else "请先连接服务器",
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
                modifier = Modifier.weight(1f)) { Text("前移", style = MaterialTheme.typography.labelMedium) }
            TextButton(enabled = enabled && canRight, onClick = onRight,
                modifier = Modifier.weight(1f)) { Text("后移", style = MaterialTheme.typography.labelMedium) }
            TextButton(enabled = enabled, onClick = onReplace,
                modifier = Modifier.weight(1f)) { Text("替换", style = MaterialTheme.typography.labelMedium) }
            TextButton(enabled = enabled, onClick = onToggle,
                modifier = Modifier.weight(1f)) {
                Text(if (disabled) "启用" else "不用", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(enabled = enabled, onClick = onDelete,
                modifier = Modifier.weight(1f)) { Text("删除", style = MaterialTheme.typography.labelMedium) }
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
    return "首帧 ${bounds.outWidth}×${bounds.outHeight} 与输出 $ratio 不一致，会被拉伸变形" +
        "（尾帧是裁切，不会变形）"
}

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
    val bytes = MediaCache.fetchVideoBytes(key, output.second, output.third) ?: return null
    val uri = MediaCache.getVideoUri(key, context) ?: return null
    return H3Result(
        promptId = promptId,
        filename = output.first,
        subfolder = output.second,
        type = output.third,
        bytes = bytes.size.toLong(),
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
            Icon(Icons.Default.PlayArrow, contentDescription = "播放", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onSave, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Download, contentDescription = "保存到相册", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Share, contentDescription = "分享", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onContinue, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "用末帧继续", modifier = Modifier.size(16.dp))
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
    onToggleAutoName: (Boolean) -> Unit,
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
                "尾帧衔接",
                style = MaterialTheme.typography.labelMedium,
                color = if (attached) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            // Only when something is attached: colour already says "empty", and a permanent
            // 「未挂」 badge read like part of the section name.
            if (attached) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "已挂",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            H3MiniAction(
                label = when {
                    attached -> "重取尾帧"
                    hasResult -> "用尾帧继续"
                    else -> "暂无上一段"
                },
                enabled = enabled && hasResult,
                onClick = onCapture
            )
            Spacer(Modifier.width(4.dp))
            H3MiniAction(label = "上传视频", enabled = enabled, onClick = onPickVideo)
            if (attached) {
                Spacer(Modifier.width(2.dp))
                Text(
                    "清除",
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

        // Left column: the two switches only. Right column: what 连拍 does, then what the files
        // will be called. Splitting it this way means neither side has to borrow a row from the
        // other, which is what left an empty patch in every earlier arrangement.
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(126.dp)) {
                Row(
                    Modifier.height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "完整连拍",
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
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "自动编号",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = autoName,
                        enabled = enabled,
                        onCheckedChange = onToggleAutoName
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // 10dp lands the first note line on the same centre as 完整连拍, whose
                // row is 36dp tall; the 2dp below keeps 前缀 level with 自动编号.
                Spacer(Modifier.height(10.dp))
                Text(
                    "每段跑完自动取尾帧，接下一段",
                    style = noteStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "失败会停下等你；关掉能手动接",
                    style = noteStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("前缀", style = noteStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .width(118.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (autoName) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .border(
                                0.8.dp,
                                if (autoName) MaterialTheme.colorScheme.outlineVariant
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = sequenceName,
                            onValueChange = onSequenceName,
                            singleLine = true,
                            enabled = enabled && autoName,
                            textStyle = noteStyle.copy(
                                color = if (autoName) MaterialTheme.colorScheme.onSurface else faint
                            ),
                            decorationBox = { inner ->
                                if (sequenceName.isBlank()) Text(
                                    "留空 = h3_seq",
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
            }
        }

        if (auto && queuedCount > 0) Text(
            "已排 $queuedCount 段，跑完当前这段自动接上",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
