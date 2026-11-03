package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.text.format.Formatter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.ServerStatusLamp
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.cache.ActiveView
import sh.hnet.comfychair.cache.LocalCachePolicy
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GalleryEvent
import sh.hnet.comfychair.viewmodel.GalleryItem
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    generationViewModel: GenerationViewModel,
    galleryViewModel: GalleryViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by galleryViewModel.uiState.collectAsState()
    val favorites by galleryViewModel.favorites.collectAsState()
    var showCacheSheet by remember { mutableStateOf(false) }
    // The filter survives configuration changes and page switches; a gallery that silently jumps
    // back to "all" is worse than no filter at all.
    var categoryName by rememberSaveable { mutableStateOf(GalleryCategory.ALL.name) }
    val category = GalleryCategory.valueOf(categoryName)
    var cacheLimitGb by remember { mutableStateOf(LocalCachePolicy.limitGb(context)) }
    var cacheUsedBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(showCacheSheet) {
        if (showCacheSheet) {
            cacheLimitGb = LocalCachePolicy.limitGb(context)
            cacheUsedBytes = withContext(Dispatchers.IO) { MediaCache.diskUsageBytes(context) }
        }
    }
    LaunchedEffect(Unit) { galleryViewModel.loadFavorites(context) }
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()

    // Everything downstream -- grid, viewer, prefetch, selection -- works on the filtered list, so
    // a swipe in the viewer can never land on an item the current filter hides.
    val sequenceIds by galleryViewModel.sequenceIds.collectAsState()
    LaunchedEffect(Unit) { galleryViewModel.reloadSequences(context) }

    val visibleItems = remember(uiState.items, categoryName, favorites, sequenceIds) {
        when (category) {
            GalleryCategory.ALL -> uiState.items
            GalleryCategory.IMAGE -> uiState.items.filter { !it.isVideo }
            GalleryCategory.VIDEO -> uiState.items.filter { it.isVideo }
            GalleryCategory.FAVORITE -> uiState.items.filter {
                it.toCacheKey().keyString in favorites
            }
            GalleryCategory.SEQUENCE -> uiState.items.filter { it.promptId in sequenceIds }
        }
    }

    // Check offline mode
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    // State and effects
    // Activity result launcher for MediaViewerActivity
    val mediaViewerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Restore active view to Gallery
        MediaCache.setActiveView(ActiveView.GALLERY)

        // Refresh gallery if item was deleted (silent refresh, no spinner)
        if (result.resultCode == Activity.RESULT_OK) {
            val itemDeleted = result.data?.getBooleanExtra(MediaViewerActivity.RESULT_ITEM_DELETED, false) ?: false
            if (itemDeleted) {
                galleryViewModel.refresh()
            }
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        galleryViewModel.initialize(context)
    }

    // Load gallery when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            galleryViewModel.loadGallery()
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        galleryViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is GalleryEvent.ShowMedia -> {
                    // Handle media display
                }
            }
        }
    }

    // Helper function to convert GalleryItems to MediaViewerItems
    fun galleryItemsToViewerItems(items: List<GalleryItem>): List<MediaViewerItem> {
        return items.map { item ->
            MediaViewerItem(
                promptId = item.promptId,
                filename = item.filename,
                subfolder = item.subfolder,
                type = item.type,
                isVideo = item.isVideo,
                index = item.index
            )
        }
    }

    // Function to launch media viewer
    fun launchMediaViewer(clickedIndex: Int) {
        // Set active view to MediaViewer
        MediaCache.setActiveView(ActiveView.MEDIA_VIEWER)

        // Update priorities to protect items around clicked index
        val allKeys = visibleItems.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(clickedIndex, allKeys)

        // Built once per tap, not per frame: no need to cache it.
        val viewerItems = galleryItemsToViewerItems(visibleItems)
        val intent = MediaViewerActivity.createGalleryIntent(
            context = context,
            hostname = ConnectionManager.hostname,
            port = ConnectionManager.port,
            items = viewerItems,
            initialIndex = clickedIndex
        )
        mediaViewerLauncher.launch(intent)
    }

    // Grid state for scroll tracking
    val gridState = rememberLazyGridState()

    // Create prefetch items list from gallery items
    val prefetchItems = remember(visibleItems) {
        visibleItems.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
        }
    }

    // Track scroll position and update cache priorities with debounce
    // Only update when Gallery is the active view (not when MediaViewer is animating closed)
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.layoutInfo.visibleItemsInfo.size) {
        if (prefetchItems.isNotEmpty() && MediaCache.isActiveView(ActiveView.GALLERY)) {
            // Debounce scroll updates to avoid excessive calls during fast scrolling
            kotlinx.coroutines.delay(100)
            MediaCache.updateGalleryPosition(
                firstVisibleIndex = gridState.firstVisibleItemIndex,
                visibleItemCount = gridState.layoutInfo.visibleItemsInfo.size,
                allItems = prefetchItems,
                columnsInGrid = 2
            )
        }
    }

    // Prefetch when items become available or change (e.g., after manual refresh)
    // Use first item's key as part of the effect key to detect list changes
    val itemsKey = remember(visibleItems) {
        if (visibleItems.isEmpty()) "" else "${visibleItems.size}_${visibleItems.first().promptId}"
    }
    LaunchedEffect(itemsKey) {
        if (visibleItems.isNotEmpty()) {
            // Base prefetch on current scroll position instead of always starting from 0
            val startIndex = gridState.firstVisibleItemIndex.coerceAtLeast(0)
            val endIndex = (startIndex + 24).coerceAtMost(visibleItems.size)
            val initialItems = visibleItems.subList(startIndex, endIndex).map { item ->
                MediaCache.PrefetchItem(
                    key = item.toCacheKey(),
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type
                )
            }
            MediaCache.initialPrefetch(initialItems)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showCacheSheet) {
            CacheManagementSheet(
                usedBytes = cacheUsedBytes,
                limitGb = cacheLimitGb,
                onSelectLimit = { gb ->
                    LocalCachePolicy.setLimitGb(context, gb)
                    cacheLimitGb = gb
                    MediaCache.trimToLimit(context)
                    cacheUsedBytes = MediaCache.diskUsageBytes(context)
                },
                onClear = {
                    MediaCache.clearAll()
                    cacheUsedBytes = 0L
                    Toast.makeText(context, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showCacheSheet = false }
            )
        }
        TopAppBar(
            title = {
                if (uiState.isSelectionMode) {
                    Text(stringResource(R.string.gallery_selected_count, uiState.selectedItems.size))
                } else {
                    Text(stringResource(R.string.title_gallery))
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                if (uiState.isSelectionMode) {
                    IconButton(onClick = { galleryViewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel_selection))
                    }
                }
            },
            actions = {
                ServerStatusLamp()
                if (uiState.isSelectionMode) {
                    // Selection mode actions: Delete, Save, and Share
                    IconButton(onClick = { galleryViewModel.deleteSelected() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.button_delete_history_item),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = { galleryViewModel.saveSelectedToGallery(context) }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.button_save_image))
                    }
                    IconButton(onClick = { galleryViewModel.shareSelected(context) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.button_share))
                    }
                } else {
                    // Normal mode actions: cache management, Select and Menu
                    IconButton(onClick = { galleryViewModel.enterSelectionMode() }) {
                        Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.button_gallery_select))
                    }
                    AppMenuDropdown(
                        onSettings = onNavigateToSettings,
                        onLogout = onLogout,
                        extraLabel = stringResource(R.string.cache_management_title),
                        extraIcon = Icons.Default.Delete,
                        onExtra = { showCacheSheet = true }
                    )
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GalleryCategory.values().forEach { option ->
                FilterChip(
                    selected = category == option,
                    onClick = {
                        categoryName = option.name
                        // Selection is invisible once it is filtered out, and deleting what the
                        // user cannot see is not a surprise worth keeping.
                        if (uiState.isSelectionMode) galleryViewModel.clearSelection()
                    },
                    label = {
                        Text(
                            stringResource(option.labelRes),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { galleryViewModel.manualRefresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            // Always use LazyVerticalGrid for consistent nested scroll behavior with pull-to-refresh
            // Grouping has to happen in composition scope, not inside the grid DSL.
            val dayGroups = remember(visibleItems, context) { groupByDay(context, visibleItems) }
            val indexByKey = remember(visibleItems) {
                visibleItems.withIndex().associate { (i, it) ->
                    "${it.promptId}_${it.filename}" to i
                }
            }
            NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                if (uiState.isLoading && visibleItems.isEmpty()) {
                    // Loading state - show as full-span item
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (visibleItems.isEmpty()) {
                    // Empty state - show as full-span item
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = stringResource(
                                        when (category) {
                                            GalleryCategory.VIDEO -> R.string.msg_gallery_empty_video
                                            GalleryCategory.FAVORITE -> R.string.msg_gallery_empty_favorite
                                            GalleryCategory.SEQUENCE -> R.string.msg_gallery_empty_sequence
                                            else -> R.string.msg_gallery_empty
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Gallery items
                    // Grouped by the day the phone first saw each item. Anything that predates
                    // metadata recording has no date and falls into one trailing block.
                    dayGroups.forEach { group ->
                        item(span = { GridItemSpan(2) }, key = "day-${group.first}") {
                            Text(
                                text = group.first,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
                            )
                        }
                        items(group.second, key = { "${it.promptId}_${it.filename}" }) { item ->
                            GalleryItemCard(
                            item = item,
                            isSelected = galleryViewModel.isItemSelected(item),
                            isFavorite = item.toCacheKey().keyString in favorites,
                            onToggleFavorite = { galleryViewModel.toggleFavorite(context, item) },
                            isOfflineMode = isOfflineMode,
                            onMissing = {
                                if (item.localOnly) galleryViewModel.forgetMissing(item)
                            },
                            onTap = {
                                if (uiState.isSelectionMode) {
                                    // In selection mode, tap toggles selection
                                    galleryViewModel.toggleSelection(item)
                                } else {
                                    // Normal mode, tap opens MediaViewer at this item's place in
                                    // the filtered list rather than its place in the grid.
                                    launchMediaViewer(
                                        indexByKey["${item.promptId}_${item.filename}"] ?: -1
                                    )
                                }
                            },
                            onLongPress = {
                                // Long press enters selection mode and selects this item
                                galleryViewModel.toggleSelection(item)
                            }
                        )
                        }
                    }
                }
                }
            }
        }
    }
}

private val GALLERY_DAY_FORMAT: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

/** One bucket per calendar day, in list order, plus a block for undated items. */
private fun groupByDay(
    context: android.content.Context,
    items: List<GalleryItem>
): List<Pair<String, List<GalleryItem>>> {
    val today = startOfDay(System.currentTimeMillis())
    val yesterday = today - 86_400_000L
    val buckets = LinkedHashMap<String, MutableList<GalleryItem>>()
    for (item in items) {
        val label = when {
            item.createdAt <= 0L -> context.getString(R.string.gallery_date_earlier)
            item.createdAt >= today -> context.getString(R.string.gallery_date_today)
            item.createdAt >= yesterday -> context.getString(R.string.gallery_date_yesterday)
            else -> GALLERY_DAY_FORMAT.format(Date(item.createdAt))
        }
        buckets.getOrPut(label) { mutableListOf() }.add(item)
    }
    return buckets.map { it.key to it.value }
}

private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * Gallery item card with lazy bitmap loading.
 * Bitmaps are loaded from MediaCache on-demand.
 */
@Composable
private fun GalleryItemCard(
    item: GalleryItem,
    isSelected: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    isOfflineMode: Boolean = false,
    /** Called when a server-less entry could not be loaded at all. */
    onMissing: () -> Unit = {},
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    // Create cache key directly from item's stable properties
    val cacheKey = item.toCacheKey()
    // A tile is ~190dp wide; decoding the 2K original for it is pure waste.
    val tileTargetPx = with(LocalDensity.current) { 190.dp.roundToPx() }
    val (bitmap, isLoading) = rememberLazyBitmap(
        cacheKey = cacheKey,
        isVideo = item.isVideo,
        subfolder = item.subfolder,
        type = item.type,
        targetPx = tileTargetPx
    )

    // A local-only entry whose file the server no longer has would keep a blank tile forever.
    // Only pruned while actually connected, so an offline start never deletes a good record.
    if (item.localOnly) LaunchedEffect(bitmap, isLoading) {
        if (bitmap == null && !isLoading && ConnectionManager.clientOrNull != null) {
            onMissing()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.medium
                    )
                } else {
                    Modifier
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail from cache
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = if (item.isVideo) {
                            stringResource(R.string.content_description_gallery_video_thumbnail)
                        } else {
                            stringResource(R.string.content_description_gallery_thumbnail)
                        },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                isLoading -> {
                    // Loading placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                else -> {
                    // Fallback placeholder - show CloudOff for offline mode, otherwise normal icon
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isOfflineMode) {
                                Icons.Default.CloudOff
                            } else if (item.isVideo) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.Image
                            },
                            contentDescription = if (isOfflineMode) {
                                stringResource(R.string.content_description_not_cached)
                            } else {
                                null
                            },
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        if (isOfflineMode) Text(
                            stringResource(R.string.msg_gallery_offline_unavailable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // Favourite: the only item-level flag the user controls, and the only exemption from
            // size-driven eviction.
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.favorite_remove else R.string.favorite_add
                    ),
                    tint = if (isFavorite) MaterialTheme.colorScheme.error else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Selection indicator
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.label_item_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Cached-only: the server forgot this one, the phone still has it.
            if (item.localOnly && item.cachedCopy) Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    stringResource(R.string.gallery_badge_cached),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }

            // Video indicator (only show when not selected and not loading)
            if (item.isVideo && !isSelected && bitmap != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.content_description_video),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/**
 * Cache management sheet: the cache is disposable, so the only decisions are how big it may grow
 * and when to empty it. Anything the user wants to keep goes to the system gallery instead.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CacheManagementSheet(
    usedBytes: Long,
    limitGb: Long,
    onSelectLimit: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.cache_management_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalCachePolicy.limitOptionsGb.forEach { gb ->
                    FilterChip(
                        selected = gb == limitGb,
                        onClick = { onSelectLimit(gb) },
                        label = {
                            Text(
                                text = if (gb <= 0L) stringResource(R.string.cache_limit_unlimited)
                                else "${gb}GB"
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(
                    R.string.cache_used_format,
                    Formatter.formatShortFileSize(context, usedBytes),
                    if (limitGb <= 0L) stringResource(R.string.cache_limit_unlimited) else "${limitGb}GB"
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onClear) {
                Text(
                    text = stringResource(R.string.button_clear_cache),
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** What the gallery grid is showing. Favourites is a filter, not a second list. */
private enum class GalleryCategory(@StringRes val labelRes: Int) {
    ALL(R.string.gallery_filter_all),
    IMAGE(R.string.gallery_filter_image),
    VIDEO(R.string.gallery_filter_video),
    FAVORITE(R.string.gallery_filter_favorite),
    /** Segments submitted while 连拍 was on; recognised by the phone's own record. */
    SEQUENCE(R.string.gallery_filter_sequence)
}
