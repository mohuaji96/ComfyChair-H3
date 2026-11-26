package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.LocalCachePolicy
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.SequenceStore
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * Represents a gallery item (image or video).
 * Bitmaps are stored in MediaCache, not in this data class.
 */
@Immutable
data class GalleryItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0, // For sorting
    /** The server no longer lists this one; its /view URL may still serve the file. */
    val localOnly: Boolean = false,
    /** The bytes are on this phone, so it renders without the server. */
    val cachedCopy: Boolean = false,
    /** First time this phone saw the item; 0 when it predates metadata recording. */
    val createdAt: Long = 0L,
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    /**
     * Absolute path of a file this app wrote itself (extracted frames). Such an entry is never
     * fetched from the server: it renders from this path, and disappears only when the file does.
     * Not persisted — the scan refills it on every refresh.
     */
    val localPath: String? = null
) {
    /** Create a cache key for this item */
    fun toCacheKey() = MediaCacheKey(promptId, filename)
}

/**
 * UI state for the Gallery screen
 */
@Immutable
data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedItems: Set<String> = emptySet(), // Set of "${promptId}_${filename}" keys
    val isSelectionMode: Boolean = false
)

/**
 * Events emitted by gallery operations
 */
sealed class GalleryEvent {
    data class ShowToast(val messageResId: Int) : GalleryEvent()
    data class ShowMedia(val item: GalleryItem, val bitmap: Bitmap?, val videoUri: Uri?) : GalleryEvent()
}

/**
 * ViewModel for the Gallery screen.
 * Uses GalleryRepository for data management and background loading.
 */
class GalleryViewModel : ViewModel() {

    // Constants
    companion object {
        private const val TAG = "Gallery"
    }

    // State
    private val repository = GalleryRepository.getInstance()

    // Favourites keep an item out of the size-driven eviction, so the gallery's heart is not just
    // decoration - it is the only way to say "this one matters more than the limit".
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    fun loadFavorites(context: Context) {
        _favorites.value = LocalCachePolicy.favorites(context)
    }

    fun toggleFavorite(context: Context, item: GalleryItem) {
        val key = item.toCacheKey().keyString
        val next = key !in _favorites.value
        LocalCachePolicy.setFavorite(context, key, next)
        _favorites.value = LocalCachePolicy.favorites(context)
    }

    /** The grid could not load a server-less item: hide it for this session. */
    fun forgetMissing(item: GalleryItem) {
        repository.hideCached(item.promptId)
    }

    // Selection state (local to this ViewModel)
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    // Combine repository state with local selection state
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GalleryEvent>()
    val events: SharedFlow<GalleryEvent> = _events.asSharedFlow()

