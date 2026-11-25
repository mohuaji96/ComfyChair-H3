package sh.hnet.comfychair.ui.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import sh.hnet.comfychair.ui.components.generation.SourceSwitchOverlay
import sh.hnet.comfychair.ui.components.generation.SourceSwitchAnchors
import sh.hnet.comfychair.ui.components.generation.LocalSourceSwitchAnchors
import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.navigation.SettingsRoute
import sh.hnet.comfychair.queue.H3RelayStatus
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.queue.JobStatus
import sh.hnet.comfychair.ui.components.ErrorSnackbars
import sh.hnet.comfychair.ui.components.LocalAppSnackbarHostState
import sh.hnet.comfychair.ui.components.LocalCoveredByMediaViewer
import sh.hnet.comfychair.ui.components.LocalMediaOrigins
import sh.hnet.comfychair.ui.components.LocalMediaViewer
import sh.hnet.comfychair.ui.components.MainNavigationBar
import sh.hnet.comfychair.ui.components.MediaOrigins
import sh.hnet.comfychair.ui.components.MediaViewerOpener
import sh.hnet.comfychair.ui.components.MediaViewerOverlay
import sh.hnet.comfychair.ui.components.SourceMedia
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.UseAsSource
import sh.hnet.comfychair.ui.components.WhatsNew
import sh.hnet.comfychair.ui.screens.AboutSettingsScreen
import sh.hnet.comfychair.ui.screens.ApiProviderEditScreen
import sh.hnet.comfychair.ui.screens.ApiProviderListScreen
import sh.hnet.comfychair.ui.screens.GalleryScreen
import sh.hnet.comfychair.ui.screens.H3WorkspaceScreen
import sh.hnet.comfychair.ui.screens.ImageToImageScreen
import sh.hnet.comfychair.ui.screens.ImageToVideoScreen
import sh.hnet.comfychair.ui.screens.PromptSettingsScreen
import sh.hnet.comfychair.ui.screens.ServerSettingsScreen
import sh.hnet.comfychair.ui.screens.SettingsRootScreen
import sh.hnet.comfychair.ui.screens.StoryComposerScreen
import sh.hnet.comfychair.ui.screens.TagComposerScreen
import sh.hnet.comfychair.ui.screens.TemplateDetailScreen
import sh.hnet.comfychair.ui.screens.TemplateLibraryScreen
import sh.hnet.comfychair.ui.screens.TextToImageScreen
import sh.hnet.comfychair.ui.screens.TextToVideoScreen
import sh.hnet.comfychair.ui.screens.VideoToVideoScreen
import sh.hnet.comfychair.viewmodel.PromptOptimizerViewModel
import sh.hnet.comfychair.ui.screens.LicenseSettingsScreen
import sh.hnet.comfychair.ui.screens.WhatsNewSettingsScreen
import sh.hnet.comfychair.ui.screens.WorkflowDetailPage
import sh.hnet.comfychair.ui.screens.ServerImportScreen
import sh.hnet.comfychair.ui.screens.WorkflowsSettingsScreen
import sh.hnet.comfychair.ui.screens.settings.BackupSettingsPage
import sh.hnet.comfychair.ui.screens.settings.CacheSettingsPage
import sh.hnet.comfychair.ui.screens.settings.ConnectionSettingsPage
import sh.hnet.comfychair.ui.screens.settings.DebugLogSettingsPage
import sh.hnet.comfychair.ui.screens.settings.GenerationSettingsPage
import sh.hnet.comfychair.ui.screens.settings.LanguageSettingsPage
import sh.hnet.comfychair.ui.screens.settings.MediaPrivacySettingsPage
import sh.hnet.comfychair.ui.screens.settings.ResetSettingsPage
import sh.hnet.comfychair.ui.screens.settings.SettingsEventsHost
import sh.hnet.comfychair.ui.screens.settings.WorkflowEditorSettingsPage
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.VideoFrames
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import sh.hnet.comfychair.viewmodel.VideoToVideoViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

/**
 * Main navigation host: the generation screens (Image and Video tabs), the Gallery tab and the Settings tab with its
 * pages, above the navigation bar.
 *
 * @param onLeftSettings Called with the route shown when the user leaves the Settings tab (a change made there may
 *   need the generation screens reloaded)
 */
