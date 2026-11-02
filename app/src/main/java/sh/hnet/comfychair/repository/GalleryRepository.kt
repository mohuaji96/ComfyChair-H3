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

    companion object {
        private const val TAG = "GalleryRepo"

        @Volatile
        private var instance: GalleryRepository? = null

        private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".gif", ".avi", ".mov")
        private const val PERIODIC_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        fun getInstance(): GalleryRepository {
            return instance ?: synchronized(this) {
                instance ?: GalleryRepository().also { instance = it }
            }
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

        scope.launch {
            _isManualRefreshing.value = true

            // Clear thumbnail cache to force re-fetch
            MediaCache.clearForRefresh()

            val success = loadGalleryInternal(isRefresh = true)

            _isManualRefreshing.value = false
            onComplete(success)
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Called after generation completes, periodically, or when returning from other screens.
     */
    fun refresh() {
        if (_isLoading.value || _isRefreshing.value) {
            DebugLogger.d(TAG, "Refresh skipped - already in progress (loading=${_isLoading.value}, refreshing=${_isRefreshing.value})")
            return
        }

        if (comfyUIClient == null) {
            DebugLogger.w(TAG, "Refresh skipped - no client available")
            return
        }

        DebugLogger.d(TAG, "Starting background refresh")
        scope.launch {
            loadGalleryInternal(isRefresh = true)
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
                    .map { it.copy(localOnly = true) }
                if (survivors.isNotEmpty()) {
                    items = items + survivors
                    DebugLogger.d(TAG, "Kept ${survivors.size} cached items the server no longer lists")
                }
            }

            val previousCount = _galleryItems.value.size
            _galleryItems.value = items
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
                val refined = describeLocalFiles(_galleryItems.value, context)
                if (refined != _galleryItems.value) {
                    _galleryItems.value = refined
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
     * Clear cached data (for logout/disconnect)
     * Note: Uses MediaCache.reset() to preserve disk cache for offline mode.
     * User can still clear disk cache manually via Settings → Clear Cache.
     */
    fun clearCache() {
        DebugLogger.i(TAG, "Clearing cache")
        _galleryItems.value = emptyList()
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
}
