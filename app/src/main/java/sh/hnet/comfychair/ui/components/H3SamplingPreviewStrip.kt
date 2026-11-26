package sh.hnet.comfychair.ui.components

import android.graphics.drawable.Animatable
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.InMemoryVideo
import sh.hnet.comfychair.connection.WebSocketMessage

/**
 * 采样预览: the frame the sampler is on right now, sitting directly above the 生成 bar.
 *
 * Three sizes, because the page is a form first and a monitor second: a one-line strip that costs
 * nothing, a small card with the picture and the step, and a full-width view for judging composition
 * before the run is over. It collapses rather than closing, so a glance costs one tap each way.
 */
@Composable
fun H3SamplingPreviewStrip(
    frame: WebSocketMessage.SamplingPreview?,
    running: Boolean,
    runKey: Any? = null,
    fallbackStep: Int = 0,
    fallbackTotal: Int = 0,
    onCancel: (() -> Unit)? = null,
    /** Whether the picture is wanted; false folds the strip into a one-line "tap to bring it back". */
    visible: Boolean = true,
    /** Same switch as 设置 → 生成 → H3 采样预览, reachable here so it costs one tap mid-run. */
    onTogglePreview: ((Boolean) -> Unit)? = null
) {
    if (!running) return
    // A new run opens the card again: the collapse is a decision about one run's picture, not a
    // setting that would hide the next one.
    var collapsed by remember(runKey) { mutableStateOf(false) }
    var zoomed by remember(runKey) { mutableStateOf(false) }
    // Turning the picture back on shows the card, not the folded line it was left in.
    LaunchedEffect(visible) { if (visible) collapsed = false }

    val step = frame?.step?.takeIf { it > 0 } ?: fallbackStep
    val total = frame?.total?.takeIf { it > 0 } ?: fallbackTotal
    val stepText = when {
        frame == null && step > 0 && total > 0 -> stringResource(R.string.smp_step_sampling, step, total)
        frame == null -> stringResource(R.string.smp_waiting_first)
        total > 0 && step > 0 -> stringResource(R.string.smp_step_of, step, total)
        // Step 0 initial noise preview or the server's own preview without a step counter.
        frame.step == 0 && frame.total > 0 -> stringResource(R.string.smp_initial_noise, frame.total)
        step <= 0 -> stringResource(R.string.smp_current_frame)
        else -> stringResource(R.string.smp_step_only, step)
    }

    // Off and folded both end up as one thin line; they differ in what a tap does and in what the
    // line says, so "I closed it" never has to become "I lost it until the next run".
    if (!visible || collapsed) {
        val foldLabel = if (visible) stringResource(R.string.smp_fold_visible, stepText)
        else stringResource(R.string.smp_fold_hidden)
        Surface(
            shadowElevation = 2.dp,
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    if (visible) collapsed = false else onTogglePreview?.invoke(true)
                }
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (visible) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                } else {
                    EyeGlyph(open = false, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(foldLabel, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }

    Surface(
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Thumbnail(frame, playVideo = !zoomed)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.smp_title), style = MaterialTheme.typography.labelMedium)
                Text(stepText, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                frame?.stepMillis?.let {
                    Text(stringResource(R.string.smp_seconds_per_step, "%.1f".format(it / 1000.0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { zoomed = true }, enabled = frame != null) {
                Icon(Icons.Filled.OpenInFull, contentDescription = stringResource(R.string.smp_cd_zoom))
            }
            if (onTogglePreview != null) {
                IconButton(onClick = { onTogglePreview(false) }) {
                    EyeGlyph(open = true, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            IconButton(onClick = { collapsed = true }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.smp_cd_collapse))
            }
        }
    }

    if (zoomed && frame != null) {
        Dialog(onDismissRequest = { zoomed = false }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 放大不等于变清楚：避免拉伸到全屏导致插值模糊，同时保持真实宽高比。
                    val density = LocalDensity.current
                    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                    val room = screenWidth - 40.dp
                    val aspect = aspectOf(frame).coerceIn(0.2f, 5.0f)
                    val maxH = 520.dp
                    val rawW = if (frame.width > 0) with(density) { frame.width.toDp() } else room
                    val rawH = if (frame.height > 0) with(density) { frame.height.toDp() } else (rawW / aspect)
                    val baseW = if (rawW < 240.dp) minOf(room, 240.dp) else rawW
                    val baseH = baseW / aspect
                    val scale = minOf(
                        1f,
                        if (baseW > room) room.value / baseW.value else 1f,
                        if (baseH > maxH) maxH.value / baseH.value else 1f
                    )
                    Box(
                        Modifier
                            .width(baseW * scale)
                            .height(baseH * scale)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                    ) {
                        PreviewPicture(
                            frame = frame,
                            modifier = Modifier.fillMaxSize(),
                            playVideo = true
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stepText + (frame.stepMillis?.let {
                            " · " + stringResource(
                                R.string.smp_seconds_per_step,
                                "%.1f".format(it / 1000.0)
                            )
                        } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (onCancel != null) TextButton(onClick = {
                            zoomed = false
                            onCancel()
                        }) { Text(stringResource(R.string.smp_cancel_run)) }
                        IconButton(onClick = { zoomed = false }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.smp_cd_close)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A small eye, drawn rather than pulled from the icon pack: the theme only bundles the core icon set,
 * which has no visibility glyph. An almond outline plus a pupil, and a slash when closed.
 */
@Composable
private fun EyeGlyph(open: Boolean, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.09f
        val outline = Path().apply {
            moveTo(w * 0.05f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.10f, w * 0.95f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.90f, w * 0.05f, h * 0.5f)
            close()
        }
        drawPath(outline, tint, style = Stroke(width = stroke))
        drawCircle(tint, radius = h * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
        if (!open) {
            drawLine(
                color = tint,
                start = Offset(w * 0.12f, h * 0.86f),
                end = Offset(w * 0.88f, h * 0.14f),
                strokeWidth = stroke * 1.2f
            )
        }
    }
}

@Composable
private fun Thumbnail(frame: WebSocketMessage.SamplingPreview?, playVideo: Boolean) {
    Box(
        Modifier
            .size(width = 108.dp, height = 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (frame == null) {
            Text(stringResource(R.string.smp_waiting_short), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            PreviewPicture(
                frame = frame,
                modifier = Modifier.fillMaxSize().aspectRatio(aspectOf(frame)),
                playVideo = playVideo
            )
        }
    }
}

/**
 * Draws a step's picture or short clip:
 * - `video/mp4` (fragmented MP4 from `ModelPreviewOverrideKJ` when PyAV/x264 is available): played
 *   from RAM via Media3 ExoPlayer on a TextureView, with the extracted poster bitmap drawn underneath
 *   so switching from step N to step N+1 never flashes black;
 * - `image/webp` (`AnimatedImageDrawable`): handed to an ImageView and explicitly started;
 * - `image/jpeg` / `image/png`: drawn as a still bitmap.
 */
@OptIn(UnstableApi::class)
@Composable
private fun PreviewPicture(
    frame: WebSocketMessage.SamplingPreview,
    modifier: Modifier,
    playVideo: Boolean = true
) {
    val videoBytes = frame.videoBytes
    val animated = frame.animated
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Poster underneath: shown for stills, and acts as an instant backdrop while a video clip swaps.
        if (frame.bitmap != null && animated == null) {
            Image(
                bitmap = frame.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.smp_title),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        when {
            videoBytes != null && playVideo -> {
                val context = LocalContext.current
                val player = remember {
                    ExoPlayer.Builder(context.applicationContext)
                        .setMediaSourceFactory(
                            DefaultMediaSourceFactory(InMemoryVideo.dataSourceFactory(context))
                        )
                        .build()
                        .apply {
                            repeatMode = Player.REPEAT_MODE_ONE
                            volume = 0f
                            playWhenReady = true
                        }
                }
                LaunchedEffect(videoBytes) {
                    val uri = InMemoryVideo.uriFor("h3_sampling_preview_${videoBytes.hashCode()}") { videoBytes }
                    player.setMediaItem(MediaItem.fromUri(uri))
                    player.prepare()
                    player.playWhenReady = true
                }
                DisposableEffect(player) {
                    onDispose { player.release() }
                }
                PlayerSurface(
                    player = player,
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    modifier = Modifier.fillMaxSize()
                )
            }
            animated != null -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { view ->
                    if (view.drawable !== animated) {
                        view.setImageDrawable(animated)
                    }
                    (animated as? Animatable)?.start()
                },
                onRelease = { view ->
                    (view.drawable as? Animatable)?.stop()
                    view.setImageDrawable(null)
                }
            )
        }
    }
}

/** Width over height, whichever way the step arrived. */
private fun aspectOf(frame: WebSocketMessage.SamplingPreview): Float {
    if (frame.width > 0 && frame.height > 0) return frame.width.toFloat() / frame.height
    frame.bitmap?.let { if (it.height > 0) return it.width.toFloat() / it.height }
    frame.animated?.let { if (it.intrinsicHeight > 0) return it.intrinsicWidth.toFloat() / it.intrinsicHeight }
    return 16f / 9f
}