@Composable
fun MainNavHost(
    generationViewModel: GenerationViewModel,
    textToImageViewModel: TextToImageViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    textToVideoViewModel: TextToVideoViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    videoToVideoViewModel: VideoToVideoViewModel,
    galleryViewModel: GalleryViewModel,
    mediaViewerViewModel: MediaViewerViewModel,
    settingsViewModel: SettingsViewModel,
    workflowManagementViewModel: WorkflowManagementViewModel,
    onLogout: () -> Unit,
    onLeftSettings: (route: String) -> Unit,
    startDestination: String = MainRoute.TextToImage.route,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Sends a generated medium to a screen as its source and switches to that screen the way the navigation bar
    // does. The image screens take an image or a video's last frame, Video to Video a video (at most 100 MB).
    // Launched on the composition's (main) scope; the loaders move their reading to IO themselves, which lint's thread
    // inference (ThreadConstraint, AGP 9.5) takes for the launched block running on a worker thread
    @SuppressLint("ThreadConstraint")
    val useAsSource: UseAsSource = { target, load ->
        scope.launch {
            val media = load()
            if (media == null) {
                AppErrors.report(context, if (target == SourceTarget.VIDEO_TO_VIDEO) R.string.error_source_video_unreadable else R.string.error_failed_load_image)
                return@launch
            }
            if (media is SourceMedia.TooLargeVideo ||
                (target == SourceTarget.VIDEO_TO_VIDEO && media is SourceMedia.Video &&
                    media.bytes.size > VideoToVideoViewModel.MAX_SOURCE_BYTES)) {
                AppErrors.report(context, R.string.error_source_video_too_large)
                return@launch
            }
            val route = when (target) {
                SourceTarget.VIDEO_TO_VIDEO -> {
                    val video = media as? SourceMedia.Video ?: return@launch
                    if (!videoToVideoViewModel.useAsSource(context, video.bytes)) {
                        AppErrors.report(context, R.string.error_source_video_unreadable)
                        return@launch
                    }
                    MainRoute.VideoToVideo
                }
                SourceTarget.IMAGE_TO_IMAGE, SourceTarget.IMAGE_TO_VIDEO -> {
                    val bitmap = when (media) {
                        is SourceMedia.Image -> media.bitmap
                        is SourceMedia.Video -> withContext(Dispatchers.IO) { VideoFrames.lastFrame(media.bytes) }
                        SourceMedia.TooLargeVideo -> null
                    }
                    if (bitmap == null) {
                        AppErrors.report(context, R.string.error_last_frame_unreadable)
                        return@launch
                    }
                    if (target == SourceTarget.IMAGE_TO_IMAGE) {
                        imageToImageViewModel.useAsSource(context, bitmap)
                        MainRoute.ImageToImage
                    } else {
                        imageToVideoViewModel.useAsSource(context, bitmap)
                        MainRoute.ImageToVideo
                    }
                }
                SourceTarget.EXTRACT_FRAME_TO_GALLERY,
                SourceTarget.H3_FIRST_FRAME,
                SourceTarget.H3_LAST_FRAME,
                SourceTarget.H3_REF_IMAGE -> {
                    val bitmap = when (media) {
                        is SourceMedia.Image -> media.bitmap
                        is SourceMedia.Video -> withContext(Dispatchers.IO) { VideoFrames.lastFrame(media.bytes) }
                        SourceMedia.TooLargeVideo -> null
                    }
                    if (bitmap == null) {
                        AppErrors.report(context, R.string.error_last_frame_unreadable)
                        return@launch
                    }
                    val now = System.currentTimeMillis()
                    val filename = "frame_$now.png"
                    val pngBytes = withContext(Dispatchers.IO) {
                        val out = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        val bytes = out.toByteArray()
                        // App-private only: a frame taken here is a working copy for the gallery
                        // and the H3 slots. Writing it into the phone's album unasked filled the
                        // album with frames nobody wanted there; "保存" in the viewer writes on request.
                        val offlineDir = java.io.File(context.filesDir, "gallery_offline")
                        if (offlineDir.exists() || offlineDir.mkdirs()) {
                            java.io.File(offlineDir, filename).writeBytes(bytes)
                        }
                        bytes
                    }
                    val cacheKey = sh.hnet.comfychair.cache.MediaCacheKey("local_$filename", filename)
                    sh.hnet.comfychair.cache.MediaCache.putBitmap(cacheKey, bitmap)
                    sh.hnet.comfychair.repository.GalleryRepository.getInstance().addLocalExtractedFrame(filename, now)
                    if (target == SourceTarget.EXTRACT_FRAME_TO_GALLERY) {
                        android.widget.Toast.makeText(context, "已保存当前帧到图库「抽帧」", android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val uploaded = withContext(Dispatchers.IO) {
                        val client = sh.hnet.comfychair.connection.ConnectionManager.clientOrNull
                        if (client == null) filename
                        else kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                            client.uploadImage(pngBytes, filename, overwrite = true, returnFullPath = false) { ok, serverName, _, _ ->
                                if (cont.isActive) cont.resume(if (ok && !serverName.isNullOrBlank()) serverName else filename) {}
                            }
                        }
                    }
                    val slot = when (target) {
                        SourceTarget.H3_FIRST_FRAME -> "first"
                        SourceTarget.H3_LAST_FRAME -> "last"
                        else -> "ref"
                    }
                    context.getSharedPreferences("h3_pending", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("sourceSlot", slot)
                        .putString("sourceFile", uploaded)
                        .apply()
                    MainRoute.H3Workspace
                }
            }
            navController.navigateToScreen(route)
        }
    }

    // The media viewer, over everything below (navigation bar included)
    val viewerOpen by mediaViewerViewModel.isOpen.collectAsState()
    val mediaViewer = remember(mediaViewerViewModel) { MediaViewerOpener(context, mediaViewerViewModel) }
    val mediaOrigins = remember { MediaOrigins() }

    val resources = LocalResources.current
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val currentScreen = mainRouteOf(currentRoute)
    val currentTab = currentScreen?.outputTab
    val sourceSwitchAnchors = remember { SourceSwitchAnchors() }

    // A change in Settings may need the generation screens reloaded: tell the activity when the user leaves the tab
    var wasInSettings by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        val inSettings = isSettingsRoute(currentRoute)
        if (wasInSettings && !inSettings && currentRoute != null) onLeftSettings(currentRoute)
        wasInSettings = inSettings
    }

    // The source used last in each tab, so the tab opens where the user left it
    var lastImageRoute by rememberSaveable { mutableStateOf(MainRoute.TextToImage.route) }
    var lastVideoRoute by rememberSaveable { mutableStateOf(MainRoute.TextToVideo.route) }
    var lastPromptRoute by rememberSaveable { mutableStateOf(MainRoute.TagComposer.route) }
    LaunchedEffect(currentScreen) {
        when (currentScreen?.outputTab) {
            OutputTab.IMAGE -> lastImageRoute = currentScreen.route
            OutputTab.VIDEO -> lastVideoRoute = currentScreen.route
            null -> if (currentScreen == MainRoute.TagComposer || currentScreen == MainRoute.StoryComposer) {
                lastPromptRoute = currentScreen.route
            }
        }
    }

    // Results of your jobs that finished on a screen not shown: a dot on its tab until visited, and a notice
    val queueState by JobRegistry.queueState.collectAsState()
    val unseenResults = remember { mutableStateListOf<OutputTab>() }
    LaunchedEffect(currentTab) { currentTab?.let { unseenResults.remove(it) } }
    val ownJobsPerTab = OutputTab.entries.associateWith { tab ->
        queueState.ownJobs.values.count {
            (it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING) && routeForOwner(it.ownerId)?.outputTab == tab
        }
    }
    val h3JobCount = queueState.ownJobs.values.count {
        (it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING) && it.ownerId == "H3_WORKSPACE"
    }
    // 连拍的交接棒（跑完一段 → 截尾帧 → 交下一段）住在 H3 页面里。页签一换页面就被销毁，当前段
    // 跑完便没人接手，链条断在半路。所以只要 H3 还有任务在跑/排队，或连拍链没走完，就把这页留在
    // 组合里——只是缩成 0 尺寸藏起来，逻辑照跑；回到 H3 时状态还在原处。
    val relayDoneNow by H3RelayStatus.done.collectAsState()
    val relayLeftNow by H3RelayStatus.remaining.collectAsState()
    val h3HasWork = h3JobCount > 0 || relayDoneNow + relayLeftNow > 0
    var h3EverShown by rememberSaveable { mutableStateOf(false) }
    val h3OnScreen = currentScreen == MainRoute.H3Workspace
    LaunchedEffect(h3OnScreen) { if (h3OnScreen) h3EverShown = true }
    val promptViewModel: PromptOptimizerViewModel = viewModel()
    val transitions = rememberScreenTransitions()
    val tabReselects = remember { MutableSharedFlow<MainRoute>(extraBufferCapacity = 1) }
    // A screen shown anew has started its transition by the time its entry is current
    LaunchedEffect(currentEntry?.id) { transitions.reopening = false }
    val snackbarHostState = remember { SnackbarHostState() }
    // Read when a result arrives, not when the collector started
    val shownScreen by rememberUpdatedState(currentScreen)
    LaunchedEffect(Unit) {
        JobRegistry.readyOwners.collect { ownerId ->
            val route = routeForOwner(ownerId) ?: return@collect
            val tab = route.outputTab ?: return@collect
            if (route == shownScreen) return@collect
            if (tab != shownScreen?.outputTab && tab !in unseenResults) unseenResults.add(tab)
            launch {
                val message = resources.getString(if (tab == OutputTab.VIDEO) R.string.msg_video_ready else R.string.msg_image_ready)
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = resources.getString(R.string.button_view),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) navController.navigateToScreen(route)
            }
        }
    }

    // After an update, once: the new version, with What's new (Settings › About › What's new, Back retraces them)
    LaunchedEffect(Unit) {
        val version = WhatsNew.takeUpdateNotice(context) ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = resources.getString(R.string.whats_new_updated_snackbar, version),
            actionLabel = resources.getString(R.string.whats_new_title),
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) {
            navController.navigateToScreen(MainRoute.Settings)
            navController.navigate(SettingsRoute.About.route)
            navController.navigate(SettingsRoute.WhatsNew.route)
        }
    }

    // The app's errors, here unless the media viewer covers this screen (it shows them itself)
    ErrorSnackbars(snackbarHostState, active = !viewerOpen)

    // The gallery's selection toolbar takes the navigation bar's place
    val galleryState by galleryViewModel.uiState.collectAsState()
    val gallerySelecting = currentScreen == MainRoute.Gallery && galleryState.isSelectionMode

    CompositionLocalProvider(
        LocalMediaViewer provides mediaViewer,
        LocalMediaOrigins provides mediaOrigins,
        LocalAppSnackbarHostState provides snackbarHostState
    ) {
        Box(Modifier.fillMaxSize()) {
            // Video players under the viewer step aside while it's open (the player is shared)
            CompositionLocalProvider(LocalCoveredByMediaViewer provides viewerOpen) {
                Scaffold(
                    bottomBar = {
                        AnimatedVisibility(
                            visible = !gallerySelecting,
                            enter = expandVertically(expandFrom = Alignment.Top),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            MainNavigationBar(
                                navController = navController,
                                lastImageRoute = mainRouteOf(lastImageRoute) ?: MainRoute.TextToImage,
                                lastVideoRoute = mainRouteOf(lastVideoRoute) ?: MainRoute.TextToVideo,
                                lastPromptRoute = mainRouteOf(lastPromptRoute) ?: MainRoute.TagComposer,
                                ownJobs = ownJobsPerTab,
                                h3JobCount = h3JobCount,
                                unseenResults = unseenResults.toSet(),
                                onReopen = { route ->
                                    if (currentEntry?.destination?.route == route.route) {
                                        // On the screen itself: it scrolls back to its top
                                        tabReselects.tryEmit(route)
                                    } else {
                                        transitions.reopening = true
                                        navController.reopenScreen(route)
                                    }
                                }
                            )
                        }
                    },
                    snackbarHost = {
                        // Above the generation screens' controls (prompt and Generate), so it doesn't cover them
                        SnackbarHost(snackbarHostState) { data ->
                            // Rounded and inset like the preview; standard snackbar colours
                            Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    },
                    modifier = modifier.imePadding()
                ) { paddingValues ->
                    // The source switch shows the other screens of its tab
                    CompositionLocalProvider(
                        LocalTabReselects provides tabReselects,
                        LocalScreenNavigator provides { route: MainRoute -> navController.navigateToScreen(route) },
                        LocalSourceSwitchAnchors provides sourceSwitchAnchors
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            NavHost(
                                navController = navController,
                                startDestination = startDestination,
                                modifier = Modifier.padding(paddingValues),
                                enterTransition = { transitions.enter(initialState.destination.route, targetState.destination.route, back = false) },
                                exitTransition = { transitions.exit(initialState.destination.route, targetState.destination.route, back = false) },
                                popEnterTransition = { transitions.enter(initialState.destination.route, targetState.destination.route, back = true) },
                                popExitTransition = { transitions.exit(initialState.destination.route, targetState.destination.route, back = true) },
                                // The back gesture runs the same transitions (Navigation 2.10 otherwise fades and shrinks)
                                predictivePopEnterTransition = { transitions.enter(initialState.destination.route, targetState.destination.route, back = true) },
                                predictivePopExitTransition = { transitions.exit(initialState.destination.route, targetState.destination.route, back = true) }
                            ) {
                                composable(MainRoute.TextToImage.route) {
                                    TextToImageScreen(
                                        generationViewModel = generationViewModel,
                                        textToImageViewModel = textToImageViewModel,
                                        onUseAsSource = useAsSource
                                    )
                                }

                                composable(MainRoute.ImageToImage.route) {
                                    ImageToImageScreen(
                                        generationViewModel = generationViewModel,
                                        imageToImageViewModel = imageToImageViewModel,
                                        onUseAsSource = useAsSource
                                    )
                                }

                                composable(MainRoute.TextToVideo.route) {
                                    TextToVideoScreen(
                                        generationViewModel = generationViewModel,
                                        textToVideoViewModel = textToVideoViewModel,
                                        onUseAsSource = useAsSource
                                    )
                                }

                                // H3 workspace: drawn by the keep-alive slot just below this NavHost, so
                                // that leaving the tab mid-run does not tear the page — and the 连拍 chain
                                // it drives — down. The destination stays registered for the back stack.
                                composable(MainRoute.H3Workspace.route) { }

                                // Prompt composer tabs: TagComposer <-> StoryComposer
                                composable(MainRoute.TagComposer.route) {
                                    TagComposerScreen(
                                        onOpenStory = { navController.navigateToScreen(MainRoute.StoryComposer) }
                                    )
                                }

                                composable(MainRoute.StoryComposer.route) {
                                    StoryComposerScreen(
                                        onBack = { navController.navigateToScreen(MainRoute.TagComposer) }
                                    )
                                }

                                // Server history; the same GenerationViewModel as the generation screens
                                composable(MainRoute.Gallery.route) {
                                    GalleryScreen(
                                        generationViewModel = generationViewModel,
                                        galleryViewModel = galleryViewModel,
                                        onNavigateToSettings = { navController.navigateToScreen(MainRoute.Settings) },
                                        onLogout = onLogout
                                    )
                                }

                                // Settings screen: opened from the top-bar gear icon of any main tab (Back returns to the tab)
                                composable(MainRoute.Settings.route) {
                                    SettingsRootScreen(
                                        viewModel = settingsViewModel,
                                        onOpenPage = { page -> navController.navigate(page.route) },
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                                composable(SettingsRoute.Server.route) {
                                    ServerSettingsScreen(
                                        viewModel = settingsViewModel,
                                        onNavigateBack = { navController.popBackStack() },
                                        onLogout = onLogout
                                    )
                                }
                                composable(SettingsRoute.Workflows.route) {
                                    WorkflowsSettingsScreen(
                                        viewModel = workflowManagementViewModel,
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenDetails = { id -> navController.navigate(SettingsRoute.WorkflowDetail.of(id)) },
                                        onImportFromServer = { navController.navigate(SettingsRoute.ServerImport.route) }
                                    )
                                }
                                composable(SettingsRoute.ServerImport.route) {
                                    ServerImportScreen(
                                        viewModel = workflowManagementViewModel,
                                        onNavigateBack = { navController.popBackStack() },
                                        // Over this page: Back returns here to import the next one
                                        onOpenDetails = { id -> navController.navigate(SettingsRoute.WorkflowDetail.of(id)) }
                                    )
                                }
                                composable(
                                    SettingsRoute.WorkflowDetail.route,
                                    arguments = listOf(navArgument(SettingsRoute.WorkflowDetail.ARG) { type = NavType.StringType })
                                ) { entry ->
                                    WorkflowDetailPage(
                                        viewModel = workflowManagementViewModel,
                                        workflowId = entry.arguments?.getString(SettingsRoute.WorkflowDetail.ARG).orEmpty(),
                                        onNavigateBack = { navController.popBackStack() },
                                        // A copy replaces this page, so Back returns to the list
                                        onOpenDetails = { id ->
                                            navController.navigate(SettingsRoute.WorkflowDetail.of(id)) {
                                                popUpTo(SettingsRoute.WorkflowDetail.route) { inclusive = true }
                                            }
                                        }
                                    )
                                }
                                // The app's and the data's topic pages
                                val back: () -> Unit = { navController.popBackStack() }
                                composable(SettingsRoute.Connection.route) { ConnectionSettingsPage(settingsViewModel, back) }
                                composable(SettingsRoute.Generation.route) { GenerationSettingsPage(settingsViewModel, back) }
                                composable(SettingsRoute.MediaPrivacy.route) { MediaPrivacySettingsPage(settingsViewModel, back) }
                                composable(SettingsRoute.Language.route) { LanguageSettingsPage(back) }
                                composable(SettingsRoute.WorkflowEditor.route) { WorkflowEditorSettingsPage(settingsViewModel, back) }
                                composable(SettingsRoute.Prompt.route) {
                                    PromptSettingsScreen(
                                        onOpenProviders = { navController.navigate(SettingsRoute.PromptProviders.route) },
                                        onOpenTemplates = { navController.navigate(SettingsRoute.PromptTemplates.route) },
                                        onNavigateToGeneration = back,
                                        onLogout = onLogout,
                                        viewModel = promptViewModel
                                    )
                                }
                                composable(SettingsRoute.PromptProviders.route) {
                                    ApiProviderListScreen(
                                        onBack = back,
                                        onEdit = { id -> navController.navigate(SettingsRoute.PromptProviderEdit.of(id)) },
                                        viewModel = promptViewModel
                                    )
                                }
                                composable(
                                    route = SettingsRoute.PromptProviderEdit.route,
                                    arguments = listOf(navArgument("providerId") { type = NavType.StringType })
                                ) { entry ->
                                    ApiProviderEditScreen(
                                        providerId = entry.arguments?.getString("providerId").orEmpty(),
                                        onBack = back,
                                        onDeleted = back,
                                        viewModel = promptViewModel
                                    )
                                }
                                composable(SettingsRoute.PromptTemplates.route) {
                                    TemplateLibraryScreen(
                                        onBack = back,
                                        onOpenDetail = { id -> navController.navigate(SettingsRoute.PromptTemplateDetail.of(id)) }
                                    )
                                }
                                composable(
                                    route = SettingsRoute.PromptTemplateDetail.route,
                                    arguments = listOf(navArgument("templateId") { type = NavType.StringType })
                                ) { entry ->
                                    TemplateDetailScreen(
                                        templateId = entry.arguments?.getString("templateId").orEmpty(),
                                        onBack = back
                                    )
                                }
                                composable(SettingsRoute.Backup.route) { BackupSettingsPage(settingsViewModel, back) }
                                composable(SettingsRoute.Cache.route) { CacheSettingsPage(settingsViewModel, back) }
                                composable(SettingsRoute.DebugLog.route) { DebugLogSettingsPage(settingsViewModel, back) }
                                composable(SettingsRoute.Reset.route) { ResetSettingsPage(settingsViewModel, back) }
                                composable(SettingsRoute.About.route) {
                                    AboutSettingsScreen(
                                        onNavigateBack = back,
                                        onOpenWhatsNew = { navController.navigate(SettingsRoute.WhatsNew.route) },
                                        onOpenLicense = { navController.navigate(SettingsRoute.License.route) }
                                    )
                                }
                                composable(SettingsRoute.WhatsNew.route) { WhatsNewSettingsScreen(back) }
                                composable(SettingsRoute.License.route) { LicenseSettingsScreen(back) }

                                composable(MainRoute.ImageToVideo.route) {
                                    ImageToVideoScreen(
                                        generationViewModel = generationViewModel,
                                        imageToVideoViewModel = imageToVideoViewModel,
                                        onUseAsSource = useAsSource
                                    )
                                }

                                composable(MainRoute.VideoToVideo.route) {
                                    VideoToVideoScreen(
                                        generationViewModel = generationViewModel,
                                        videoToVideoViewModel = videoToVideoViewModel,
                                        onUseAsSource = useAsSource
                                    )
                                }
                            }
                            // H3, kept composed while it has work in flight: hidden as a zero-size box on
                            // the other tabs, so its 连拍 chain and the sampling preview survive the switch.
                            // Must apply paddingValues like NavHost, otherwise it ignores the top/bottom bars
                            // and collides directly into the phone's system status bar!
                            if (h3EverShown && (h3OnScreen || h3HasWork)) {
                                Box(
                                    modifier = if (h3OnScreen) {
                                        Modifier.fillMaxSize().padding(paddingValues)
                                    } else {
                                        Modifier.size(0.dp)
                                    }
                                ) {
                                    H3WorkspaceScreen(
                                        generationViewModel = generationViewModel,
                                        onOpenWorkflowSettings = { navController.navigate(SettingsRoute.Workflows.route) },
                                        isVisible = h3OnScreen
                                    )
                                }
                            }
                            // One switch for the tab over the screens, so choosing a source animates it as a toggle
                            // group does; each screen's top bar keeps its place (SourceSwitchPlaceholder)
                            SourceSwitchOverlay(currentScreen, sourceSwitchAnchors)
                        }
                    }
                }
            }
            // The queue drawer is hosted once, here, instead of inside each tab: every pill opens the
            // same sheet, and the H3 page kept alive in the background can no longer bring a second
            // copy of it to the front.
            if (sh.hnet.comfychair.ui.components.QueueSheetController.visible) {
                sh.hnet.comfychair.ui.components.generation.QueueSheet(
                    generationViewModel = generationViewModel,
                    onDismiss = { sh.hnet.comfychair.ui.components.QueueSheetController.visible = false }
                )
            }
            // Messages and the restore confirmation of every settings page
            SettingsEventsHost(settingsViewModel)
            MediaViewerOverlay(mediaViewerViewModel, onUseAsSource = { target, media -> useAsSource(target) { media } })
        }
    }
}

/**
 * Transitions between screens, timed by the theme's (standard) motion scheme:
 * - another tab: fade through (the old screen fades out quickly, the new one fades in growing slightly);
 * - another source in the same tab: a quick crossfade, as it's a choice within one place;
 * - Settings list and its pages: shared axis X (the page comes in from the end, and goes back there on Back);
 * - a screen shown anew from its tab (tapped again): as another tab.
 */
private class ScreenTransitions(
    private val fastFade: FiniteAnimationSpec<Float>,
    private val fade: FiniteAnimationSpec<Float>,
    private val scale: FiniteAnimationSpec<Float>,
    private val slide: FiniteAnimationSpec<IntOffset>,
    private val axisOffsetPx: Int
) {
    private enum class Kind { TAB, SOURCE, AXIS }

    /** A screen shown anew from its tab ([reopenScreen]): entered as from another tab; cleared once it's shown */
    var reopening = false

    private fun kind(from: String?, to: String?): Kind {
        if (reopening) return Kind.TAB
        val fromScreen = mainRouteOf(from)
        val toScreen = mainRouteOf(to)
        return when {
            isSettingsRoute(from) && isSettingsRoute(to) -> Kind.AXIS
            fromScreen?.outputTab != null && fromScreen.outputTab == toScreen?.outputTab -> Kind.SOURCE
            else -> Kind.TAB
        }
    }

    fun enter(from: String?, to: String?, back: Boolean): EnterTransition = when (kind(from, to)) {
        Kind.TAB -> fadeIn(fade) + scaleIn(scale, initialScale = 0.92f)
        Kind.SOURCE -> fadeIn(fastFade)
        Kind.AXIS -> slideInHorizontally(slide) { if (back) -axisOffsetPx else axisOffsetPx } + fadeIn(fade)
    }

    fun exit(from: String?, to: String?, back: Boolean): ExitTransition = when (kind(from, to)) {
        Kind.TAB, Kind.SOURCE -> fadeOut(fastFade)
        Kind.AXIS -> slideOutHorizontally(slide) { if (back) axisOffsetPx else -axisOffsetPx } + fadeOut(fastFade)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberScreenTransitions(): ScreenTransitions {
    val motion = MaterialTheme.motionScheme
    val axisOffsetPx = with(LocalDensity.current) { 30.dp.roundToPx() }
    return remember(motion, axisOffsetPx) {
        ScreenTransitions(
            fastFade = motion.fastEffectsSpec(),
            fade = motion.defaultEffectsSpec(),
            scale = motion.defaultSpatialSpec(),
            slide = motion.defaultSpatialSpec(),
            axisOffsetPx = axisOffsetPx
        )
    }
}