    init {
        // Observe repository state and combine with local selection state
        viewModelScope.launch {
            combine(
                repository.galleryItems,
                repository.isLoading,
                repository.isManualRefreshing,
                _selectedItems,
                _isSelectionMode
            ) { items, isLoading, isManualRefreshing, selectedItems, isSelectionMode ->
                GalleryUiState(
                    items = items,
                    isLoading = isLoading,
                    isRefreshing = isManualRefreshing, // Only show indicator for manual refresh
                    selectedItems = selectedItems,
                    isSelectionMode = isSelectionMode
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun initialize(context: Context) {
        repository.initialize(context)
        // Local records first: the gallery must not be a blank page while /history is on its way.
        repository.primeFromCache()
        _sequenceIds.value = SequenceStore.ids(context)
    }

    /**
     * Prompt ids that belong to a 连拍 chain. Kept beside the main list so switching to the
     * 连拍视频 filter does not need a second round trip.
     */
    private val _sequenceIds = MutableStateFlow<Set<String>>(emptySet())
    val sequenceIds: StateFlow<Set<String>> = _sequenceIds.asStateFlow()

    fun reloadSequences(context: Context) {
        _sequenceIds.value = SequenceStore.ids(context)
    }

    /**
     * Load gallery - delegates to repository.
     * If repository already has data, this will return immediately with cached data.
     * The repository handles background refreshing.
     */
    fun loadGallery() {
        DebugLogger.d(TAG, "Loading gallery")
        // Entering the gallery always asks once. It used to load only when the list was empty, so
        // a generation was invisible until the completion signal happened to land or the five
        // minute sweep came round; the repository folds this into any request already running.
        if (repository.hasData()) repository.refreshQuietly() else repository.refresh()
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Shows the refresh indicator and displays a Toast on completion.
     */
    fun manualRefresh() {
        DebugLogger.i(TAG, "Manual refresh")
        repository.manualRefresh { success ->
            viewModelScope.launch {
                if (success) {
                    DebugLogger.d(TAG, "Refresh successful")
                    _events.emit(GalleryEvent.ShowToast(R.string.msg_gallery_refresh_success))
                } else {
                    DebugLogger.w(TAG, "Refresh failed")
                    _events.emit(GalleryEvent.ShowToast(R.string.error_gallery_refresh))
                }
            }
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Used when returning from other screens or after external changes.
     */
    fun refresh() {
        DebugLogger.d(TAG, "Background refresh")
        repository.refresh()
    }

    /** Drops entries that only existed because their file was cached, after a cache clear. */
    fun pruneLocalOnly() {
        repository.pruneLocalOnlyWithoutCopy()
    }

    fun deleteItem(item: GalleryItem) {
        DebugLogger.i(TAG, "Deleting item: ${item.promptId}")
        viewModelScope.launch {
            val success = repository.deleteItem(item)
            if (success) {
                DebugLogger.d(TAG, "Delete successful")
                _events.emit(GalleryEvent.ShowToast(R.string.msg_history_item_deleted_success))
            } else {
                DebugLogger.w(TAG, "Delete failed")
                _events.emit(GalleryEvent.ShowToast(R.string.error_history_item_delete))
            }
        }
    }

    // Selection mode functions

    private fun getItemKey(item: GalleryItem): String = "${item.promptId}_${item.filename}"

    fun toggleSelection(item: GalleryItem) {
        val key = getItemKey(item)
        val currentSelected = _selectedItems.value.toMutableSet()

        if (currentSelected.contains(key)) {
            currentSelected.remove(key)
        } else {
            currentSelected.add(key)
        }

        _selectedItems.value = currentSelected
        _isSelectionMode.value = currentSelected.isNotEmpty()
    }

    fun isItemSelected(item: GalleryItem): Boolean {
        return _selectedItems.value.contains(getItemKey(item))
    }

    fun clearSelection() {
        _selectedItems.value = emptySet()
        _isSelectionMode.value = false
    }

    /** Replaces the selection with exactly these items: 全选 and 选这一天 both come through here. */
    fun selectItems(items: List<GalleryItem>) {
        _selectedItems.value = items.map { getItemKey(it) }.toSet()
        _isSelectionMode.value = items.isNotEmpty()
    }

    fun isEverythingSelected(items: List<GalleryItem>): Boolean =
        items.isNotEmpty() && items.all { _selectedItems.value.contains(getItemKey(it)) }

    fun enterSelectionMode() {
        _isSelectionMode.value = true
    }

    fun deleteSelected() {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            // Get unique prompt IDs (multiple items can share the same promptId)
            val promptIds = selectedItems.map { it.promptId }.distinct().toSet()
            val successCount = repository.deleteItems(promptIds)
            val failCount = promptIds.size - successCount

            // Show result toast
            if (failCount == 0) {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_items_deleted_success))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_some_items_failed_to_delete))
            }

            // Clear selection after delete
            clearSelection()
        }
    }

    fun getSelectedItems(): List<GalleryItem> {
        val selectedKeys = _selectedItems.value
        return _uiState.value.items.filter { getItemKey(it) in selectedKeys }
    }

    fun saveSelectedToGallery(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            var failCount = 0

            for (item in selectedItems) {
                val success = if (item.isVideo) {
                    saveVideoToGalleryInternal(context, item)
                } else {
                    saveImageToGalleryInternal(context, item)
                }
                if (success) successCount++ else failCount++
            }

            // Show result toast
            if (failCount == 0) {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_items_saved_to_gallery))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_some_items_failed_to_save))
            }

            // Clear selection after save
            clearSelection()
        }
    }

    private suspend fun saveImageToGalleryInternal(context: Context, item: GalleryItem): Boolean {
        return withContext(Dispatchers.IO) {
            val key = MediaCacheKey(item.promptId, item.filename)
            // Extracted frames only exist on the phone (`localPath`); asking the server for them
            // fails whenever the in-memory LRU has evicted the bitmap.
            val bitmap = item.localPath?.let { path ->
                runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
            } ?: MediaCache.getBitmap(key)
                ?: MediaCache.fetchBitmap(key, false, item.subfolder, item.type)

            if (bitmap == null) return@withContext false

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
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun saveVideoToGalleryInternal(context: Context, item: GalleryItem): Boolean {
        return withContext(Dispatchers.IO) {
            val key = MediaCacheKey(item.promptId, item.filename)
            val sourceFile = item.localPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                ?: MediaCache.localFile(context, key, isVideo = true)

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext false

                val written = resolver.openOutputStream(uri)?.use { output ->
                    if (sourceFile != null) {
                        sourceFile.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
                        true
                    } else {
                        val fetchedUri = MediaCache.fetchVideoUri(key, item.subfolder, item.type, context)
                        if (fetchedUri != null) {
                            resolver.openInputStream(fetchedUri)?.use { input -> input.copyTo(output, 128 * 1024) }
                            true
                        } else false
                    }
                } ?: false

                if (!written) {
                    resolver.delete(uri, null, null)
                }
                written
            } catch (e: Exception) {
                false
            }
        }
    }

    fun shareSelected(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            try {
                val uris = mutableListOf<Uri>()

                for ((index, item) in selectedItems.withIndex()) {
                    val uri = if (item.isVideo) {
                        getVideoShareUri(context, item, index)
                    } else {
                        getImageShareUri(context, item, index)
                    }
                    uri?.let { uris.add(it) }
                }

                if (uris.isEmpty()) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
                    return@launch
                }

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                    type = if (selectedItems.all { it.isVideo }) "video/*"
                           else if (selectedItems.none { it.isVideo }) "image/*"
                           else "*/*"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.button_share)
                    )
                )

                // Clear selection after share
                clearSelection()
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
            }
        }
    }

    private suspend fun getImageShareUri(context: Context, item: GalleryItem, index: Int): Uri? {
        return withContext(Dispatchers.IO) {
            val key = MediaCacheKey(item.promptId, item.filename)
            val bitmap = item.localPath?.let { path ->
                runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
            } ?: MediaCache.getBitmap(key)
                ?: MediaCache.fetchBitmap(key, false, item.subfolder, item.type)

            if (bitmap == null) return@withContext null

            try {
                val shareFile = File(context.cacheDir, "share_image_$index.png")
                shareFile.outputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getVideoShareUri(context: Context, item: GalleryItem, index: Int): Uri? {
        return withContext(Dispatchers.IO) {
            val key = MediaCacheKey(item.promptId, item.filename)
            val shareFile = File(context.cacheDir, "share_video_$index.mp4")
            val sourceFile = item.localPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                ?: MediaCache.localFile(context, key, isVideo = true)

            val written = if (sourceFile != null) {
                runCatching {
                    sourceFile.inputStream().use { input ->
                        shareFile.outputStream().use { output ->
                            input.copyTo(output, 128 * 1024)
                        }
                    }
                    true
                }.getOrDefault(false)
            } else {
                val fetchedUri = MediaCache.fetchVideoUri(key, item.subfolder, item.type, context)
                if (fetchedUri != null) {
                    runCatching {
                        context.contentResolver.openInputStream(fetchedUri)?.use { input ->
                            shareFile.outputStream().use { output ->
                                input.copyTo(output, 128 * 1024)
                            }
                        }
                        true
                    }.getOrDefault(false)
                } else false
            }

            if (!written || !shareFile.exists() || shareFile.length() == 0L) {
                shareFile.delete()
                return@withContext null
            }

            try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )
            } catch (e: Exception) {
                shareFile.delete()
                null
            }
        }
    }
}
