package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.workflow.H3Plan
import sh.hnet.comfychair.workflow.H3WorkflowAdapter

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
    onOpenWorkflowSettings: () -> Unit
) {
    val version by WorkflowManager.workflowsVersion.collectAsState()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var choices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pickerFor by remember { mutableStateOf<H3Plan.Area?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingStore = remember { sh.hnet.comfychair.workflow.PendingJobStore(context) }

    val tabs = H3Plan.Area.entries

    // A card tapped in 工作流管理 hands over the workflow (and one mode it supports) once.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("h3_pending", android.content.Context.MODE_PRIVATE)
        val workflowId = prefs.getString("workflowId", null) ?: return@LaunchedEffect
        val modeName = prefs.getString("mode", "").orEmpty()
        prefs.edit().clear().apply()
        val mode = H3Plan.Mode.entries.firstOrNull { it.name == modeName } ?: H3Plan.Mode.T2V
        val area = mode.area
        tabIndex = tabs.indexOf(area).coerceAtLeast(0)
        choices = choices + (area.name to workflowId)
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
        WorkflowManager.getAllWorkflows(WorkflowManager.WorkflowSpace.H3).mapNotNull { workflow ->
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
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("H3 视频工作区", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenWorkflowSettings) { Text("工作流管理") }
        }

        if (undecided.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("以下工作流包含多个 H3 核心，无法自动判定，请指定一次：",
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
                Tab(selected = index == tabIndex, onClick = { tabIndex = index },
                    text = { Text(item.label, style = MaterialTheme.typography.labelLarge) })
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
                    onPickWorkflow = { pickerFor = tab }
                )
            }
        }
    }

    val picking = pickerFor
    if (picking != null) {
        val list = inventory.filter { entry -> entry.modes.any { it.area == picking } }
        AlertDialog(
            onDismissRequest = { pickerFor = null },
            title = { Text("选择${picking.label}工作流") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(list, key = { it.id }) { entry ->
                        TextButton(onClick = {
                            choices = choices + (picking.name to entry.id)
                            pickerFor = null
                        }) {
                            Column {
                                Text(entry.name)
                                Text(
                                    (if (entry.builtIn) "内置" else "用户导入") +
                                        " · " + if (entry.format == "litegraph") "LiteGraph" else "API",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerFor = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun BoundWorkflowRow(
    entry: H3Entry,
    candidates: List<H3Entry>,
    onSwitch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // The name moved to the fixed bottom bar, where it stays visible while scrolling;
            // repeating it here was the redundancy. Provenance is the only fact unique to this row.
            Text(
                (if (entry.builtIn) "内置工作流" else "用户导入") +
                    " · " + (if (entry.format == "litegraph") "LiteGraph" else "API JSON"),
                style = MaterialTheme.typography.labelSmall
            )
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
        Text("未找到${tab.label}的 H3 工作流", style = MaterialTheme.typography.titleMedium)
        Text(
            when (tab) {
                H3Plan.Area.FRAMES -> "同一个模板就能跑文生、图生和首尾帧，导入含 MiniMaxH3ImageToVideo 的工作流即可。"
                H3Plan.Area.R2V -> "参考生视频需要含 MiniMaxH3ReferenceToVideo 的模板。"
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = onOpenWorkflowSettings) { Text("去导入工作流") }
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
