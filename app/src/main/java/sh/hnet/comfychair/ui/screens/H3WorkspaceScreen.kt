package sh.hnet.comfychair.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.H3Plan
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.ServerStatusLamp
import sh.hnet.comfychair.workflow.H3WorkflowAdapter
import sh.hnet.comfychair.workflow.ProgressStage

/**
 * A workflow the adapter recognises as H3 plus the modes it can actually run.
 * One template may serve T2V, I2V and FL2V: the mode is a submission-time decision, not a
 * separate file, so those three share one task tab.
 */
private data class H3Entry(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val modes: List<H3Plan.Mode>,
    val format: String,
    /** Templates the workspace should default to (marked in the workflow file itself). */
    val preferred: Boolean
)

/**
 * H3 workspace: two task tabs.
 *
 * The frames tab covers T2V, I2V and FL2V — the model exposes them as one node with two optional
 * keyframes, so the studio derives the concrete mode from which images are filled. Reference
 * video stays on its own tab because it is a different conditioning node.
 */
@Composable
fun H3WorkspaceScreen(
    generationViewModel: GenerationViewModel?,
    onOpenWorkflowSettings: () -> Unit,
    isVisible: Boolean = true
) {
    val version by WorkflowManager.workflowsVersion.collectAsState()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var pickerFor by remember { mutableStateOf<H3Plan.Area?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingStore = remember { sh.hnet.comfychair.workflow.PendingJobStore(context) }

    val tabs = H3Plan.Area.entries
    var choices by remember { mutableStateOf(H3ChoicesStore.load(context)) }

    // A card tapped in 工作流管理 hands over the workflow (and one mode it supports).
    val consumePendingWorkflow: () -> Unit = {
        val prefs = context.getSharedPreferences("h3_pending", android.content.Context.MODE_PRIVATE)
        val workflowId = prefs.getString("workflowId", null)
        if (workflowId != null) {
            val modeName = prefs.getString("mode", "").orEmpty()
            prefs.edit().remove("workflowId").remove("mode").apply()
            val mode = H3Plan.Mode.entries.firstOrNull { it.name == modeName } ?: H3Plan.Mode.T2V
            val area = mode.area
            tabIndex = tabs.indexOf(area).coerceAtLeast(0)
            choices = choices + (area.name to workflowId)
            H3ChoicesStore.save(context, area.name, workflowId)
        }
        val sourceSlot = prefs.getString("sourceSlot", null)
        if (sourceSlot != null) {
            val targetArea = if (sourceSlot == "ref") H3Plan.Area.R2V else H3Plan.Area.FRAMES
            tabIndex = tabs.indexOf(targetArea).coerceAtLeast(0)
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) consumePendingWorkflow()
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                consumePendingWorkflow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Recovery: jobs persisted before a restart or a dropped socket are re-queried by prompt id.
    LaunchedEffect(Unit) {
        val live = sh.hnet.comfychair.connection.ConnectionManager.clientOrNull ?: return@LaunchedEffect
        pendingStore.pending().forEach { job ->
            live.fetchHistory(job.promptId) { json ->
                if (json != null) {
                    val snap = sh.hnet.comfychair.workflow.JobRecovery.parse(json.toString(), job.promptId)
                    if (snap.state == sh.hnet.comfychair.workflow.JobRecovery.State.COMPLETED ||
                        snap.state == sh.hnet.comfychair.workflow.JobRecovery.State.FAILED
                    ) pendingStore.updateState(job.promptId, snap.state.name)
                }
            }
        }
    }
    var overrides by remember { mutableStateOf(ModeOverrideStore.load(context)) }

    val recognised = remember(version) {
        WorkflowManager.getAllWorkflows(WorkflowManager.WorkflowSpace.H3)
            .filter { WorkflowManager.isWorkflowEnabled(it.id) }
            .mapNotNull { workflow ->
            val plan = runCatching { H3Plan.resolve(workflow.jsonContent) }.getOrNull()
            if (plan != null) Triple(workflow, plan.modes, plan.format)
            else {
                val analysis = runCatching { H3WorkflowAdapter.inspectAny(workflow.jsonContent) }.getOrNull()
                    ?: return@mapNotNull null
                val mode = when (analysis.mode) {
                    H3WorkflowAdapter.Mode.T2V -> H3Plan.Mode.T2V
                    H3WorkflowAdapter.Mode.I2V -> H3Plan.Mode.I2V
                    H3WorkflowAdapter.Mode.FL2V -> H3Plan.Mode.FL2V
                    H3WorkflowAdapter.Mode.R2V -> H3Plan.Mode.R2V
                    H3WorkflowAdapter.Mode.AMBIGUOUS -> null
                }
                Triple(workflow, if (mode == null) emptyList() else listOf(mode), analysis.format)
            }
        }
    }
    val inventory = recognised.mapNotNull { (workflow, modes, format) ->
        val resolved = modes.ifEmpty { overrides[workflow.id]?.let { listOf(it) }.orEmpty() }
        if (resolved.isEmpty()) return@mapNotNull null
        val preferred = runCatching {
            org.json.JSONObject(workflow.jsonContent).optBoolean("h3Preferred", false)
        }.getOrDefault(false)
        H3Entry(workflow.id, workflow.name, workflow.isBuiltIn, resolved, format, preferred)
    }
    val undecided = recognised
        .filter { (workflow, modes, _) -> modes.isEmpty() && workflow.id !in overrides }
        .map { it.first }

    val tab = tabs[tabIndex.coerceIn(0, tabs.size - 1)]
    // The shared frames template is the default for T2V/I2V/FL2V: one file that can do all three,
    // which is exactly what this tab is built around. Legacy single-mode templates stay
    // selectable through 工作流.
    val candidates = inventory
        .filter { entry -> entry.modes.any { it.area == tab } }
        .sortedWith(
            compareByDescending<H3Entry> { it.preferred }
                .thenByDescending { if (tab == H3Plan.Area.FRAMES && H3Plan.Mode.FL2V in it.modes) 1 else 0 }
                .thenByDescending { it.builtIn }
        )

    val selected = choices[tab.name]?.let { id -> candidates.firstOrNull { it.id == id } }
        ?: candidates.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.h3_workspace_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            sh.hnet.comfychair.ui.components.QueueIndicatorPill()
            Spacer(Modifier.width(8.dp))
            ServerStatusLamp()
            // The workflow library is reachable from the 工作流 picker in each tab and from
            // 设置；a text button here crowded the same row as the status lamp and the queue pill.
            val navigate = sh.hnet.comfychair.ui.navigation.LocalScreenNavigator.current
            androidx.compose.material3.IconButton(onClick = { navigate(sh.hnet.comfychair.navigation.MainRoute.Settings) }) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.h3_settings)
                )
            }
        }
        // The queue drawer is hosted once by the navigation host.

        // Under the title, above the tabs -- the same slot the four ordinary pages use. It sits
        // outside the tab switch on purpose: a long reference-video run should stay visible while
        // the user edits parameters on the other tab.
        val genState = generationViewModel?.generationState?.collectAsState()?.value
        val jobQueue by JobRegistry.queueState.collectAsState()
        // The registry is the half that survives: `genState` lives in a ViewModel and is gone whenever the
        // process is recreated, while the job keeps running on the server. Asking both meant the bar
        // disappeared for good after a spell in the background; either one saying "running" is enough.
        val runningHere = jobQueue.executingOwnerId == H3_OWNER_ID &&
            (genState?.isGenerating == true || jobQueue.isOwnJobExecuting)
        // The fade-out outlives the job: the state resets to UNKNOWN the moment work ends, so the
        // bar would read "处理中" on its way out. Hold the last real reading for that 120ms.
        var frozen by remember { mutableStateOf(genState) }
        SideEffect {
            if (genState != null && genState.isGenerating) frozen = genState
        }
        AnimatedVisibility(
            visible = runningHere,
            // A bar that appears out of nothing makes the tabs jump; 150ms reads as "pushed down".
            enter = expandVertically(tween(150)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
        ) {
            val state = frozen ?: genState
            val fallbackStarted = jobQueue.executingPromptId?.let { JobRegistry.executionStartedAtMillis(it) } ?: 0L
            val effectiveStarted = (state?.startedAt?.takeIf { it > 0L }) ?: fallbackStarted
            if (state != null) {
                GenerationProgressBar(
                    stage = state.stage,
                    progress = state.progress,
                    maxProgress = state.maxProgress,
                    startedAt = effectiveStarted
                )
            } else {
                // A job restored after the process died has no screen state behind it yet. Say "处理中",
                // but keep the clock ticking from the persisted start timestamp so time is not lost.
                GenerationProgressBar(
                    stage = ProgressStage.UNKNOWN,
                    progress = 0,
                    maxProgress = 0,
                    startedAt = effectiveStarted
                )
            }
        }

        if (undecided.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.h3_pick_core),
                    style = MaterialTheme.typography.labelMedium)
                undecided.forEach { wf ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(wf.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        H3Plan.Mode.entries.forEach { m ->
                            TextButton(onClick = {
                                ModeOverrideStore.save(context, wf.id, m)
                                overrides = overrides + (wf.id to m)
                            }) { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }

        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { index, item ->
                Tab(
                    selected = index == tabIndex,
                    onClick = { tabIndex = index },
                    modifier = Modifier.heightIn(min = 40.dp),
                    text = { Text(item.label, style = MaterialTheme.typography.labelLarge) }
                )
            }
        }

        when {
            selected == null -> MissingArea(tab, onOpenWorkflowSettings)
            else -> Column(Modifier.weight(1f)) {
                BoundWorkflowRow(
                    entry = selected,
                    candidates = candidates,
                    onSwitch = { pickerFor = tab },
                    onOpenSettings = onOpenWorkflowSettings
                )
                H3StudioContent(
                    workflowId = selected.id,
                    area = tab,
                    generationViewModel = generationViewModel,
                    onOpenWorkflowSettings = onOpenWorkflowSettings,
                    onPickWorkflow = { pickerFor = tab },
                    isVisible = isVisible
                )
            }
        }
    }

    val picking = pickerFor
    if (picking != null) {
        val list = inventory.filter { entry -> entry.modes.any { it.area == picking } }
        AlertDialog(
            onDismissRequest = { pickerFor = null },
            title = { Text(stringResource(R.string.h3_choose_workflow, stringResource(picking.labelRes))) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(list, key = { it.id }) { entry ->
                        TextButton(onClick = {
                            choices = choices + (picking.name to entry.id)
                            H3ChoicesStore.save(context, picking.name, entry.id)
                            pickerFor = null
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (entry.id == selected?.id) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                val subtitle = when {
                                    entry.builtIn -> if (entry.name.contains("Turbo", ignoreCase = true)) stringResource(R.string.h3_builtin_turbo) else stringResource(R.string.h3_builtin)
                                    else -> stringResource(R.string.h3_user_imported)
                                }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerFor = null }) { Text(stringResource(R.string.h3_close)) } }
        )
    }

    // The queue sheet is hosted once by MainNavHost, for every screen
}

@Composable
private fun BoundWorkflowRow(
    entry: H3Entry,
    candidates: List<H3Entry>,
    onSwitch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clickable(onClick = onSwitch),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(Color(0xFF2E7D32), CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(R.string.h3_active_workflow),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.h3_switch),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingArea(tab: H3Plan.Area, onOpenWorkflowSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.h3_no_workflow_for, stringResource(tab.labelRes)), style = MaterialTheme.typography.titleMedium)
        Text(
            when (tab) {
                H3Plan.Area.FRAMES -> stringResource(R.string.h3_hint_frames)
                H3Plan.Area.R2V -> stringResource(R.string.h3_hint_r2v)
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = onOpenWorkflowSettings) { Text(stringResource(R.string.h3_go_import)) }
    }
}

/** User-assigned modes must survive a restart, otherwise an ambiguous workflow falls back to
 *  "unrecognised" every launch and the assignment is lost. */
private object ModeOverrideStore {
    private const val PREFS = "h3_mode_overrides"

    fun load(context: android.content.Context): Map<String, H3Plan.Mode> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val out = mutableMapOf<String, H3Plan.Mode>()
        prefs.all.forEach { (key, value) ->
            val raw = value as? String ?: return@forEach
            runCatching { H3Plan.Mode.valueOf(raw) }.getOrNull()?.let { out[key] = it }
        }
        return out
    }

    fun save(context: android.content.Context, workflowId: String, mode: H3Plan.Mode) {
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(workflowId, mode.name).apply()
    }
}

/** Remembers the user's selected workflow per area so choices survive a restart or navigation away. */
private object H3ChoicesStore {
    private const val PREFS = "h3_choices"

    fun load(context: android.content.Context): Map<String, String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val out = mutableMapOf<String, String>()
        prefs.all.forEach { (key, value) ->
            (value as? String)?.let { out[key] = it }
        }
        return out
    }

    fun save(context: android.content.Context, areaName: String, workflowId: String) {
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(areaName, workflowId).apply()
    }
}
