package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.connection.WebSocketMessage
import sh.hnet.comfychair.connection.WebSocketState
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.workflow.NodeTypeIndex
import sh.hnet.comfychair.workflow.ProgressStage

/**
 * Generation state data class
 */
data class GenerationState(
    val isGenerating: Boolean = false,
    val promptId: String? = null,
    val progress: Int = 0,
    val maxProgress: Int = 100,
    val ownerId: String? = null,
    val contentType: ContentType = ContentType.IMAGE,
    /** Which step of the pipeline the server is on, in plain words. */
    val stage: ProgressStage = ProgressStage.UNKNOWN,
    /** Wall clock when the server actually started running, for the elapsed clock. */
    val startedAt: Long = 0L
)

/**
 * Connection state enum
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * Content type for generation (determines which event to dispatch on completion)
 */
enum class ContentType {
    IMAGE,
    VIDEO
}

/**
 * One-time events emitted during generation
 */
sealed class GenerationEvent {
    data class ImageGenerated(val promptId: String) : GenerationEvent()
    data class VideoGenerated(val promptId: String) : GenerationEvent()
    data class PreviewImage(val bitmap: Bitmap) : GenerationEvent()
    data class Error(val message: String) : GenerationEvent()
    data object GenerationCancelled : GenerationEvent()
    data object ConnectionLostDuringGeneration : GenerationEvent()
    data object ClearPreviewForResume : GenerationEvent()
}

/**
 * Central ViewModel for managing generation state.
 *
 * WebSocket Architecture:
 * - ConnectionManager owns the WebSocket connection and broadcasts parsed events
 * - This ViewModel subscribes to ConnectionManager.webSocketMessages SharedFlow
 * - Multiple instances can safely coexist (e.g., MainContainer + GalleryContainer)
 * - Only the instance whose ownerId matches the generation state processes events
 */
class GenerationViewModel : ViewModel() {

    // Application context for gallery repository
    private var applicationContext: Context? = null

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    // Connection state (derived from WebSocket state)
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // Generation state
    private val _generationState = MutableStateFlow(GenerationState())
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    // One-time events
    private val _events = MutableSharedFlow<GenerationEvent>()
    val events: SharedFlow<GenerationEvent> = _events.asSharedFlow()

    // Active event handler for screen-specific event delivery
    private var activeEventHandler: ((GenerationEvent) -> Unit)? = null
    private var activeEventHandlerOwnerId: String? = null  // Track who registered the handler
    private var generationOwnerId: String? = null

    /**
     * Who is submitting right now, until the server hands back a promptId.
     *
     * The owner used to live in a single pair of fields that every submission wrote, so starting
     * a second job before the first came back re-labelled the first one: the video page then
     * tracked an image job, and the image never showed up anywhere. These two belong to the
     * request in flight and are cleared the moment its answer arrives.
     */
    private var pendingSubmitOwner: String? = null
    private var pendingSubmitContentType: ContentType = ContentType.IMAGE

    // Per-owner buffered events (keyed by ownerId)
    // This prevents events from one owner overwriting another's when jobs overlap
    private val pendingCompletions = mutableMapOf<String, GenerationEvent>()  // ImageGenerated or VideoGenerated
    private val bufferedPreviews = mutableMapOf<String, Bitmap>()  // Preview when no handler registered
    private val pendingClearPreviews = mutableSetOf<String>()  // ClearPreviewForResume when no handler

    // Polling for completion when resuming a generation
    private var completionPollingJob: Job? = null

    // Subscription jobs
    private var webSocketMessageJob: Job? = null
    private var webSocketStateJob: Job? = null

    companion object {
        private const val TAG = "Generation"
        private const val PREFS_NAME = "GenerationViewModelPrefs"
        private const val PREF_IS_GENERATING = "isGenerating"
        private const val PREF_CURRENT_PROMPT_ID = "currentPromptId"
        private const val PREF_OWNER_ID = "ownerId"
        private const val PREF_CONTENT_TYPE = "contentType"
        private const val COMPLETION_POLLING_INTERVAL_MS = 3000L

        /** Stages shorter than this are not drawn; the pipeline has many millisecond nodes. */
        private const val STAGE_MIN_DWELL_MS = 250L
    }

    /**
     * Initialize the ViewModel with context.
     * Connection is managed by ConnectionManager.
     */
    fun initialize(context: Context) {
        if (this.applicationContext != null) {
            // Already initialized
            DebugLogger.d(TAG, "Already initialized, skipping")
            return
        }

        DebugLogger.i(TAG, "Initializing")
        this.applicationContext = context.applicationContext

        // Restore JobRegistry state from persistence
        JobRegistry.restoreState(context)

        // Restore generation state
        restoreGenerationState(context)

        // Subscribe to ConnectionManager's WebSocket flows
        subscribeToWebSocketMessages()
        subscribeToWebSocketState()

        // Open WebSocket connection via ConnectionManager
        if (ConnectionManager.isConnected) {
            ConnectionManager.openWebSocket()
            // Poll queue to validate restored jobs against server
            ConnectionManager.pollQueueStatus()
        } else {
            _connectionStatus.value = ConnectionStatus.FAILED
        }

        // If we restored an active generation, check completion immediately
        if (_generationState.value.isGenerating) {
            DebugLogger.d(TAG, "Restored active generation, checking completion immediately")
            checkServerForCompletion()
        }
    }

