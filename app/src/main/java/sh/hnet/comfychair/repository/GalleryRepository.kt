package sh.hnet.comfychair.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.LocalCachePolicy
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * Repository for managing gallery data with background preloading and caching.
 * This is a singleton that persists across activities.
 */
class GalleryRepository private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Application context for accessing settings and cache
    private var applicationContext: Context? = null

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    /**
     * Initialize with application context.
     * Called when needed to access settings/cache.
     */
    fun initialize(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
    }

    private var assetDownloadJob: Job? = null

    /** Newest items worth keeping warm; the rest stay on the server until they are opened. */
    private val prefetchMaxItems = 30

    /** Serial downloads keep large videos from multiplying peak memory use. */
    private fun cacheGeneratedAssets(items: List<GalleryItem>) {
        val context = applicationContext ?: return
        if (MediaCache.isMemoryFirstMode() || assetDownloadJob?.isActive == true) return
        val server = ConnectionManager.currentServerId ?: return
        assetDownloadJob = scope.launch(Dispatchers.IO) {
            for (item in items.take(prefetchMaxItems)) {
                // A cache that keeps refilling evicts whatever the user is looking at, and an item
                // removed on purpose must stay removed.
                val limit = LocalCachePolicy.limitBytes(context)
                if (limit > 0L && MediaCache.diskUsageBytes(context) >= limit) return@launch
                if (LocalCachePolicy.isTombstoned(context, item.toCacheKey().keyString)) continue
                // A local-only entry would 404 three times per refresh; it loads on demand
                // instead, and a cached one needs nothing.
                if (item.localOnly) continue
                if (MediaCache.hasLocalCopy(context, item.toCacheKey(), item.isVideo)) continue
                for (attempt in 0 until 3) {
                    if (ConnectionManager.currentServerId != server || comfyUIClient == null) return@launch
                    if (MediaCache.isMemoryFirstMode()) return@launch
                    val cached = try {
                        if (item.isVideo) MediaCache.fetchVideoUri(item.toCacheKey(), item.subfolder, item.type, context) != null
                        else MediaCache.prefetchImage(item.toCacheKey(), item.subfolder, item.type)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DebugLogger.w(TAG, "Automatic cache download failed: ${error.javaClass.simpleName}")
                        false
                    }
                    if (cached) break
                    if (attempt < 2) delay(2_000L shl attempt)
                }
            }
        }
    }

    // Gallery data state
    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<GalleryItem>> = _galleryItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Manual refresh triggered by user (pull-to-refresh) - shows indicator
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // Any refresh in progress (manual or background)
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastRefreshTime = MutableStateFlow(0L)
    val lastRefreshTime: StateFlow<Long> = _lastRefreshTime.asStateFlow()

    // Periodic refresh job
    private var periodicRefreshJob: Job? = null

    // Track if initial load has been done
    private var hasLoadedOnce = false

    // Track items being deleted to filter them from refresh results
    private val pendingDeletions = mutableSetOf<String>()

    // ---- One refresh at a time; a request that arrives meanwhile is replayed ----
    private val refreshGate = Any()
    private var lastCompletionSignalAt = 0L
    private var refreshRunning = false
    private var refreshQueued = false
    private var lastRefreshStartedAt = 0L

    /**
     * Bumped every time the list is replaced on purpose. A background pass that fills in size
     * and shape reads the list, spends a while on disk, then writes the whole thing back; without
     * this a delete that happened in between was undone by that older snapshot -- the item came
     * back, and the resurrected list was saved to the local record too.
     */
    private val listVersion = java.util.concurrent.atomic.AtomicInteger(0)

    /** Prompts whose completion retry chain is still running, so a signal repeated does not stack. */
    private val completionsAwaiting: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    companion object {
        private const val TAG = "GalleryRepo"

        @Volatile
        private var instance: GalleryRepository? = null

        private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".gif", ".avi", ".mov")
        private const val PERIODIC_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        /** Floor for a screen-entry pull, so opening the gallery twice does not cost twice. */
        private const val ENTRY_REFRESH_GAP_MS = 3_000L
        /**
         * When to look again after `execution_success`, measured from the completion.
         *
         * A single retry 1.5s later was enough for a still and never enough for a clip: the
         * server writes the file, then the history entry, and a 6–15 second video takes far
         * longer than that. Two looks, then nothing until the five-minute sweep — which is
         * exactly why the gallery stayed stale until the user pulled to refresh. Four looks
         * spread over half a minute cover the slow case while each stop is a cheap list read.
         */
        private val COMPLETION_RETRY_AT_MS = longArrayOf(1_500L, 4_000L, 9_000L, 16_000L, 28_000L, 45_000L)

    /** Window that folds the burst of completion signals into a single sweep. */
    private const val COMPLETION_COALESCE_MS = 400L
        private const val REFRESH_FOLLOWUP_DELAY_MS = 250L

        fun getInstance(): GalleryRepository {
            return instance ?: synchronized(this) {
                instance ?: GalleryRepository().also { instance = it }
            }
        }
    }

    /**
     * Show what this phone already knows, before any request goes out.
     *
     * ComfyUI's /history is in memory and the network may take seconds; without this the gallery
     * is an empty page on every launch even though every file and its record are still on disk.
     * A later server refresh replaces the list, so this only fills the gap.
     */
    fun primeFromCache() {
        val context = applicationContext ?: return
        if (_galleryItems.value.isNotEmpty()) return
        val serverId = ConnectionManager.currentServerId
            ?: GalleryMetadataCache.lastServer(context) ?: return
        // Reading and parsing the whole local record on the call site blocked the first frame:
        // with a few thousand entries the gallery froze for a second or two on every entry.
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                GalleryMetadataCache.loadMetadata(context, serverId)
            } ?: return@launch
            if (cached.isEmpty() || _galleryItems.value.isNotEmpty()) return@launch
            val stamp = withContext(Dispatchers.IO) {
                GalleryMetadataCache.getCacheTimestamp(context, serverId)
            }
            _galleryItems.value = cached
            listVersion.incrementAndGet()
            _lastRefreshTime.value = stamp
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Cold start: ${cached.size} items from the local record")
        }
    }

    /**
     * Start background preloading of gallery data.
     * Called after WebSocket connection is established, or when entering offline mode.
     */
    fun startBackgroundPreload() {
        val context = applicationContext
        val isOffline = context != null && AppSettings.isOfflineMode(context)

        // In online mode, require a client
        if (!isOffline && comfyUIClient == null) {
            return
        }

        if (_isLoading.value || _isRefreshing.value) {
            return
        }

        scope.launch {
            // Small delay to let UI settle after connection
            delay(500)
            loadGalleryInternal(isRefresh = false)
        }

        // Start periodic refresh only in online mode
        if (!isOffline) {
            startPeriodicRefresh()
        }
    }

    /**
     * Start periodic background refresh
     */
    private fun startPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = scope.launch {
            while (true) {
                delay(PERIODIC_REFRESH_INTERVAL_MS)
                if (comfyUIClient != null && !_isLoading.value && !_isRefreshing.value) {
                    loadGalleryInternal(isRefresh = true)
                }
            }
        }
    }

    /**
     * Stop periodic refresh (call when disconnecting)
     */
    fun stopPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Clears the thumbnail cache and fetches fresh data from server.
     *
     * @param onComplete Callback with success status (true if refresh succeeded)
     */
    fun manualRefresh(onComplete: (Boolean) -> Unit = {}) {
        if (_isLoading.value || _isRefreshing.value) {
            onComplete(false)
            return
        }
        // Take the same gate the background sweep uses: both paths rewrite the same list, and a
        // manual pull also clears the thumbnail cache under the other one's feet.
        val start = synchronized(refreshGate) {
            if (refreshRunning) {
                false
            } else {
                refreshRunning = true
                refreshQueued = false
                lastRefreshStartedAt = System.currentTimeMillis()
                true
            }
        }
        if (!start) {
            onComplete(false)
            return
        }

        scope.launch {
            var success = false
            _isManualRefreshing.value = true
            try {
                // Clear thumbnail cache to force re-fetch
                MediaCache.clearForRefresh()
                success = loadGalleryInternal(isRefresh = true)
            } finally {
                _isManualRefreshing.value = false
                openGateAfterRun()
                onComplete(success)
            }
        }
    }

    /**
     * Releases the gate and schedules the deferred sweep, if one was asked for.
     *
     * `refreshRunning` must go back to false unconditionally: it means "a sweep is in flight", and
     * setting it to the queued flag instead left the gate shut forever once a follower arrived.
     */
    private fun openGateAfterRun() {
        val again = synchronized(refreshGate) {
            val queued = refreshQueued
            refreshQueued = false
            refreshRunning = false
            queued
        }
        if (again) {
            scope.launch {
                delay(REFRESH_FOLLOWUP_DELAY_MS)
                requestRefresh(0L)
            }
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Called after generation completes, periodically, or when returning from other screens.
     */
    fun refresh() = requestRefresh(0L)

    fun addLocalExtractedFrame(filename: String, createdAt: Long = System.currentTimeMillis()) {
        val context = applicationContext
        val path = context?.let { File(File(it.filesDir, "gallery_offline"), filename) }
            ?.takeIf { it.exists() }?.absolutePath
        val newItem = GalleryItem(
            promptId = "local_$filename",
            filename = filename,
            subfolder = "frames",
            type = "output",
            isVideo = false,
            createdAt = createdAt,
            localOnly = true,
            localPath = path
        )
        val current = _galleryItems.value
        if (current.none { it.filename == filename }) {
            _galleryItems.value = listOf(newItem) + current
            listVersion.incrementAndGet()
        }
    }

    fun refreshQuietly(minGapMs: Long = ENTRY_REFRESH_GAP_MS) = requestRefresh(minGapMs)

    /**
     * Refresh after a prompt finished.
     *
     * `execution_success` arrives before the history entry is written, so the first answer can
     * still be the old list. Nothing used to ask again until the five-minute sweep -- which is
     * exactly why a fresh image was missing until the user pulled to refresh.
     */
    fun refreshAfterCompletion(promptId: String?) {
        requestRefresh(0L)
        if (promptId.isNullOrEmpty()) return
        // One chain per prompt: a relay run fires this once per segment, and the queue-size
        // signal can repeat it for the same job.
        if (!completionsAwaiting.add(promptId)) return
        scope.launch {
            try {
                var elapsed = 0L
                for (at in COMPLETION_RETRY_AT_MS) {
                    delay(at - elapsed)
                    elapsed = at
                    if (listContains(promptId)) return@launch
                    DebugLogger.d(TAG, "Completion not listed yet at ${at}ms, asking again")
                    requestRefresh(0L)
                }
                DebugLogger.d(TAG, "Completion still missing after the retry window: $promptId")
            } finally {
                completionsAwaiting.remove(promptId)
            }
        }
    }

    /**
     * Whether the finished prompt has actually landed.
     *
     * The history key is the prompt id, but a few servers echo it back in another shape; a
     * matching file name is just as good evidence that the new item arrived.
     */
    private fun listContains(promptId: String): Boolean {
        val items = _galleryItems.value
        if (items.any { it.promptId == promptId }) return true
        val marker = promptId.takeLast(8)
        return marker.isNotEmpty() && items.any { it.filename.contains(marker) }
    }

    /**
     * One request at a time, and a request that arrives while one is running is not dropped:
     * three completion signals (execution_success, the queue status message, the poll) land
     * within a second of each other, and the later ones used to be thrown away -- including the
     * one that would have found the item after the history write.
     */
    private fun requestRefresh(minGapMs: Long) {
        val start = synchronized(refreshGate) {
            when {
                refreshRunning -> {
                    refreshQueued = true
                    false
                }
                minGapMs > 0 && System.currentTimeMillis() - lastRefreshStartedAt < minGapMs -> false
                else -> {
                    refreshRunning = true
                    refreshQueued = false
                    lastRefreshStartedAt = System.currentTimeMillis()
                    true
                }
            }
        }
        if (!start) {
            DebugLogger.d(TAG, "Refresh folded into one already running or just finished")
            return
        }
        if (comfyUIClient == null) {
            DebugLogger.w(TAG, "Refresh skipped - no client available")
            synchronized(refreshGate) { refreshRunning = false }
            return
        }

        DebugLogger.d(TAG, "Starting background refresh")
        scope.launch {
            try {
                loadGalleryInternal(isRefresh = true)
            } finally {
                openGateAfterRun()
            }
        }
    }

    /**
     * Load gallery data in background.
     * @return true if load succeeded, false otherwise
     */
    private suspend fun loadGalleryInternal(isRefresh: Boolean): Boolean {
        val context = applicationContext
        val serverId = ConnectionManager.currentServerId

        // Check if in offline mode - load from cache instead
        if (context != null && AppSettings.isOfflineMode(context)) {
            return loadFromOfflineCache()
        }

        val client = comfyUIClient ?: run {
            return false
        }

        if (isRefresh) {
            _isRefreshing.value = true
        } else {
            _isLoading.value = true
        }

        try {
            val historyJson = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchAllHistory { history ->
                        continuation.resumeWith(Result.success(history))
                    }
                }
            }

            if (historyJson == null) {
                _isLoading.value = false
                _isRefreshing.value = false
                return false
            }

            // Get pending deletions snapshot
            val deletionsSnapshot: Set<String>
            synchronized(pendingDeletions) {
                deletionsSnapshot = pendingDeletions.toSet()
            }

            var items = parseHistoryToGalleryItems(historyJson)

            // Filter out pending deletions to prevent reappearing
            if (deletionsSnapshot.isNotEmpty()) {
                items = items.filter { it.promptId !in deletionsSnapshot }
            }

            // What we already know about these files -- first sighting, size, shape -- lives in
            // the metadata cache, because /history carries none of it.
            val cached = if (context != null && serverId != null) {
                withContext(Dispatchers.IO) {
                    GalleryMetadataCache.loadMetadata(context, serverId)
                }
            } else null
            val byPrompt = cached.orEmpty().associateBy { it.promptId }
            val firstSight = System.currentTimeMillis()
            items = items.map { item ->
                val known = byPrompt[item.promptId]
                when {
                    known != null -> item.copy(
                        createdAt = known.createdAt,
                        sizeBytes = known.sizeBytes,
                        width = known.width,
                        height = known.height,
                        durationMs = known.durationMs
                    )
                    // No history at all to compare against: leave the date unset rather than
                    // stamping the whole back catalogue with today.
                    cached == null -> item
                    else -> item.copy(createdAt = firstSight)
                }
            }

            // The phone outlives the server: ComfyUI keeps its history in memory, so a restart
            // empties /history while every cached file is still here. Anything we can still show
            // from disk is appended as a local-only entry instead of leaving a blank page.
            if (context != null && serverId != null) {
                val known = items.map { it.promptId }.toHashSet()
                val survivors = cached.orEmpty()
                    .filter { it.promptId !in known && it.promptId !in deletionsSnapshot }
                    .filter { !LocalCachePolicy.isTombstoned(context, it.toCacheKey().keyString) }
                    // A record without its file draws as an empty tile forever. Keeping entries
                    // is only worth it while there is something to show for them.
                    .filter { MediaCache.hasLocalCopy(context, it.toCacheKey(), it.isVideo) }
                    .map { it.copy(localOnly = true) }
                if (survivors.isNotEmpty()) {
                    items = items + survivors
                    DebugLogger.d(TAG, "Kept ${survivors.size} cached items the server no longer lists")
                }
            }

            // Also append locally extracted frames so they show up under "提取帧" tab
            if (context != null) {
                val frames = scanExtractedFrames(context)
                if (frames.isNotEmpty()) {
                    val frameNames = frames.map { it.filename }.toSet()
                    items = frames + items.filter { it.filename !in frameNames }
                }
            }

            val previousCount = _galleryItems.value.size
            _galleryItems.value = items
            val version = listVersion.incrementAndGet()
            cacheGeneratedAssets(items)
            _lastRefreshTime.value = System.currentTimeMillis()
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery refresh complete: ${items.size} items (was $previousCount)")

            // Cache gallery metadata for offline mode
            if (context != null && serverId != null) {
                withContext(Dispatchers.IO) {
                    GalleryMetadataCache.saveMetadata(context, serverId, items)
                }
            }

            // Size and shape only matter for entries the server cannot describe, and they can
            // arrive after first paint: waiting for them delayed every refresh.
            if (context != null) scope.launch(Dispatchers.IO) {
                val snapshot = _galleryItems.value
                val refined = describeLocalFiles(snapshot, context)
                // The list may have moved on while this was reading files: a delete, a hide, or
                // a newer refresh. Writing the older snapshot back would undo it.
                if (version != listVersion.get()) return@launch
                if (refined != snapshot) {
                    _galleryItems.value = refined
                    listVersion.incrementAndGet()
                    val live = ConnectionManager.currentServerId
                    if (live != null) GalleryMetadataCache.saveMetadata(context, live, refined)
                }
            }

            return true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Gallery refresh failed: ${e.message}")
            return false
        } finally {
            _isLoading.value = false
            _isRefreshing.value = false
        }
    }

    /**
     * Load gallery data from offline cache.
     * @return true if cache was loaded successfully, false otherwise
     */
    private fun loadFromOfflineCache(): Boolean {
        val context = applicationContext ?: return false
        val serverId = ConnectionManager.currentServerId ?: return false

        val cachedItems = GalleryMetadataCache.loadMetadata(context, serverId)
        if (cachedItems != null) {
            _galleryItems.value = cachedItems
            _lastRefreshTime.value = GalleryMetadataCache.getCacheTimestamp(context, serverId)
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery loaded from offline cache: ${cachedItems.size} items")
            return true
        }

        DebugLogger.w(TAG, "No offline cache available for gallery")
        return false
    }

    /**
     * Check if gallery data is available (has been loaded at least once)
     */
    fun hasData(): Boolean = hasLoadedOnce && _galleryItems.value.isNotEmpty()

    /**
     * Remove an item from the local gallery list only (server deletion already done).
     * Used by MediaViewerViewModel to sync after it deletes on server.
     *
     * @param promptId The prompt ID of the item to remove
     */
    fun removeItemLocally(promptId: String) {
        val currentItems = _galleryItems.value.toMutableList()
        currentItems.removeAll { it.promptId == promptId }
        _galleryItems.value = currentItems
        listVersion.incrementAndGet()
    }

    /**
     * Fills in size, pixel size and duration from the file on disk. A missing file simply leaves
     * the fields at zero, so this never fails a refresh.
     */
    private suspend fun describeLocalFiles(
        items: List<GalleryItem>,
        context: Context
    ): List<GalleryItem> = withContext(Dispatchers.IO) {
        items.map { item ->
            // Entries the server still lists do not need this: only a survivor's tile depends on
            // knowing whether its bytes are here.
            if (!item.localOnly) return@map item
            val complete = item.sizeBytes > 0L &&
                if (item.isVideo) item.durationMs > 0L else item.width > 0
            if (complete) return@map item
            val path = MediaCache.localPath(context, item.toCacheKey(), item.isVideo)
                ?: return@map item
            val size = File(path).length()
            if (!item.isVideo) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                item.copy(
                    cachedCopy = true,
                    sizeBytes = size,
                    width = bounds.outWidth.coerceAtLeast(0),
                    height = bounds.outHeight.coerceAtLeast(0)
                )
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    item.copy(
                        cachedCopy = true,
                        sizeBytes = size,
                        width = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull() ?: 0,
                        height = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull() ?: 0,
                        durationMs = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    )
                } catch (_: Exception) {
                    item.copy(cachedCopy = true, sizeBytes = size)
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                        // Nothing to release
                    }
                }
            }
        }
    }

    /**
     * Hides a local-only entry whose file could not be loaded. Deliberately not a tombstone: if
     * the failure was a dropped connection the record comes back on the next refresh, whereas a
     * deleted file is hidden again within a second or two.
     */
    fun hideCached(promptId: String) {
        _galleryItems.value = _galleryItems.value.filter { it.promptId != promptId }
        listVersion.incrementAndGet()
    }

    /**
     * A local-only entry whose file the server no longer serves is dead weight: drop it and
     * tombstone the key so neither the merge nor the prefetch brings it back.
     */
    fun forgetCached(promptId: String, filename: String) {
        val context = applicationContext ?: return
        val key = MediaCacheKey(promptId, filename)
        LocalCachePolicy.tombstone(context, listOf(key.keyString))
        MediaCache.evict(key)
        _galleryItems.value = _galleryItems.value.filter { it.promptId != promptId }
        listVersion.incrementAndGet()
        val serverId = ConnectionManager.currentServerId
        if (serverId != null) scope.launch(Dispatchers.IO) {
            GalleryMetadataCache.saveMetadata(context, serverId, _galleryItems.value)
        }
        DebugLogger.d(TAG, "Dropped cached item the server cannot serve")
    }

    /**
     * Forgets an item the server no longer knows about: the file goes, and a tombstone stops the
     * metadata cache from listing it again on the next load.
     */
    private fun dropLocalItem(item: GalleryItem) {
        val context = applicationContext
        // A frame is a file on this phone, not a server record: tombstoning the cache key alone
        // left the file in place and the next scan put the frame straight back.
        deleteLocalFrameFiles(item)
        if (context != null) {
            LocalCachePolicy.tombstone(context, listOf(item.toCacheKey().keyString))
            val serverId = ConnectionManager.currentServerId
            if (serverId != null) {
                scope.launch(Dispatchers.IO) {
                    GalleryMetadataCache.saveMetadata(
                        context, serverId, _galleryItems.value.filter { it.promptId != item.promptId }
                    )
                }
            }
        }
        MediaCache.evict(item.toCacheKey())
        _galleryItems.value = _galleryItems.value.filter { it.promptId != item.promptId }
        listVersion.incrementAndGet()
    }

    /**
     * Removes the copies a frame this app wrote has on the phone: the app's own folder, the
     * Pictures copy the user can see, and its MediaStore row. Best effort — a missing one is fine.
     */
    private fun deleteLocalFrameFiles(item: GalleryItem) {
        if (!item.localOnly && item.localPath == null) return
        val name = item.filename
        if (name.isBlank()) return
        val paths = mutableListOf<String>()
        item.localPath?.let { paths += it }
        applicationContext?.let { context ->
            paths += File(File(context.filesDir, "gallery_offline"), name).absolutePath
            @Suppress("DEPRECATION")
            val pictures = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            paths += File(File(pictures, "ComfyChair"), name).absolutePath
        }
        paths.distinct().forEach { path ->
            runCatching {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    DebugLogger.d(TAG, "Deleted local frame: $name")
                }
            }
        }
        applicationContext?.let { context ->
            runCatching {
                val resolver = context.contentResolver
                val where = "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ?"
                resolver.delete(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    where,
                    arrayOf(name)
                )
            }
        }
    }

    /**
     * Delete an item from the gallery
     */
    suspend fun deleteItem(item: GalleryItem): Boolean {
        if (item.localOnly) {
            dropLocalItem(item)
            return true
        }
        val client = comfyUIClient ?: return false

        // Add to pending deletions to prevent reappearing during concurrent refresh
        synchronized(pendingDeletions) {
            pendingDeletions.add(item.promptId)
        }

        // Remove from local list immediately for responsive UI
        val currentItems = _galleryItems.value.toMutableList()
        currentItems.removeAll { it.promptId == item.promptId }
        _galleryItems.value = currentItems
        listVersion.incrementAndGet()

        try {
            val success = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.deleteHistoryItem(item.promptId) { success ->
                        continuation.resumeWith(Result.success(success))
                    }
                }
            }

            if (success) {
                // Evict from media cache
                MediaCache.evict(MediaCacheKey(item.promptId, item.filename))
            }

            return success
        } finally {
            // Always remove from pending deletions to prevent memory leak
            synchronized(pendingDeletions) {
                pendingDeletions.remove(item.promptId)
            }
        }
    }

    /**
     * Delete multiple items by prompt IDs
     */
    suspend fun deleteItems(promptIds: Set<String>): Int {
        val local = _galleryItems.value.filter { it.promptId in promptIds && it.localOnly }
        local.forEach { dropLocalItem(it) }
        val remoteIds = promptIds - local.map { it.promptId }.toSet()
        if (remoteIds.isEmpty()) return local.size
        val client = comfyUIClient ?: return local.size

        // Find items to be deleted for cache eviction
        val itemsToDelete = _galleryItems.value.filter { it.promptId in remoteIds }

        // Add to pending deletions to prevent reappearing during concurrent refresh
        synchronized(pendingDeletions) {
            pendingDeletions.addAll(remoteIds)
        }

        // Remove from local list immediately for responsive UI
        val currentItems = _galleryItems.value.toMutableList()
        currentItems.removeAll { it.promptId in remoteIds }
        _galleryItems.value = currentItems
        listVersion.incrementAndGet()

        try {
            var successCount = local.size
            for (promptId in remoteIds) {
                val success = withContext(Dispatchers.IO) {
                    kotlin.coroutines.suspendCoroutine { continuation ->
                        client.deleteHistoryItem(promptId) { success ->
                            continuation.resumeWith(Result.success(success))
                        }
                    }
                }
                if (success) {
                    successCount++
                    // Evict from media cache
                    itemsToDelete.filter { it.promptId == promptId }.forEach { item ->
                        MediaCache.evict(MediaCacheKey(item.promptId, item.filename))
                    }
                }
            }

            return successCount
        } finally {
            // Always remove from pending deletions to prevent memory leak
            synchronized(pendingDeletions) {
                pendingDeletions.removeAll(promptIds)
            }
        }
    }

    /**
     * Drops local-only entries whose file no longer exists.
     *
     * Called after the user empties the cache: the records survive in the metadata store but the
     * bytes are gone, and an entry cannot be described any more.
     */
    fun pruneLocalOnlyWithoutCopy() {
        val context = applicationContext ?: return
        val current = _galleryItems.value
        val kept = current.filter {
            !it.localOnly || MediaCache.hasLocalCopy(context, it.toCacheKey(), it.isVideo)
        }
        if (kept.size != current.size) {
            _galleryItems.value = kept
            listVersion.incrementAndGet()
            DebugLogger.d(TAG, "Dropped ${current.size - kept.size} local-only entries without a file")
        }
    }

    /**
     * Clear cached data (for logout/disconnect)
     * Note: Uses MediaCache.reset() to preserve disk cache for offline mode.
     * User can still clear disk cache manually via Settings → Clear Cache.
     */
    fun clearCache() {
        DebugLogger.i(TAG, "Clearing cache")
        _galleryItems.value = emptyList()
        listVersion.incrementAndGet()
        _lastRefreshTime.value = 0L
        hasLoadedOnce = false
        synchronized(pendingDeletions) {
            pendingDeletions.clear()
        }
        stopPeriodicRefresh()
        // Clear media cache (preserves disk cache for offline mode)
        MediaCache.reset()
    }

    /**
     * Reset repository state (for logout/disconnect).
     * Called by ConnectionManager when disconnecting.
     */
    fun reset() {
        DebugLogger.i(TAG, "Resetting repository")
        clearCache()
    }

    /**
     * Parse history JSON to gallery items.
     * Does NOT fetch bitmaps - those are loaded lazily via MediaCache.
     */
    private fun parseHistoryToGalleryItems(historyJson: JSONObject): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        var index = 0

        val promptIds = historyJson.keys()
        while (promptIds.hasNext()) {
            val promptId = promptIds.next()
            val promptHistory = historyJson.optJSONObject(promptId) ?: continue
            val outputs = promptHistory.optJSONObject("outputs") ?: continue

            val nodeIds = outputs.keys()
            while (nodeIds.hasNext()) {
                val nodeId = nodeIds.next()
                val nodeOutput = outputs.optJSONObject(nodeId) ?: continue

                // Check for videos first
                val videos = nodeOutput.optJSONArray("videos")
                    ?: nodeOutput.optJSONArray("gifs")

                if (videos != null && videos.length() > 0) {
                    for (i in 0 until videos.length()) {
                        val videoInfo = videos.optJSONObject(i) ?: continue
                        val filename = videoInfo.optString("filename", "")
                        if (filename.isEmpty()) continue

                        val subfolder = videoInfo.optString("subfolder", "")
                        val type = videoInfo.optString("type", "output")

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = true,
                            index = index++
                        ))
                    }
                }

                // Check for images
                val images = nodeOutput.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    for (i in 0 until images.length()) {
                        val imageInfo = images.optJSONObject(i) ?: continue
                        val filename = imageInfo.optString("filename", "")
                        if (filename.isEmpty()) continue

                        // Skip if it's actually a video
                        if (VIDEO_EXTENSIONS.any { filename.lowercase().endsWith(it) }) {
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            items.add(GalleryItem(
                                promptId = promptId,
                                filename = filename,
                                subfolder = subfolder,
                                type = type,
                                isVideo = true,
                                index = index++
                            ))
                            continue
                        }

                        val subfolder = imageInfo.optString("subfolder", "")
                        val type = imageInfo.optString("type", "output")

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = false,
                            index = index++
                        ))
                    }
                }
            }
        }

        // Sort by index descending (newest first)
        return items.sortedByDescending { it.index }
    }

    private fun scanExtractedFrames(context: android.content.Context): List<GalleryItem> {
        val results = mutableListOf<GalleryItem>()
        // 1. Internal gallery_offline directory
        val offlineDir = java.io.File(context.filesDir, "gallery_offline")
        if (offlineDir.exists()) {
            offlineDir.listFiles { _, name -> name.startsWith("frame_") && name.endsWith(".png") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    results.add(
                        GalleryItem(
                            promptId = "local_${file.name}",
                            filename = file.name,
                            subfolder = "frames",
                            type = "output",
                            isVideo = false,
                            createdAt = file.lastModified(),
                            sizeBytes = file.length(),
                            localOnly = true,
                            localPath = file.absolutePath
                        )
                    )
                }
        }
        // 2. External Pictures/ComfyChair directory
        val extDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "ComfyChair")
        if (extDir.exists()) {
            extDir.listFiles { _, name -> name.startsWith("frame_") && name.endsWith(".png") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    if (results.none { it.filename == file.name }) {
                        results.add(
                            GalleryItem(
                                promptId = "local_${file.name}",
                                filename = file.name,
                                subfolder = "frames",
                                type = "output",
                                isVideo = false,
                                createdAt = file.lastModified(),
                                sizeBytes = file.length(),
                                localOnly = true,
                                localPath = file.absolutePath
                            )
                        )
                    }
                }
        }
        return results
    }
}
