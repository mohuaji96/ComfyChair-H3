package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.MetadataStore
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.MetadataParser
import sh.hnet.comfychair.util.Mp4MetadataExtractor
import sh.hnet.comfychair.util.PngMetadataExtractor
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import java.io.File
import kotlin.coroutines.resume

/**
 * Viewer mode
 */
enum class ViewerMode {
    GALLERY,  // Launched from gallery, supports navigation
    SINGLE    // Launched from generation screen, single item only
}

/**
 * Represents an item in the media viewer
 */
@Immutable
data class MediaViewerItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0,
    /** The server has no history entry for it, so deleting is a cache operation. */
    val localOnly: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("promptId", promptId)
            put("filename", filename)
            put("subfolder", subfolder)
            put("type", type)
            put("isVideo", isVideo)
            put("index", index)
            put("localOnly", localOnly)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MediaViewerItem {
            return MediaViewerItem(
                promptId = json.optString("promptId", ""),
                filename = json.optString("filename", ""),
                subfolder = json.optString("subfolder", ""),
                type = json.optString("type", "output"),
                isVideo = json.optBoolean("isVideo", false),
                index = json.optInt("index", 0),
                localOnly = json.optBoolean("localOnly", false)
            )
        }

        fun listToJson(items: List<MediaViewerItem>): String {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(json: String): List<MediaViewerItem> {
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    fromJson(array.getJSONObject(i))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * UI state for the media viewer.
 * Caching is handled by MediaCache singleton - no local cache maps needed.
 */
@Stable
data class MediaViewerUiState(
    val mode: ViewerMode = ViewerMode.GALLERY,
    val items: List<MediaViewerItem> = emptyList(),
    val currentIndex: Int = 0,
    val isUiVisible: Boolean = true,
    val isLoading: Boolean = false,
    val currentBitmap: Bitmap? = null,
    val currentVideoUri: Uri? = null
) {
    val currentItem: MediaViewerItem?
        get() = items.getOrNull(currentIndex)

    val totalCount: Int
        get() = items.size

    val canNavigateBack: Boolean
        get() = mode == ViewerMode.GALLERY && currentIndex > 0

    val canNavigateForward: Boolean
        get() = mode == ViewerMode.GALLERY && currentIndex < items.size - 1
}

/**
 * Events emitted by media viewer operations
 */
sealed class MediaViewerEvent {
    data class ShowToast(val messageResId: Int) : MediaViewerEvent()
    data object ItemDeleted : MediaViewerEvent()
    data object Close : MediaViewerEvent()
}

/**
 * ViewModel for the MediaViewer screen
 */
class MediaViewerViewModel : ViewModel() {

    private var applicationContext: Context? = null

    private val _uiState = MutableStateFlow(MediaViewerUiState())
    val uiState: StateFlow<MediaViewerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MediaViewerEvent>()
    val events: SharedFlow<MediaViewerEvent> = _events.asSharedFlow()

    // Metadata state. Only successful reads are remembered, so one miss cannot poison an item
    // for the rest of the session.
    private val cachedMetadata = mutableMapOf<Int, GenerationMetadata>()
    private val metadataAttempts = mutableMapOf<Int, Int>()
    private var metadataJob: Job? = null
    private val _currentMetadata = MutableStateFlow<GenerationMetadata?>(null)
    val currentMetadata: StateFlow<GenerationMetadata?> = _currentMetadata.asStateFlow()

    private val _isLoadingMetadata = MutableStateFlow(false)
    val isLoadingMetadata: StateFlow<Boolean> = _isLoadingMetadata.asStateFlow()

    /** Why the last attempt produced nothing, shown under the spinner instead of a dead end. */
    private val _metadataError = MutableStateFlow<String?>(null)
    val metadataError: StateFlow<String?> = _metadataError.asStateFlow()

    /** Storage scope for parameters: the server id when known, else host:port. */
    private var storeScope: String = "local"

    // Track whether any items were deleted during this session
    private val _hasDeletedItems = MutableStateFlow(false)
    val hasDeletedItems: StateFlow<Boolean> = _hasDeletedItems.asStateFlow()

    fun initialize(
        context: Context,
        hostname: String,
        port: Int,
        mode: ViewerMode,
        items: List<MediaViewerItem>,
        initialIndex: Int,
        singleBitmap: Bitmap? = null,
        singleVideoUri: Uri? = null
    ) {
        applicationContext = context.applicationContext
        storeScope = ConnectionManager.currentServerId ?: "$hostname:$port"

        _uiState.value = MediaViewerUiState(
            mode = mode,
            items = items,
            currentIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            currentBitmap = singleBitmap,
            currentVideoUri = singleVideoUri,
            isLoading = mode == ViewerMode.GALLERY && items.isNotEmpty()
        )

        // For gallery mode, set up priorities and load current item
        if (mode == ViewerMode.GALLERY && items.isNotEmpty()) {
            val currentIdx = _uiState.value.currentIndex
            updateCachePrioritiesForIndex(currentIdx)
            triggerPrefetchForIndex(currentIdx)
            preloadMetadataForIndices()
            loadCurrentItem()
        }
    }

    fun toggleUiVisibility() {
        _uiState.value = _uiState.value.copy(isUiVisible = !_uiState.value.isUiVisible)
    }

    fun setCurrentIndex(index: Int) {
        val state = _uiState.value
        if (index < 0 || index >= state.items.size) return

        // Clear metadata when navigating
        clearCurrentMetadata()

        val item = state.items[index]
        val key = item.toCacheKey()

        // Update image cache priorities based on new position
        updateCachePrioritiesForIndex(index)

        // Trigger prefetch IMMEDIATELY for adjacent items (before loading current)
        triggerPrefetchForIndex(index)

        // Pre-load metadata for current + adjacent items
        preloadMetadataForIndices()

        // Try to get from cache immediately (same approach for images and videos)
        val cachedBitmap = if (!item.isVideo) MediaCache.getBitmap(key) else null
        val cachedVideoUri = if (item.isVideo) MediaCache.getCachedVideoUri(key) else null

        if (!item.isVideo && cachedBitmap != null) {
            // Image is cached - show immediately
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = cachedBitmap,
                currentVideoUri = null,
                isLoading = false
            )
        } else if (item.isVideo && cachedVideoUri != null) {
            // Video is cached - show immediately
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = cachedVideoUri,
                isLoading = false
            )
        } else if (MediaCache.isPrefetchInProgress(key)) {
            // Prefetch is in progress - wait for it instead of starting new fetch
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            viewModelScope.launch {
                MediaCache.awaitPrefetchCompletion(key)
                showFromCache(item, key)
            }
        } else {
            // Content not cached - load from server
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            loadCurrentItem()
        }
    }

    /**
     * Show item from cache after prefetch completes.
     */
    private fun showFromCache(item: MediaViewerItem, key: MediaCacheKey) {
        if (item.isVideo) {
            val uri = MediaCache.getCachedVideoUri(key)
            _uiState.value = _uiState.value.copy(
                currentVideoUri = uri,
                isLoading = false
            )
        } else {
            val bitmap = MediaCache.getBitmap(key)
            _uiState.value = _uiState.value.copy(
                currentBitmap = bitmap,
                isLoading = false
            )
        }
    }

    /**
     * Update cache priorities based on current viewing position.
     * Ensures adjacent items have higher priority than distant items.
     */
    private fun updateCachePrioritiesForIndex(index: Int) {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val allKeys = state.items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(index, allKeys)
    }

    /**
     * Trigger prefetch for items around the given index.
     */
    private fun triggerPrefetchForIndex(index: Int) {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val prefetchItems = state.items.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
        }

        MediaCache.prefetchAround(index, prefetchItems)
    }

    /** Convert MediaViewerItem to MediaCacheKey */
    private fun MediaViewerItem.toCacheKey() = MediaCacheKey(promptId, filename)

    fun navigateNext() {
        val state = _uiState.value
        if (state.canNavigateForward) {
            setCurrentIndex(state.currentIndex + 1)
        }
    }

    fun navigatePrevious() {
        val state = _uiState.value
        if (state.canNavigateBack) {
            setCurrentIndex(state.currentIndex - 1)
        }
    }

    private fun loadCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        val context = applicationContext ?: return

        val key = item.toCacheKey()

        // Check if already cached
        if (item.isVideo) {
            MediaCache.getCachedVideoUri(key)?.let { uri ->
                _uiState.value = state.copy(currentVideoUri = uri, isLoading = false)
                return
            }
        } else {
            MediaCache.getBitmap(key)?.let { bitmap ->
                _uiState.value = state.copy(currentBitmap = bitmap, isLoading = false)
                return
            }
        }

        _uiState.value = state.copy(isLoading = true)

        viewModelScope.launch {
            if (item.isVideo) {
                // Fetch video and create URI in one step
                val uri = MediaCache.fetchVideoUri(key, item.subfolder, item.type, context)
                _uiState.value = _uiState.value.copy(
                    currentVideoUri = uri,
                    isLoading = false
                )
            } else {
                val bitmap = MediaCache.fetchImage(key, item.subfolder, item.type)
                _uiState.value = _uiState.value.copy(
                    currentBitmap = bitmap,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Load metadata for the current item.
     *
     * Order matters: memory, then the copy on this phone (a stored record or the cached file
     * itself), and only then the server. The old code went straight to the network and pulled the
     * whole image or video down, which is why opening this sheet on a slow connection spun
     * forever -- and why a ComfyUI restart made old items look like they had no parameters.
     */
    fun loadMetadata(force: Boolean = false) {
        val index = _uiState.value.currentIndex
        val item = _uiState.value.items.getOrNull(index)

        if (!force && cachedMetadata.containsKey(index)) {
            _currentMetadata.value = cachedMetadata[index]
            _isLoadingMetadata.value = false
            _metadataError.value = null
            return
        }
        // A file with nothing to give is not retried on every open; the button in the sheet is the
        // deliberate way back in.
        if (!force && (metadataAttempts[index] ?: 0) >= MAX_AUTO_ATTEMPTS) {
            _currentMetadata.value = null
            _isLoadingMetadata.value = false
            _metadataError.value = "上次没有读到参数，点重试再试一次"
            return
        }

        metadataJob?.cancel()
        _isLoadingMetadata.value = true
        _metadataError.value = null
        metadataJob = viewModelScope.launch {
            var failure: String? = null
            val metadata = try {
                withTimeoutOrNull(METADATA_TIMEOUT_MS) { resolveMetadata(item) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = "读取失败：${error.javaClass.simpleName}"
                null
            }
            if (metadata == null && failure == null) failure = "这个文件里没有生成参数"
            if (metadata != null) {
                cachedMetadata[index] = metadata
                metadataAttempts.remove(index)
                _currentMetadata.value = metadata
            } else {
                metadataAttempts[index] = (metadataAttempts[index] ?: 0) + 1
                _currentMetadata.value = null
                _metadataError.value = failure
            }
            // Always cleared: an exception or a timeout used to leave the sheet spinning forever.
            _isLoadingMetadata.value = false
        }
    }

    /**
     * Clear metadata when navigating to a different item.
     */
    private fun clearCurrentMetadata() {
        _currentMetadata.value = null
        _metadataError.value = null
        _isLoadingMetadata.value = false
    }

    /** memory -> stored record -> file on this phone -> server. */
    private suspend fun resolveMetadata(item: MediaViewerItem?): GenerationMetadata? {
        if (item == null) return null
        val context = applicationContext ?: return null

        MetadataStore.get(context, storeScope, item.promptId, item.filename)?.let { return it }

        val local = withContext(Dispatchers.IO) {
            MediaCache.localFile(context, item.toCacheKey(), item.isVideo)
                ?.let { parseLocalFile(it, item.isVideo) }
        }
        if (local != null) {
            MetadataStore.put(context, storeScope, item.promptId, item.filename, local)
            return local
        }

        val remote = fetchRemoteMetadata(item) ?: return null
        MetadataStore.put(context, storeScope, item.promptId, item.filename, remote)
        return remote
    }

    /** Reads only the part of the file that can hold parameters, whole file only as a last resort. */
    private fun parseLocalFile(file: File, isVideo: Boolean): GenerationMetadata? = try {
        val slice = if (isVideo) tailBytes(file, TAIL_BYTES) else headBytes(file, HEAD_BYTES)
        parseBytes(slice, isVideo) ?: run {
            // Small files are cheap to read whole; a large video is not worth it here.
            if (!isVideo || file.length() <= FULL_READ_LIMIT) parseBytes(file.readBytes(), isVideo) else null
        }
    } catch (error: Exception) {
        DebugLogger.w(TAG, "Local metadata failed: ${error.javaClass.simpleName}")
        null
    }

    private fun headBytes(file: File, count: Int): ByteArray = file.inputStream().use { stream ->
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = stream.read(buffer, read, count - read)
            if (n <= 0) break
            read += n
        }
        if (read == count) buffer else buffer.copyOf(read)
    }

    private fun tailBytes(file: File, count: Int): ByteArray = java.io.RandomAccessFile(file, "r").use { raf ->
        val size = raf.length()
        val length = minOf(size, count.toLong()).toInt()
        if (length <= 0) return ByteArray(0)
        val buffer = ByteArray(length)
        raf.seek(size - length)
        raf.readFully(buffer)
        buffer
    }

    private fun parseBytes(bytes: ByteArray?, isVideo: Boolean): GenerationMetadata? {
        if (bytes == null || bytes.isEmpty()) return null
        val json = if (isVideo) {
            Mp4MetadataExtractor.extractPromptMetadataFromTail(bytes)
        } else {
            PngMetadataExtractor.extractPromptMetadata(bytes)
        }
        return json?.let { MetadataParser.parseWorkflowJson(it) }
    }

    private suspend fun fetchRemoteMetadata(item: MediaViewerItem): GenerationMetadata? {
        if (item.filename.isEmpty()) return null
        val client = ConnectionManager.clientOrNull ?: return null

        // The parameters live in the PNG's first chunks and in the MP4's trailing moov box, so
        // half a megabyte is normally enough -- a 40 MB clip never has to cross the network.
        val slice = suspendCancellableCoroutine<ByteArray?> { continuation ->
            client.fetchViewRange(
                item.filename, item.subfolder, item.type,
                length = if (item.isVideo) TAIL_BYTES else HEAD_BYTES,
                suffix = item.isVideo
            ) { bytes -> if (continuation.isActive) continuation.resume(bytes) }
        }
        parseBytes(slice, item.isVideo)?.let { return it }

        val full = suspendCancellableCoroutine<ByteArray?> { continuation ->
            client.fetchRawBytes(item.filename, item.subfolder, item.type) { bytes, _ ->
                if (continuation.isActive) continuation.resume(bytes)
            }
        }
        return parseBytes(full, item.isVideo)
    }

    /**
     * Pre-loads metadata for current and adjacent items in the background.
     * Called when navigating to have metadata ready before user opens sheet.
     * Mirrors the same indices as MediaCache.prefetchAround() for consistency.
     */
    private fun preloadMetadataForIndices() {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val currentIndex = state.currentIndex
        val itemCount = state.items.size

        // Same pattern as MediaCache.prefetchAround(): current ±1, ±2
        val indicesToPreload = listOf(
            currentIndex,      // Current item (highest priority)
            currentIndex - 1,  // Adjacent
            currentIndex + 1,
            currentIndex - 2,  // Nearby
            currentIndex + 2
        ).filter { it in 0 until itemCount }
         .filter { !cachedMetadata.containsKey(it) }

        indicesToPreload.forEach { idx ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val metadata = withTimeoutOrNull(METADATA_TIMEOUT_MS) {
                        resolveMetadata(_uiState.value.items.getOrNull(idx))
                    } ?: return@launch
                    // Only a real answer is remembered; a miss stays open for the next attempt.
                    cachedMetadata[idx] = metadata
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Silently fail - metadata will load on-demand if needed
                }
            }
        }
    }

    fun deleteCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        val client = ConnectionManager.clientOrNull
        // A cached-only item exists nowhere on the server: forget the file instead of asking.
        if (!item.localOnly && client == null) {
            viewModelScope.launch {
                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_delete_needs_connection))
            }
            return
        }

        viewModelScope.launch {
            val success = if (item.localOnly) {
                GalleryRepository.getInstance().forgetCached(item.promptId, item.filename)
                true
            } else withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine<Boolean> { continuation ->
                    client!!.deleteHistoryItem(item.promptId) { deleted ->
                        continuation.resumeWith(Result.success(deleted))
                    }
                }
            }

            if (success) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_history_item_deleted_success))

                // Mark that items were deleted (for result reporting)
                _hasDeletedItems.value = true

                // Evict from MediaCache (uses stable key, not index)
                MediaCache.evict(item.toCacheKey())

                // Sync deletion to GalleryRepository so Gallery UI updates immediately
                GalleryRepository.getInstance().removeItemLocally(item.promptId)

                // Get fresh state after async operation
                val currentState = _uiState.value
                val currentItems = currentState.items.toMutableList()

                // Make sure we're removing the correct item
                val actualIndexToRemove = currentItems.indexOfFirst {
                    it.promptId == item.promptId && it.filename == item.filename
                }

                if (actualIndexToRemove >= 0) {
                    currentItems.removeAt(actualIndexToRemove)
                }

                if (currentItems.isEmpty()) {
                    // No more items, close viewer
                    _events.emit(MediaViewerEvent.ItemDeleted)
                    _events.emit(MediaViewerEvent.Close)
                } else {
                    // Adjust index and show next/previous item
                    val newIndex = actualIndexToRemove.coerceIn(0, currentItems.size - 1)

                    // Clear metadata cache (still uses indices)
                    cachedMetadata.clear()
                    _currentMetadata.value = null

                    _uiState.value = currentState.copy(
                        items = currentItems,
                        currentIndex = newIndex,
                        currentBitmap = null,
                        currentVideoUri = null,
                        isLoading = true
                    )
                    _events.emit(MediaViewerEvent.ItemDeleted)
                    loadCurrentItem()
                }
            } else {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_history_item_delete))
            }
        }
    }

    fun saveCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value

        viewModelScope.launch {
            if (state.mode == ViewerMode.SINGLE) {
                // Save from current bitmap/video
                if (state.currentItem?.isVideo == true || state.currentVideoUri != null) {
                    saveVideoFromUri(context, state.currentVideoUri)
                } else {
                    saveBitmapToGallery(context, state.currentBitmap)
                }
            } else {
                // Gallery mode - fetch and save
                val item = state.currentItem ?: return@launch
                if (item.isVideo) {
                    saveVideoFromServer(context, item)
                } else {
                    saveImageFromServer(context, item)
                }
            }
        }
    }

    private suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_image))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_image_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    private suspend fun saveVideoFromUri(context: Context, videoUri: Uri?) {
        if (videoUri == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val videoBytes = context.contentResolver.openInputStream(videoUri)?.use {
                    it.readBytes()
                } ?: run {
                    _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
                    return@withContext
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        outputStream.write(videoBytes)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_video_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
            }
        }
    }

    private suspend fun saveImageFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        withContext(Dispatchers.IO) {
            // Try cache first, then fetch if needed
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

            if (bitmap == null) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_image))
                return@withContext
            }

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_image_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    private suspend fun saveVideoFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        withContext(Dispatchers.IO) {
            // Try cache first, then fetch if needed
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            if (videoBytes == null) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
                return@withContext
            }

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        outputStream.write(videoBytes)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_video_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
            }
        }
    }

    fun shareCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value

        viewModelScope.launch {
            if (state.mode == ViewerMode.SINGLE) {
                if (state.currentItem?.isVideo == true || state.currentVideoUri != null) {
                    shareVideoFromUri(context, state.currentVideoUri)
                } else {
                    shareBitmap(context, state.currentBitmap)
                }
            } else {
                val item = state.currentItem ?: return@launch
                if (item.isVideo) {
                    shareVideoFromServer(context, item)
                } else {
                    shareImageFromServer(context, item)
                }
            }
        }
    }

    private suspend fun shareBitmap(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_image))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val shareFile = File(context.cacheDir, "share_image.png")
                shareFile.outputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "image/png"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(
                        Intent.createChooser(shareIntent, context.getString(R.string.share_image))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_image))
            }
        }
    }

    private suspend fun shareVideoFromUri(context: Context, videoUri: Uri?) {
        if (videoUri == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            return
        }

        withContext(Dispatchers.Main) {
            try {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, videoUri)
                    type = "video/mp4"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    Intent.createChooser(shareIntent, context.getString(R.string.share_video))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            }
        }
    }

    private suspend fun shareImageFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        // Try cache first, then fetch if needed
        val bitmap = MediaCache.getBitmap(key)
            ?: MediaCache.fetchImage(key, item.subfolder, item.type)

        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_image))
            return
        }

        shareBitmap(context, bitmap)
    }

    private suspend fun shareVideoFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        // Try cache first, then fetch if needed
        val videoBytes = MediaCache.getVideoBytes(key)
            ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

        if (videoBytes == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val shareFile = File(context.cacheDir, "share_video.mp4")
                shareFile.writeBytes(videoBytes)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "video/mp4"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(
                        Intent.createChooser(shareIntent, context.getString(R.string.share_video))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            }
        }
    }
}

private const val METADATA_TIMEOUT_MS = 12_000L
private const val MAX_AUTO_ATTEMPTS = 2
private const val HEAD_BYTES = 512 * 1024
private const val TAIL_BYTES = 512 * 1024

/** Above this a local video is read from its ends only: the whole file would not fit comfortably. */
private const val FULL_READ_LIMIT = 8L * 1024 * 1024

private const val TAG = "MediaViewer"
