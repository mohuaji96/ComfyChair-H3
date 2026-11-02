import io

P = r'G:\ComfyUI各类文件模型\app\ComfyChairH3\app\src\main\java\sh\hnet\comfychair\ui\screens\WorkflowsSettingsScreen.kt'
s = io.open(P, encoding='utf-8').read()


def sub(old, new):
    global s
    assert old in s, old[:70]
    s = s.replace(old, new, 1)


# 1) WorkflowSection / item gain an optional capability subtitle
sub(
    "    onExport: (WorkflowManager.Workflow, ExportFormat) -> Unit,\n"
    "    onDelete: (WorkflowManager.Workflow) -> Unit\n"
    ") {\n"
    "    Column {\n"
    "        // Section header",
    "    onExport: (WorkflowManager.Workflow, ExportFormat) -> Unit,\n"
    "    onDelete: (WorkflowManager.Workflow) -> Unit,\n"
    "    /** Extra line under the name, e.g. the modes an H3 template supports. */\n"
    "    subtitle: (WorkflowManager.Workflow) -> String? = { null }\n"
    ") {\n"
    "    Column {\n"
    "        // Section header"
)

sub(
    "                                onExport = { format -> onExport(workflow, format) },\n"
    "                                onDelete = { onDelete(workflow) }",
    "                                onExport = { format -> onExport(workflow, format) },\n"
    "                                onDelete = { onDelete(workflow) },\n"
    "                                subtitle = subtitle(workflow)"
)

sub(
    "    onExport: (ExportFormat) -> Unit,\n"
    "    onDelete: () -> Unit\n"
    ") {\n"
    "    var showContextMenu by remember { mutableStateOf(false) }",
    "    onExport: (ExportFormat) -> Unit,\n"
    "    onDelete: () -> Unit,\n"
    "    subtitle: String? = null\n"
    ") {\n"
    "    var showContextMenu by remember { mutableStateOf(false) }"
)

sub(
    "            if (workflow.isBuiltIn) {\n"
    "                Text(\n"
    "                    text = stringResource(R.string.label_workflow_built_in),",
    "            if (subtitle != null) {\n"
    "                Text(\n"
    "                    text = subtitle,\n"
    "                    style = MaterialTheme.typography.labelMedium,\n"
    "                    color = MaterialTheme.colorScheme.primary\n"
    "                )\n"
    "            }\n"
    "            if (workflow.isBuiltIn) {\n"
    "                Text(\n"
    "                    text = stringResource(R.string.label_workflow_built_in),"
)

# 2) the H3 section, after Image-to-Video
sub(
    "                // Bottom padding\n"
    "                item {\n"
    "                    Spacer(modifier = Modifier.height(16.dp))\n"
    "                }",
    "                // H3 video workspace: its own library, opened in the H3 screen rather than the\n"
    "                // node editor; the editor stays reachable from the card menu.\n"
    "                item {\n"
    "                    WorkflowSection(\n"
    "                        title = \"H3 视频工作区\",\n"
    "                        workflows = uiState.h3Workflows,\n"
    "                        onWorkflowClick = { openH3Workspace(context, it, onNavigateToGeneration) },\n"
    "                        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },\n"
    "                        onRename = { viewModel.onEditWorkflow(it) },\n"
    "                        onDuplicate = { viewModel.onDuplicateWorkflow(it) },\n"
    "                        onExport = { workflow, format -> viewModel.onExportWorkflow(workflow, format) },\n"
    "                        onDelete = { viewModel.onDeleteWorkflow(it) },\n"
    "                        subtitle = { h3CapabilityLabel(it) }\n"
    "                    )\n"
    "                }\n"
    "\n"
    "                // Bottom padding\n"
    "                item {\n"
    "                    Spacer(modifier = Modifier.height(16.dp))\n"
    "                }"
)

# 3) helpers at the end of the file
s += '''
/** Modes an H3 workflow really supports, derived from the file itself. */
private fun h3CapabilityLabel(workflow: WorkflowManager.Workflow): String? {
    val spec = runCatching {
        sh.hnet.comfychair.workflow.H3Plan.resolve(workflow.jsonContent)
    }.getOrNull() ?: return null
    return spec.modes.joinToString(" · ") { it.label }
}

/**
 * Remembers which workflow the H3 workspace should open, then switches tabs to the H3 screen.
 * The workspace consumes the value once, so returning there later keeps its own choice.
 */
private fun openH3Workspace(
    context: android.content.Context,
    workflow: WorkflowManager.Workflow,
    onNavigateToGeneration: () -> Unit
) {
    val mode = runCatching {
        sh.hnet.comfychair.workflow.H3Plan.resolve(workflow.jsonContent)
    }.getOrNull()?.modes?.firstOrNull()
    context.getSharedPreferences("h3_pending", android.content.Context.MODE_PRIVATE)
        .edit()
        .putString("workflowId", workflow.id)
        .putString("mode", mode?.name ?: "")
        .apply()
    onNavigateToGeneration()
}
'''

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('patched screen')
