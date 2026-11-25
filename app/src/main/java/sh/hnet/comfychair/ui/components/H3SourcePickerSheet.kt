package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.ServerInputs
import sh.hnet.comfychair.viewmodel.GalleryItem

/** The media cache key of a server input file (the cache keys files by job; these have none). */
fun serverInputKey(name: String): MediaCacheKey =
    ServerInputs.File(name).let { MediaCacheKey("${ServerInputs.TYPE}:${it.subfolder}", it.filename) }

private sealed interface PickerEntry {
    val key: String
    val filename: String
    data class Server(val file: ServerInputs.File) : PickerEntry {
        override val key: String get() = "server:${file.name}"
        override val filename: String get() = file.name
    }
    data class Local(val item: GalleryItem) : PickerEntry {
        override val key: String get() = "local:${item.promptId}_${item.filename}"
        override val filename: String get() = item.filename
    }
}

/**
 * Bottom sheet allowing users to pick an input image/reference from the server's own input folder
 * (used by name, nothing uploaded), from an existing gallery image or extracted frame, or from the
 * device album ("从设备"). Supports smooth half-screen / full-screen dragging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H3SourcePickerSheet(
    title: String? = null,
    currentSelectedFile: String? = null,
    onDismiss: () -> Unit,
    onPickFromAlbum: () -> Unit,
    /**
     * [localPath] is set for a frame this app wrote: it must be uploaded before it can be used.
     *
     * [item] is the gallery entry the pick came from, or null for a file already sitting in the
     * server's input folder. A gallery image lives in the server's *output* folder, which the
     * workflow cannot read, so the caller needs the entry (and its cache key) to turn it into an
     * input file rather than passing the name through.
     */
    onSelectFile: (filename: String, localPath: String?, item: GalleryItem?) -> Unit
) {
    // skipPartiallyExpanded = false allows smooth dragging between half-expanded and fully-expanded states
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val galleryItems by GalleryRepository.getInstance().galleryItems.collectAsState()
    val titleText = title ?: stringResource(R.string.h3_source_image)
    val tabLabels = listOf(
        R.string.h3_tab_all,
        R.string.h3_tab_server,
        R.string.h3_tab_frames,
        R.string.h3_tab_history
    )

    // Server's input folder: images only, newest first where the server tells the order
    var serverFiles by remember { mutableStateOf<List<ServerInputs.File>>(emptyList()) }
    var serverLoading by remember { mutableStateOf(true) }
    var serverOffline by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val client = ConnectionManager.clientOrNull
        if (client == null) {
            serverOffline = true
            serverLoading = false
            return@LaunchedEffect
        }
        when (val result = ServerInputs.list(client, video = false)) {
            is ServerInputs.ListResult.Ok -> serverFiles = result.files
            is ServerInputs.ListResult.Failed -> Unit
        }
        serverLoading = false
    }

    val imageItems = remember(galleryItems) { galleryItems.filter { !it.isVideo } }
    val frames = remember(imageItems) { imageItems.filter { isFrameName(it) } }
    val history = remember(imageItems) { imageItems.filter { !isFrameName(it) } }

    // 0: 全部, 1: 服务器素材, 2: 提取帧, 3: 历史生成
    var selectedFilter by remember { mutableStateOf(0) }

    val entries = remember(selectedFilter, serverFiles, frames, history) {
        when (selectedFilter) {
            1 -> serverFiles.map { PickerEntry.Server(it) }
            2 -> frames.map { PickerEntry.Local(it) }
            3 -> history.map { PickerEntry.Local(it) }
            else -> serverFiles.map { PickerEntry.Server(it) } +
                (frames + history).map { PickerEntry.Local(it) }
        }
    }
    val totalImages = serverFiles.size + imageItems.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // Header
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when {
                    serverOffline -> stringResource(R.string.h3_offline_pick_hint)
                    serverLoading -> stringResource(R.string.h3_images_reading_server, totalImages)
                    else -> stringResource(R.string.h3_images_with_server, totalImages, serverFiles.size)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            // Filter chips: four labels with counts outgrew the row, and the last chip was
            // squeezed into a vertical sliver. No counts here (the line above has them) and the
            // row scrolls.
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(4) { index ->
                    FilterChip(
                        selected = selectedFilter == index,
                        onClick = { selectedFilter = index },
                        label = {
                            Text(
                                stringResource(tabLabels[index]),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // First tile: "从设备" (upload from phone device)
                item(key = "from_device_tile") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onPickFromAlbum)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.h3_from_device),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                if (serverLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                } else if (serverOffline && selectedFilter in 0..1) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.h3_offline_server_unavailable),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (entries.isEmpty() && !serverLoading && !serverOffline) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.h3_no_assets),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }

                items(entries, key = { it.key }) { entry ->
                    val isSelected = !currentSelectedFile.isNullOrBlank() &&
                        entry.filename == currentSelectedFile
                    val bitmap = when (entry) {
                        is PickerEntry.Server -> rememberRetainedBitmap(
                            cacheKey = serverInputKey(entry.file.name),
                            isVideo = false,
                            subfolder = entry.file.subfolder,
                            type = ServerInputs.TYPE
                        )
                        is PickerEntry.Local -> rememberRetainedBitmap(
                            cacheKey = entry.item.toCacheKey(),
                            isVideo = false,
                            subfolder = entry.item.subfolder,
                            type = entry.item.type,
                            localPath = entry.item.localPath
                        )
                    }

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (isSelected) {
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                } else {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                }
                            )
                            .clickable {
                                val picked = (entry as? PickerEntry.Local)?.item
                                onSelectFile(entry.filename, picked?.localPath, picked)
                            }
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                            }
                        }

                        // Selected checkmark badge in top-right
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(24.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.h3_selected),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Source badge in top-left: where the tile comes from
                        val badge = when (entry) {
                            is PickerEntry.Server -> stringResource(R.string.h3_badge_server)
                            is PickerEntry.Local -> if (isFrameName(entry.item)) stringResource(R.string.h3_tab_frames) else null
                        }
                        if (badge != null) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A file the app extracted from a video; those live only in the gallery's own "提取帧" tab. */
private fun isFrameName(item: GalleryItem): Boolean =
    item.filename.startsWith("frame_") || item.filename.startsWith("extract_") ||
        item.subfolder.equals("frames", ignoreCase = true)