    /**
     * Subscribe to WebSocket messages from ConnectionManager
     */
    private fun subscribeToWebSocketMessages() {
        webSocketMessageJob?.cancel()
        webSocketMessageJob = viewModelScope.launch {
            ConnectionManager.webSocketMessages.collect { message ->
                handleWebSocketMessage(message)
            }
        }
    }

    /**
     * Subscribe to WebSocket state changes from ConnectionManager
     */
    private fun subscribeToWebSocketState() {
        webSocketStateJob?.cancel()
        webSocketStateJob = viewModelScope.launch {
            ConnectionManager.webSocketState.collect { state ->
                when (state) {
                    is WebSocketState.Connected -> {
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        // Validate whatever restoreState() put back. On a cold start the queue
                        // was never polled (initialize() runs before the socket exists), so a
                        // job persisted from the previous session stays PENDING forever and the
                        // generate button keeps reading "add to queue (1)".
                        ConnectionManager.pollQueueStatus()
                        // Check for pending completion when reconnecting
                        if (_generationState.value.isGenerating) {
                            DebugLogger.d(TAG, "WebSocket reconnected, checking for completion")
                            checkServerForCompletion()
                        }
                    }
                    is WebSocketState.Connecting -> {
                        _connectionStatus.value = ConnectionStatus.CONNECTING
                    }
                    is WebSocketState.Reconnecting -> {
                        _connectionStatus.value = ConnectionStatus.RECONNECTING
                        // Persist the in-flight job, but stay quiet. A short drop is usually the
                        // system suspending the socket while backgrounded, and the reconnect path
                        // already re-checks for completion. Only a permanent failure is worth a
                        // message, which the Failed branch below handles.
                        if (_generationState.value.isGenerating) {
                            applicationContext?.let { saveGenerationState(it) }
                        }
                    }
                    is WebSocketState.Failed -> {
                        _connectionStatus.value = ConnectionStatus.FAILED
                        // Only show dialog if actively generating - otherwise user doesn't need to know
                        if (_generationState.value.isGenerating) {
                            applicationContext?.let { ctx ->
                                ConnectionManager.showConnectionAlert(ctx, state.failureType)
                            }
                        }
                    }
                    is WebSocketState.Disconnected -> {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    }
                }
            }
        }
    }

    /**
     * Handle parsed WebSocket message from ConnectionManager
     */
    private fun handleWebSocketMessage(message: WebSocketMessage) {
        val currentState = _generationState.value

        when (message) {
            is WebSocketMessage.ExecutionComplete -> {
                if (message.promptId == currentState.promptId && currentState.isGenerating) {
                    DebugLogger.i(TAG, "Generation complete (promptId: ${Obfuscator.promptId(message.promptId)})")
                    dispatchCompletionEvent(message.promptId, currentState.contentType)
                }
            }
            is WebSocketMessage.ExecutionSuccess -> {
                if (message.promptId == currentState.promptId && currentState.isGenerating) {
                    DebugLogger.i(TAG, "Generation complete via execution_success (promptId: ${Obfuscator.promptId(message.promptId)})")
                    dispatchCompletionEvent(message.promptId, currentState.contentType)
                }
            }
            is WebSocketMessage.Progress -> {
                // A step belongs to one job. Without this check the second job in a queue wrote
                // its steps onto the first job's bar.
                val forUs = message.promptId == null || message.promptId == currentState.promptId
                if (currentState.isGenerating && forUs) {
                    _generationState.value = currentState.copy(
                        progress = message.value,
                        maxProgress = message.max,
                        stage = ProgressStage.SAMPLE
                    )
                }
            }
            is WebSocketMessage.PreviewImage -> {
                // Only process previews if we're generating and live preview is enabled
                val context = applicationContext
                if (currentState.isGenerating && context != null && AppSettings.isLivePreviewEnabled(context)) {
                    dispatchEvent(GenerationEvent.PreviewImage(message.bitmap))
                }
            }
            is WebSocketMessage.ExecutionError -> {
                if (message.promptId == currentState.promptId || message.promptId == null) {
                    // Dispatch error event BEFORE resetting state, so ownerId is still available
                    // for handler matching in dispatchEvent()
                    val errorMessage = applicationContext?.getString(R.string.error_generation_failed)
                        ?: "Generation failed"
                    dispatchEvent(GenerationEvent.Error(errorMessage))
                    resetGenerationState()
                }
            }
            // When a job starts executing, update generation state to track it
            is WebSocketMessage.ExecutionStart -> {
                val promptId = message.promptId
                if (promptId.isNotEmpty()) {
                    // Look up job info from JobRegistry
                    var owner = JobRegistry.findOwner(promptId)
                    var contentType = JobRegistry.findContentType(promptId)

                    // Fallback: If not found in JobRegistry but a submission is in flight, use its
                    // owner/contentType (execution_start arrived before registerJob completed).
                    // Only an actual in-flight submission counts -- guessing from the last job
                    // picked up other clients' work and stole their progress.
                    val inFlightOwner = pendingSubmitOwner
                    if (owner == null && inFlightOwner != null) {
                        val pendingOwner = inFlightOwner
                        val pendingContentType = pendingSubmitContentType
                        pendingSubmitOwner = null
                        owner = pendingOwner
                        contentType = pendingContentType
                        DebugLogger.w(TAG, "ExecutionStart arrived before registerJob, using pending owner: $pendingOwner")

                        // Register the job now since we know the promptId
                        JobRegistry.registerJob(promptId, pendingOwner, pendingContentType, sh.hnet.comfychair.queue.JobPrompts.known(promptId))
                        applicationContext?.let { ctx -> JobRegistry.saveState(ctx) }
                    }

                    if (owner != null && contentType != null) {
                        val previousOwner = generationOwnerId
                        val ownerChanged = previousOwner != null && previousOwner != owner

                        // This is one of our jobs - update state to track it
                        DebugLogger.d(TAG, "Tracking execution start for our job (promptId: ${Obfuscator.promptId(promptId)}, owner: $owner)")
                        if (ownerChanged) {
                            DebugLogger.i(TAG, "OWNER CHANGE: $previousOwner -> $owner, currentHandler=$activeEventHandlerOwnerId")
                        }

                        generationOwnerId = owner
                        generationContentType = contentType
                        lastStageChangeAt = 0L
                        _generationState.value = GenerationState(
                            isGenerating = true,
                            promptId = promptId,
                            progress = 0,
                            maxProgress = 100,
                            ownerId = owner,
                            contentType = contentType,
                            stage = ProgressStage.PREPARE,
                            startedAt = System.currentTimeMillis()
                        )

                        // If handler doesn't match new owner, clear it so correct screen can register
                        if (activeEventHandlerOwnerId != null && activeEventHandlerOwnerId != owner) {
                            DebugLogger.w(TAG, "Handler mismatch after ExecutionStart: handler=$activeEventHandlerOwnerId, newOwner=$owner - clearing handler")
                            activeEventHandler = null
                            activeEventHandlerOwnerId = null
                        }
                    }
                }
            }
            is WebSocketMessage.Executing -> {
                val forUs = message.promptId == null || message.promptId == currentState.promptId
                if (currentState.isGenerating && forUs) {
                    val classType = NodeTypeIndex.classOf(
                        message.promptId ?: currentState.promptId, message.node
                    )
                    advanceStage(ProgressStage.of(classType))
                }
            }
            is WebSocketMessage.ExecutionCached -> {}
            is WebSocketMessage.Status -> {}
            is WebSocketMessage.Unknown -> {}
        }
    }

