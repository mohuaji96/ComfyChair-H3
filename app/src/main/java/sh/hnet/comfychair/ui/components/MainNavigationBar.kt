package sh.hnet.comfychair.ui.components

import sh.hnet.comfychair.ui.navigation.sources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.ui.navigation.OutputTab
import sh.hnet.comfychair.ui.navigation.mainRouteOf
import sh.hnet.comfychair.ui.navigation.navigateToScreen
import sh.hnet.comfychair.ui.navigation.outputTab

/**
 * Five-tab bottom navigation: 生图 | 视频 | H3 | 提示词 | 图库.
 * Settings is placed in the top app bar gear icon of each main screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainNavigationBar(
    navController: NavController,
    lastImageRoute: MainRoute,
    lastVideoRoute: MainRoute,
    lastPromptRoute: MainRoute = MainRoute.TagComposer,
    ownJobs: Map<OutputTab, Int>,
    h3JobCount: Int = 0,
    unseenResults: Set<OutputTab>,
    /** Show a screen anew, as when first opened (its tab tapped again) */
    onReopen: (MainRoute) -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = mainRouteOf(navBackStackEntry?.destination?.route)
    val currentTab = currentRoute?.outputTab
    val promptSelected = currentRoute == MainRoute.TagComposer || currentRoute == MainRoute.StoryComposer

    ShortNavigationBar {
        TabItem(
            selected = currentTab == OutputTab.IMAGE,
            label = stringResource(R.string.nav_tab_image),
            selectedIcon = Icons.Filled.Image,
            icon = Icons.Outlined.Image,
            badgeCount = ownJobs[OutputTab.IMAGE] ?: 0,
            badgeDot = OutputTab.IMAGE in unseenResults,
            onClick = { navController.navigateToScreen(lastImageRoute) },
            onReselect = { showFirstSource(navController, OutputTab.IMAGE, currentRoute) }
        )
        TabItem(
            selected = currentTab == OutputTab.VIDEO,
            label = stringResource(R.string.nav_tab_video),
            selectedIcon = Icons.Filled.Movie,
            icon = Icons.Outlined.Movie,
            badgeCount = ownJobs[OutputTab.VIDEO] ?: 0,
            badgeDot = OutputTab.VIDEO in unseenResults,
            onClick = { navController.navigateToScreen(lastVideoRoute) },
            onReselect = { showFirstSource(navController, OutputTab.VIDEO, currentRoute) }
        )
        TabItem(
            selected = currentRoute == MainRoute.H3Workspace,
            label = stringResource(R.string.nav_tab_h3),
            selectedIcon = H3TabIcons.Filled,
            icon = H3TabIcons.Outlined,
            badgeCount = h3JobCount,
            onClick = { navController.navigateToScreen(MainRoute.H3Workspace) },
            onReselect = { onReopen(MainRoute.H3Workspace) }
        )
        TabItem(
            selected = promptSelected,
            label = stringResource(R.string.nav_tab_prompt),
            selectedIcon = Icons.Filled.EditNote,
            icon = Icons.Outlined.EditNote,
            onClick = { navController.navigateToScreen(lastPromptRoute) },
            onReselect = {
                if (currentRoute != MainRoute.TagComposer) {
                    navController.navigateToScreen(MainRoute.TagComposer)
                } else {
                    onReopen(MainRoute.TagComposer)
                }
            }
        )
        TabItem(
            selected = currentRoute == MainRoute.Gallery,
            label = stringResource(R.string.nav_gallery),
            selectedIcon = Icons.Filled.PhotoLibrary,
            icon = Icons.Outlined.PhotoLibrary,
            onClick = { navController.navigateToScreen(MainRoute.Gallery) },
            onReselect = { onReopen(MainRoute.Gallery) }
        )
    }
}

/** A tab tapped again: its first source (Text), as the source switch shows it; nothing when that's already shown. */
private fun showFirstSource(navController: NavController, tab: OutputTab, current: MainRoute?) {
    val first = tab.sources.first().route
    if (current != first) navController.navigateToScreen(first)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabItem(
    selected: Boolean,
    label: String,
    selectedIcon: ImageVector,
    icon: ImageVector,
    badgeCount: Int = 0,
    badgeDot: Boolean = false,
    /** Tapped while selected */
    onReselect: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val badgeDescription = when {
        badgeCount > 0 -> pluralStringResource(R.plurals.label_queue_own_jobs, badgeCount, badgeCount)
        badgeDot -> stringResource(R.string.content_description_new_result)
        else -> null
    }
    ShortNavigationBarItem(
        selected = selected,
        onClick = { if (!selected) onClick() else onReselect?.invoke() },
        icon = {
            BadgedBox(
                badge = {
                    when {
                        badgeCount > 0 -> Badge { Text(badgeCount.toString()) }
                        badgeDot -> Badge()
                    }
                }
            ) {
                Icon(if (selected) selectedIcon else icon, contentDescription = null)
            }
        },
        modifier = if (badgeDescription != null) Modifier.semantics { stateDescription = badgeDescription } else Modifier,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    )
}
