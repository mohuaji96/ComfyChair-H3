package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.BottomAppBar
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.queue.JobStatus
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel

/**
 * Main navigation bar for the app with 4 destinations:
 * - Text-to-Image (icon button)
 * - Image-to-Image (icon button)
 * - Text-to-Video (icon button)
 * - Image-to-Video (icon button)
 *
 * FAB launches Gallery activity.
 *
 * Uses BottomAppBar with navigation buttons on left and FAB on right.
 */
@Composable
fun MainNavigationBar(
    navController: NavController,
    onNavigateToGallery: () -> Unit,
    onTabReselect: (MainRoute) -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Observe gallery items to show latest thumbnail in FAB
    val galleryItems by GalleryRepository.getInstance().galleryItems.collectAsState()
    val latestItem = galleryItems.firstOrNull()

    // One badge per screen: the jobs that screen started, so the count answers what you are waiting on
    val queueState by JobRegistry.queueState.collectAsState()
    val activeJobs = queueState.ownJobs.values.filter {
        it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING
    }
    val t2iCount = activeJobs.count { it.ownerId == TextToImageViewModel.OWNER_ID }
    val i2iCount = activeJobs.count { it.ownerId == ImageToImageViewModel.OWNER_ID }
    val t2vCount = activeJobs.count { it.ownerId == TextToVideoViewModel.OWNER_ID }
    val i2vCount = activeJobs.count { it.ownerId == ImageToVideoViewModel.OWNER_ID }
    val h3Count = activeJobs.count { it.ownerId == "H3_WORKSPACE" }

    // Lazy load thumbnail for FAB - keep previous thumbnail until new one is ready
    val displayedThumbnail = rememberRetainedBitmap(
        cacheKey = latestItem?.toCacheKey(),
        isVideo = latestItem?.isVideo ?: false,
        subfolder = latestItem?.subfolder ?: "",
        type = latestItem?.type ?: ""
    )

    BottomAppBar(
        actions = {
            // Add left padding to align with screen content (16dp - default 4dp = 12dp extra)
            Spacer(modifier = Modifier.width(12.dp))

            // Text to Image
            if (currentRoute == MainRoute.TextToImage.route) {
                FilledIconButton(
                    onClick = { onTabReselect(MainRoute.TextToImage) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    NavIcon(painterResource(R.drawable.text_to_image_24px), stringResource(R.string.nav_text_to_image), t2iCount)
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.TextToImage.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    NavIcon(painterResource(R.drawable.text_to_image_24px), stringResource(R.string.nav_text_to_image), t2iCount, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Image to Image
            if (currentRoute == MainRoute.ImageToImage.route) {
                FilledIconButton(
                    onClick = { onTabReselect(MainRoute.ImageToImage) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    NavIcon(painterResource(R.drawable.image_to_image_24px), stringResource(R.string.nav_image_to_image), i2iCount)
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.ImageToImage.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    NavIcon(painterResource(R.drawable.image_to_image_24px), stringResource(R.string.nav_image_to_image), i2iCount, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Text to Video
            if (currentRoute == MainRoute.TextToVideo.route) {
                FilledIconButton(
                    onClick = { onTabReselect(MainRoute.TextToVideo) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    NavIcon(painterResource(R.drawable.text_to_video_24px), stringResource(R.string.nav_text_to_video), t2vCount)
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.TextToVideo.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    NavIcon(painterResource(R.drawable.text_to_video_24px), stringResource(R.string.nav_text_to_video), t2vCount, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Image to Video
            if (currentRoute == MainRoute.ImageToVideo.route) {
                FilledIconButton(
                    onClick = { onTabReselect(MainRoute.ImageToVideo) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    NavIcon(painterResource(R.drawable.image_to_video_24px), stringResource(R.string.nav_image_to_video), i2vCount)
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.ImageToVideo.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    NavIcon(painterResource(R.drawable.image_to_video_24px), stringResource(R.string.nav_image_to_video), i2vCount, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // H3 Workspace (independent four-mode entry)
            if (currentRoute == MainRoute.H3Workspace.route) {
                FilledIconButton(
                    onClick = { onTabReselect(MainRoute.H3Workspace) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.h3_24px),
                        contentDescription = stringResource(R.string.nav_h3_workspace)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.H3Workspace.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    Icon(
                        painter = painterResource(R.drawable.h3_24px),
                        contentDescription = stringResource(R.string.nav_h3_workspace),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Random tag combiner: builds a prompt line, never submits a workflow
            if (currentRoute == MainRoute.TagComposer.route) {
                FilledIconButton(
                    onClick = { onTabReselect(MainRoute.TagComposer) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.tag_combo_24px),
                        contentDescription = stringResource(R.string.nav_tag_composer)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.TagComposer.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    Icon(
                        painter = painterResource(R.drawable.tag_combo_24px),
                        contentDescription = stringResource(R.string.nav_tag_composer),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToGallery,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                if (displayedThumbnail != null) {
                    Image(
                        bitmap = displayedThumbnail.asImageBitmap(),
                        contentDescription = stringResource(R.string.nav_gallery),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Filled.Collections,
                        contentDescription = stringResource(R.string.nav_gallery),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    )
}

/**
 * A navigation glyph with the screen's running job count. The badge only appears while that screen
 * has work in the queue, so the bar stays quiet when nothing is running.
 */
@Composable
private fun NavIcon(
    painter: androidx.compose.ui.graphics.painter.Painter,
    description: String,
    count: Int,
    // Defaults to the content colour of the button it sits in. Color.Unspecified means "keep the
    // drawable's own fill", and these glyphs are filled white — invisible on the light selected pill.
    tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current
) {
    // The button clips to a circle, so the badge has to sit inside the glyph's own corners: a
    // badge pushed past them came out as the sliver in the report.
    // The button clips to a circle, so the badge must stay inside a 28dp box around the glyph: any
    // further out it was cut down to the sliver in the report.
    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(
            painter = painter,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (count > 9) "9+" else count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onError,
                    maxLines = 1
                )
            }
        }
    }
}