    private var lastStageChangeAt = 0L

    /**
     * A graph is mostly constants and switches that finish in milliseconds, so naming every node
     * would turn the line into a slide show. Two rules keep it readable: a node we cannot name
     * never overwrites a stage we know, and a stage that would replace another within a quarter
     * second is dropped.
     */
    private fun advanceStage(next: ProgressStage) {
        if (next == ProgressStage.UNKNOWN) return
        val state = _generationState.value
        if (!state.isGenerating || state.stage == next) return
        val now = System.currentTimeMillis()
        if (state.stage != ProgressStage.UNKNOWN && now - lastStageChangeAt < STAGE_MIN_DWELL_MS) return
        lastStageChangeAt = now
        _generationState.value = state.copy(stage = next)
    }

    /**
     * Get the ComfyUI client instance
     */
    fun getClient(): ComfyUIClient? = comfyUIClient

    /**
     * Get the hostname from ConnectionManager
     */
    fun getHostname(): String = ConnectionManager.hostname

    /**
     * Get the port from ConnectionManager
     */
    fun getPort(): Int = ConnectionManager.port

    /**
     * Register an event handler for a specific owner (screen).
     * Only the owner that started generation will receive events.
     * If there's a buffered preview or pending completion, it will be delivered immediately.
     */
    fun registerEventHandler(ownerId: String, handler: (GenerationEvent) -> Unit) {
        val state = _generationState.value
        val executingOwner = state.ownerId

        // Check if this owner has pending buffered events that need delivery
        val hasPendingEvents = pendingCompletions.containsKey(ownerId) ||
                               bufferedPreviews.containsKey(ownerId) ||
                               pendingClearPreviews.contains(ownerId)

        DebugLogger.d(TAG, "registerEventHandler: ownerId=$ownerId, executingOwner=$executingOwner, " +
                "generationOwnerId=$generationOwnerId, currentHandler=$activeEventHandlerOwnerId, hasPending=$hasPendingEvents")

        // Allow registration if:
        // 1. This owner matches the executing job, OR
        // 2. No generation is active, OR
        // 3. This owner has pending buffered events that need delivery
        if (ownerId == executingOwner || executingOwner == null || hasPendingEvents) {
            DebugLogger.d(TAG, "Handler registration ACCEPTED for $ownerId (reason: ${
                when {
                    ownerId == executingOwner -> "matches executing"
                    executingOwner == null -> "no active generation"
                    hasPendingEvents -> "has pending events"
                    else -> "unknown"
                }
            })")
            activeEventHandler = handler
            activeEventHandlerOwnerId = ownerId

            // Deliver pending ClearPreviewForResume if exists for THIS owner (must be before preview delivery)
            if (pendingClearPreviews.remove(ownerId)) {
                DebugLogger.d(TAG, "Delivering buffered ClearPreviewForResume to $ownerId")
                viewModelScope.launch { handler(GenerationEvent.ClearPreviewForResume) }
            }

            // Deliver buffered preview if exists for THIS owner
            bufferedPreviews.remove(ownerId)?.let { bitmap ->
                DebugLogger.d(TAG, "Delivering buffered PreviewImage to $ownerId")
                viewModelScope.launch { handler(GenerationEvent.PreviewImage(bitmap)) }
            }

            // Deliver pending completion if exists for THIS owner
            pendingCompletions.remove(ownerId)?.let { completion ->
                DebugLogger.d(TAG, "Delivering buffered ${completion::class.simpleName} to $ownerId")
                viewModelScope.launch { handler(completion) }
            }

            // If generation is active but completion wasn't detected yet,
            // trigger a server check in case completion happened before handler registered
            if (state.isGenerating && !pendingCompletions.containsKey(ownerId) && ownerId == state.ownerId) {
                DebugLogger.d(TAG, "Handler registered during active generation, verifying status")
                checkServerForCompletion()
            }
        } else {
            DebugLogger.d(TAG, "Handler registration REJECTED for $ownerId (executing owner is $executingOwner)")
        }
    }

    /**
     * Unregister the event handler for a specific owner.
     * Only clears the handler if this owner actually registered it.
     * If the owner started generation that's still running, keep handler active for pending completion.
     */
    fun unregisterEventHandler(ownerId: String) {
        val state = _generationState.value
        val executingOwner = state.ownerId

        DebugLogger.d(TAG, "unregisterEventHandler: ownerId=$ownerId, executingOwner=$executingOwner, " +
                "currentHandler=$activeEventHandlerOwnerId, isGenerating=${state.isGenerating}")

        // Only clear handler if THIS owner registered it
        if (activeEventHandlerOwnerId != ownerId) {
            DebugLogger.d(TAG, "Unregister SKIPPED: another owner ($activeEventHandlerOwnerId) registered the handler")
            return  // Another screen registered the handler, don't clear it
        }

        // Don't clear handler if this owner's job is currently executing
        if (executingOwner == ownerId && state.isGenerating) {
            DebugLogger.d(TAG, "Unregister SKIPPED: $ownerId's job is still executing")
            return
        }

        DebugLogger.d(TAG, "Unregister ACCEPTED: clearing handler for $ownerId")
        activeEventHandler = null
        activeEventHandlerOwnerId = null
    }

    /**
     * Dispatch event to active handler, falling back to SharedFlow.
     * If no handler is active OR handler doesn't match executing owner, buffer events.
     * Handler is invoked on the main thread to ensure proper UI state updates.
     */
    private fun dispatchEvent(event: GenerationEvent) {
        val handler = activeEventHandler
        val state = _generationState.value
        val eventName = event::class.simpleName
        val executingOwner = state.ownerId

        // Check if handler matches executing job owner
        val handlerMatchesExecuting = activeEventHandlerOwnerId == executingOwner

        // Only log non-preview events to avoid spam
        if (event !is GenerationEvent.PreviewImage) {
            DebugLogger.d(TAG, "dispatchEvent: $eventName, executingOwner=$executingOwner, " +
                    "handlerOwner=$activeEventHandlerOwnerId, hasHandler=${handler != null}, matches=$handlerMatchesExecuting")
        }

        // Only dispatch to handler if it matches the executing job's owner
        if (handler != null && handlerMatchesExecuting) {
            viewModelScope.launch { handler(event) }
            if (event is GenerationEvent.PreviewImage && executingOwner != null) {
                bufferedPreviews.remove(executingOwner)
            }
        } else {
            // No matching handler - buffer for later delivery to the correct owner
            // Use executingOwner as the key so this owner can retrieve it later
            if (executingOwner != null) {
                if (event !is GenerationEvent.PreviewImage) {
                    if (handler != null && !handlerMatchesExecuting) {
                        DebugLogger.d(TAG, "Handler mismatch, buffering $eventName for $executingOwner (current handler: $activeEventHandlerOwnerId)")
                    } else {
                        DebugLogger.d(TAG, "No handler, buffering $eventName for $executingOwner")
                    }
                }
                when (event) {
                    is GenerationEvent.PreviewImage -> bufferedPreviews[executingOwner] = event.bitmap
                    is GenerationEvent.ImageGenerated, is GenerationEvent.VideoGenerated -> {
                        pendingCompletions[executingOwner] = event
                        // Immediately fetch and store the result to ensure persistence
                        // even if the user doesn't return to this screen before app is killed
                        fetchAndStoreResultImmediately(event, executingOwner)
                    }
                    is GenerationEvent.ClearPreviewForResume -> pendingClearPreviews.add(executingOwner)
                    else -> {}
                }
            }
        }
        // Also emit to SharedFlow for backwards compatibility
        viewModelScope.launch { _events.emit(event) }
    }

    /**
     * Immediately fetch and store the generated result to ensure persistence.
     * Called when a completion event is dispatched but no handler is registered.
     * This ensures results are stored in MediaStateHolder even if the app is killed
     * before the user returns to the generating screen.
     */
    private fun fetchAndStoreResultImmediately(event: GenerationEvent, ownerId: String) {
        val client = comfyUIClient ?: run {
            DebugLogger.w(TAG, "Cannot fetch immediately: no client")
            return
        }
        val context = applicationContext ?: run {
            DebugLogger.w(TAG, "Cannot fetch immediately: no context")
            return
        }

        when (event) {
            is GenerationEvent.ImageGenerated -> {
                val mediaKey = when (ownerId) {
                    "TEXT_TO_IMAGE" -> MediaStateHolder.MediaKey.TtiPreview
                    "IMAGE_TO_IMAGE" -> MediaStateHolder.MediaKey.ItiPreview
                    else -> {
                        DebugLogger.w(TAG, "Unknown image owner: $ownerId")
                        return
                    }
                }
                DebugLogger.i(TAG, "Fetching image immediately for $ownerId (promptId=${Obfuscator.promptId(event.promptId)})")
                fetchAndStoreImage(client, context, event.promptId, mediaKey)
            }
            is GenerationEvent.VideoGenerated -> {
                val filePrefix = when (ownerId) {
                    "TEXT_TO_VIDEO" -> VideoUtils.FilePrefix.TEXT_TO_VIDEO
                    "IMAGE_TO_VIDEO" -> VideoUtils.FilePrefix.IMAGE_TO_VIDEO
                    // The H3 workspace names its own owner, so it fell into the else branch and
                    // returned: those videos were never pulled down when they finished.
                    "H3_WORKSPACE" -> VideoUtils.FilePrefix.H3_WORKSPACE
                    else -> {
                        DebugLogger.w(TAG, "Unknown video owner: $ownerId")
                        return
                    }
                }
                DebugLogger.i(TAG, "Fetching video immediately for $ownerId (promptId=${Obfuscator.promptId(event.promptId)})")
                VideoUtils.fetchVideoFromHistory(context, client, event.promptId, filePrefix) { uri ->
                    if (uri != null) {
                        DebugLogger.i(TAG, "Immediate video fetch successful for $ownerId")
                    } else {
                        DebugLogger.w(TAG, "Immediate video fetch failed for $ownerId")
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Fetch image from server and store in MediaStateHolder.
     */
    private fun fetchAndStoreImage(
        client: ComfyUIClient,
        context: Context,
        promptId: String,
        mediaKey: MediaStateHolder.MediaKey
    ) {
        client.fetchHistory(promptId) { historyJson ->
            if (historyJson != null) {
                try {
                    val promptData = historyJson.optJSONObject(promptId)
                    val outputs = promptData?.optJSONObject("outputs")

                    outputs?.keys()?.forEach { nodeId ->
                        val nodeOutput = outputs.getJSONObject(nodeId)
                        val images = nodeOutput.optJSONArray("images")

                        if (images != null && images.length() > 0) {
                            val imageInfo = images.getJSONObject(0)
                            val filename = imageInfo.optString("filename")
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            client.fetchImage(filename, subfolder, type) { bitmap, failureType ->
                                if (bitmap != null) {
                                    MediaStateHolder.putBitmap(mediaKey, bitmap, context)
                                    DebugLogger.i(TAG, "Immediate image fetch successful, stored to ${mediaKey::class.simpleName}")
                                } else if (failureType == ConnectionFailure.STALLED) {
                                    DebugLogger.w(TAG, "Immediate image fetch stalled")
                                    ConnectionManager.showConnectionAlert(context, failureType)
                                } else {
                                    DebugLogger.w(TAG, "Immediate image fetch failed: bitmap is null")
                                }
                            }
                            return@fetchHistory
                        }
                    }
                    DebugLogger.w(TAG, "Immediate image fetch: no images in history output")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Immediate image fetch failed: ${e.message}")
                }
            } else {
                DebugLogger.w(TAG, "Immediate image fetch: history is null")
            }
        }
    }

    // Content type for current generation (used in submitWorkflow)
    private var generationContentType: ContentType = ContentType.IMAGE

    /**
     * Start generation with the given workflow JSON
     * @param workflowJson The workflow JSON to submit
     * @param ownerId The ID of the screen/ViewModel that owns this generation (e.g., "TEXT_TO_IMAGE")
     * @param contentType The type of content being generated (IMAGE or VIDEO)
     * @param onResult Callback with result
     */
    fun startGeneration(
        workflowJson: String,
        ownerId: String,
        contentType: ContentType = ContentType.IMAGE,
        front: Boolean = false,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        DebugLogger.i(TAG, "Starting generation (owner: $ownerId, type: $contentType${if (front) ", front" else ""})")
        comfyUIClient ?: run {
            DebugLogger.e(TAG, "Cannot start generation: client not initialized")
            onResult(false, null, applicationContext?.getString(R.string.error_client_not_initialized) ?: "Client not initialized")
            return
        }

        val context = applicationContext ?: run {
            DebugLogger.e(TAG, "Cannot start generation: no application context")
            onResult(false, null, "No application context")
            return
        }

        // Ensure connection before starting - will try to reconnect if needed
        // Dialog is shown by ensureConnection if reconnection fails
        ConnectionManager.ensureConnection(context) { connected ->
            if (connected) {
                // Submitting is the moment progress starts to matter. If start-up could not open
                // the socket, this is the last chance to get it back before the job runs blind.
                ConnectionManager.ensureWebSocket()
                // Owner travels with the request instead of through a shared field: a second
                // submission used to overwrite the first one's label while it was still in
                // flight, so each page ended up tracking the other one's job.
                submitWorkflow(workflowJson, ownerId, contentType, front, onResult)
            } else {
                // Connection failed - dialog already shown by ensureConnection
                // Don't show Toast (no error message) to avoid duplicate notifications
                onResult(false, null, null)
            }
        }
    }

    /**
     * Submit workflow to server
     */
    private fun submitWorkflow(
        workflowJson: String,
        ownerId: String,
        contentType: ContentType,
        front: Boolean = false,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        val client = comfyUIClient ?: return

        // Resolve server-side names (folder separators) and refuse unresolved {{placeholders}}.
        val preflight = sh.hnet.comfychair.workflow.SubmitPreflight.prepare(workflowJson) { classType, input ->
            sh.hnet.comfychair.connection.ConnectionManager.nodeTypeRegistry
                .getInputDefinition(classType, input)?.options
        }
        if (!preflight.ok) {
            val detail = preflight.issues.joinToString("；") { it.toString() }
            DebugLogger.w(TAG, "Pre-submit check failed: $detail")
            onResult(false, null, "提交前检查未通过：$detail")
            return
        }
        if (preflight.rewrites.isNotEmpty())
            DebugLogger.i(TAG, "Pre-submit normalised: " + preflight.rewrites.joinToString("；"))

        DebugLogger.d(TAG, "Submitting workflow to server")
        // Read the prompt off what we are actually sending: our own jobs then show a prompt even
        // when the server's queue listing carries none.
        val jobPrompt = sh.hnet.comfychair.queue.JobPrompts.extract(
            runCatching { org.json.JSONObject(workflowJson) }.getOrNull()
        )
        val mainHandler = Handler(Looper.getMainLooper())
        pendingSubmitOwner = ownerId
        pendingSubmitContentType = contentType
        client.submitPrompt(preflight.prompt, front) { success, promptId, errorMessage, failureType ->
            // Whatever happened, this submission is no longer in flight.
            if (pendingSubmitOwner == ownerId) pendingSubmitOwner = null
            if (success && promptId != null) {
                DebugLogger.i(TAG, "Workflow submitted successfully (promptId: ${Obfuscator.promptId(promptId)})")

                // Register job with JobRegistry for queue tracking
                JobRegistry.registerJob(promptId, ownerId, contentType, jobPrompt)
                // Save JobRegistry state for persistence
                applicationContext?.let { ctx -> JobRegistry.saveState(ctx) }
                generationOwnerId = ownerId
                generationContentType = contentType

                // Determine if we should set up GenerationState now
                // There's a race condition: ExecutionStart might fire before we register the job.
                // Check both:
                // 1. Nothing executing (first job or queue was empty)
                // 2. This job is already executing (race: ExecutionStart fired before registration)
                val queueState = JobRegistry.queueState.value
                val isThisJobExecuting = queueState.executingPromptId == promptId
                val shouldSetState = isThisJobExecuting || !queueState.isExecuting

                if (shouldSetState) {
                    // ExecutionStart can land before this callback. Rebuilding the state from
                    // scratch then wiped the stage back to 处理中 and the clock to zero, which is
                    // exactly what the bar was supposed to stop doing.
                    val existing = _generationState.value
                    val carry = existing.isGenerating && existing.promptId == promptId
                    _generationState.value = GenerationState(
                        isGenerating = true,
                        promptId = promptId,
                        progress = 0,
                        maxProgress = 100,
                        ownerId = ownerId,
                        contentType = contentType,
                        stage = if (carry) existing.stage else ProgressStage.PREPARE,
                        startedAt = if (carry && existing.startedAt > 0L) existing.startedAt
                        else System.currentTimeMillis()
                    )
                    // Save state immediately after starting
                    applicationContext?.let { saveGenerationState(it) }
                }
                mainHandler.post { onResult(true, promptId, null) }
            } else {
                DebugLogger.e(TAG, "Workflow submission failed: $errorMessage")
                // For authentication errors, show the connection alert dialog instead of Toast
                if (failureType == ConnectionFailure.AUTHENTICATION) {
                    mainHandler.post {
                        applicationContext?.let { ctx ->
                            ConnectionManager.showConnectionAlert(ctx, ConnectionFailure.AUTHENTICATION)
                        }
                    }
                } else {
                    mainHandler.post { onResult(false, null, errorMessage) }
                }
            }
        }
    }

    /**
     * Cancel the current generation
     */
    fun cancelGeneration(onResult: (success: Boolean) -> Unit) {
        DebugLogger.i(TAG, "Cancelling generation")
        val client = comfyUIClient ?: run {
            DebugLogger.w(TAG, "Cannot cancel: client not initialized")
            onResult(false)
            return
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val promptId = _generationState.value.promptId

        fun finish(success: Boolean) {
            if (!success) DebugLogger.w(TAG, "Cancel did not reach the server")
            promptId?.let { JobRegistry.removeJob(it) }
            applicationContext?.let { ctx -> JobRegistry.saveState(ctx) }
            resetGenerationState()
            dispatchEvent(GenerationEvent.GenerationCancelled)
            mainHandler.post { onResult(success) }
        }

        if (promptId == null) {
            // Nothing tracked: the only safe action is to stop whatever runs now, and the UI
            // only offers this when the running job is ours.
            client.interruptExecution { success -> finish(success) }
            return
        }

        // Two different things can be true at once, and interrupt alone only handles one of them:
        // a queued job kept starting minutes after the user "cancelled". Delete it from the queue
        // first, then interrupt only if it is the one actually running -- interrupting blindly
        // stops whatever the server is doing, including another device's job.
        client.deleteQueueItems(listOf(promptId)) { deleted ->
            DebugLogger.d(TAG, "Queue delete for ${Obfuscator.promptId(promptId)}: $deleted")
            val runningOurs = JobRegistry.queueState.value.executingPromptId == promptId
            if (runningOurs) {
                client.interruptExecution { interrupted -> finish(deleted || interrupted) }
            } else {
                finish(deleted)
            }
        }
    }

    /**
     * Complete generation (called after image is fetched)
     * @param promptId Optional promptId to verify - only resets if matches current generation.
     *                 This prevents late completion calls from clearing state for a different job.
     */
    fun completeGeneration(promptId: String? = null) {
        val currentPromptId = _generationState.value.promptId

        // If promptId specified, only reset if it matches current generation
        // This prevents race condition where a late completion clears a new job's state
        if (promptId != null && promptId != currentPromptId) {
            DebugLogger.d(TAG, "Ignoring completion for old job (${Obfuscator.promptId(promptId)}) - current is (${Obfuscator.promptId(currentPromptId ?: "")})")
            return
        }

        DebugLogger.i(TAG, "Generation completed")
        resetGenerationState()
    }

    /**
     * Reset generation state
     * Clears transient buffers (previews, clear flags) but preserves pending completions
     * since they need to survive until the handler registers to receive them
     */
    private fun resetGenerationState() {
        stopCompletionPolling()

        // Clear transient buffers (previews can be discarded, completion events cannot)
        val currentOwner = generationOwnerId
        if (currentOwner != null) {
            // DON'T clear pendingCompletions - they must survive until handler retrieves them
            bufferedPreviews.remove(currentOwner)
            pendingClearPreviews.remove(currentOwner)
        }

        _generationState.value = GenerationState()
        generationOwnerId = null
        // Clear persisted state to prevent stale "generating" state on app restart
        applicationContext?.let { saveGenerationState(it) }
    }

    /**
     * Start polling for generation completion.
     * Used when resuming a generation after app restart, since WebSocket
     * won't receive progress updates for jobs submitted with a different client ID.
     */
    private fun startCompletionPolling(promptId: String, contentType: ContentType) {
        if (completionPollingJob?.isActive == true) return

        completionPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(COMPLETION_POLLING_INTERVAL_MS)
                if (!isActive) break
                pollForCompletion(promptId, contentType)
            }
        }
    }

    /**
     * Stop polling for generation completion
     */
    private fun stopCompletionPolling() {
        completionPollingJob?.cancel()
        completionPollingJob = null
    }

    /**
     * Poll the server to check if generation has completed
     */
    private fun pollForCompletion(promptId: String, contentType: ContentType) {
        val client = comfyUIClient ?: return

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson == null) return@fetchHistory

            val outputs = historyJson.optJSONObject(promptId)?.optJSONObject("outputs")
            if (outputs != null && outputs.length() > 0) {
                val event = if (contentType == ContentType.VIDEO) {
                    GenerationEvent.VideoGenerated(promptId)
                } else {
                    GenerationEvent.ImageGenerated(promptId)
                }
                dispatchEvent(event)
                completeGeneration(promptId)
                // Gallery refresh handled by JobRegistry.markCompleted() via WebSocket event
            }
        }
    }

    /**
     * Save generation state to SharedPreferences
     */
    fun saveGenerationState(context: Context) {
        val state = _generationState.value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(PREF_IS_GENERATING, state.isGenerating)
            putString(PREF_CURRENT_PROMPT_ID, state.promptId)
            putString(PREF_OWNER_ID, state.ownerId)
            putString(PREF_CONTENT_TYPE, state.contentType.name)
            apply()
        }
    }

    /**
     * Check server for generation completion.
     * Called when returning from background or reconnecting after connection loss.
     * Queries the server's history API to see if the generation completed while we were away.
     * If not in history, checks the queue to determine if still running or truly stale.
     */
    fun checkServerForCompletion(onResult: (completed: Boolean, promptId: String?) -> Unit = { _, _ -> }) {
        val state = _generationState.value
        if (!state.isGenerating || state.promptId == null) {
            onResult(false, null)
            return
        }

        val client = comfyUIClient ?: run {
            onResult(false, null)
            return
        }

        client.fetchHistory(state.promptId) { historyJson ->
            if (historyJson == null) {
                // History fetch failed - check queue to detect stale state
                checkIfPromptInQueue(client, state.promptId) { inQueue ->
                    if (inQueue) {
                        dispatchEvent(GenerationEvent.ClearPreviewForResume)
                        startCompletionPolling(state.promptId, state.contentType)
                        onResult(false, state.promptId)
                    } else {
                        // Not in queue - retry history check in case it just completed
                        client.fetchHistory(state.promptId) { retryHistoryJson ->
                            val retryOutputs = retryHistoryJson?.optJSONObject(state.promptId)?.optJSONObject("outputs")
                            if (retryOutputs != null && retryOutputs.length() > 0) {
                                dispatchCompletionEvent(state.promptId, state.contentType)
                                onResult(true, state.promptId)
                            } else {
                                resetGenerationState()
                                onResult(false, null)
                            }
                        }
                    }
                }
                return@fetchHistory
            }

            val outputs = historyJson.optJSONObject(state.promptId)?.optJSONObject("outputs")
            if (outputs != null && outputs.length() > 0) {
                dispatchCompletionEvent(state.promptId, state.contentType)
                onResult(true, state.promptId)
            } else {
                // Not in history with outputs - check if still in queue
                checkIfPromptInQueue(client, state.promptId) { inQueue ->
                    if (inQueue) {
                        dispatchEvent(GenerationEvent.ClearPreviewForResume)
                        startCompletionPolling(state.promptId, state.contentType)
                        onResult(false, state.promptId)
                    } else {
                        resetGenerationState()
                        onResult(false, null)
                    }
                }
            }
        }
    }

    /**
     * Helper to dispatch completion event and clean up state
     */
    private fun dispatchCompletionEvent(promptId: String, contentType: ContentType) {
        val event = if (contentType == ContentType.VIDEO) {
            GenerationEvent.VideoGenerated(promptId)
        } else {
            GenerationEvent.ImageGenerated(promptId)
        }
        dispatchEvent(event)
        completeGeneration(promptId)  // Pass promptId to prevent clearing state for a different job
        // Clear JobRegistry state - ensures executingOwnerId is reset even when
        // WebSocket events were missed (app was in background during completion)
        JobRegistry.markCompleted(promptId)
    }

    /**
     * Check if a prompt is in the ComfyUI queue (either running or pending)
     */
    private fun checkIfPromptInQueue(
        client: ComfyUIClient,
        promptId: String,
        callback: (inQueue: Boolean) -> Unit
    ) {
        client.fetchQueue { queueJson ->
            if (queueJson == null) {
                // Couldn't fetch queue, assume still running to be safe
                callback(true)
                return@fetchQueue
            }

            // Check queue_running array: [[number, prompt_id, {...}], ...]
            val queueRunning = queueJson.optJSONArray("queue_running")
            if (queueRunning != null) {
                for (i in 0 until queueRunning.length()) {
                    val entry = queueRunning.optJSONArray(i)
                    if (entry != null && entry.length() > 1 && entry.optString(1) == promptId) {
                        callback(true)
                        return@fetchQueue
                    }
                }
            }

            // Check queue_pending array: [[number, prompt_id, {...}], ...]
            val queuePending = queueJson.optJSONArray("queue_pending")
            if (queuePending != null) {
                for (i in 0 until queuePending.length()) {
                    val entry = queuePending.optJSONArray(i)
                    if (entry != null && entry.length() > 1 && entry.optString(1) == promptId) {
                        callback(true)
                        return@fetchQueue
                    }
                }
            }

            callback(false)
        }
    }

    /**
     * Restore generation state from SharedPreferences
     */
    private fun restoreGenerationState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isGenerating = prefs.getBoolean(PREF_IS_GENERATING, false)
        val promptId = prefs.getString(PREF_CURRENT_PROMPT_ID, null)
        val ownerId = prefs.getString(PREF_OWNER_ID, null)
        val contentTypeName = prefs.getString(PREF_CONTENT_TYPE, null)
        val contentType = try {
            contentTypeName?.let { ContentType.valueOf(it) } ?: ContentType.IMAGE
        } catch (e: IllegalArgumentException) {
            ContentType.IMAGE
        }

        if (isGenerating && promptId != null) {
            generationOwnerId = ownerId
            generationContentType = contentType
            _generationState.value = GenerationState(
                isGenerating = true,
                promptId = promptId,
                ownerId = ownerId,
                contentType = contentType
            )
        }
    }

    /**
     * Logout - close connections and reset state
     */
    fun logout() {
        // Clear JobRegistry persisted state
        applicationContext?.let { JobRegistry.clearPersistedState(it) }

        // Logout from server (sets flag to prevent auto-reconnect, clears all caches including JobRegistry)
        ConnectionManager.logout()

        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        resetGenerationState()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketMessageJob?.cancel()
        webSocketStateJob?.cancel()
        // WebSocket is managed by ConnectionManager, don't close it here
        // as other ViewModels may still need it
    }
}
