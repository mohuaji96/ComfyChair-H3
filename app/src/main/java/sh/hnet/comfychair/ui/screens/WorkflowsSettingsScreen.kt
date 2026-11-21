package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.SettingsFootnote
import sh.hnet.comfychair.ui.components.SettingsGroupCard
import sh.hnet.comfychair.ui.components.SettingsMenuDropdown
import sh.hnet.comfychair.ui.components.SettingsRowDivider
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.WorkflowTypeDisplay
import sh.hnet.comfychair.viewmodel.ExportFormat
import sh.hnet.comfychair.viewmodel.WorkflowFilter
import sh.hnet.comfychair.viewmodel.WorkflowManagementEvent
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsSettingsScreen(
    viewModel: WorkflowManagementViewModel,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // State and effects
    // JSON file picker
    val jsonPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.onFileSelected(context, it)
        }
    }

    // Editor launcher for mapping mode
    val editorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val mappingsJson = result.data?.getStringExtra(WorkflowEditorActivity.EXTRA_RESULT_MAPPINGS)
            if (mappingsJson != null) {
                // Parse mappings and complete import
                val mappings = parseMappingsFromJson(mappingsJson)
                viewModel.completeImport(mappings)
            }
        } else {
            viewModel.cancelMapping()
        }
    }

    var showServerImportSheet by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    // Editor launcher for create new workflow mode
    val createEditorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Reload from disk and refresh UI - workflow was saved by WorkflowEditorViewModel
            viewModel.reloadAndRefreshWorkflows()
        }
    }

    // Editor launcher for editing existing workflow structure
    val editExistingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Reload from disk and refresh UI - workflow was updated by WorkflowEditorViewModel
            viewModel.reloadAndRefreshWorkflows()
        }
    }

    // Export file picker launcher
    var exportFilename by remember { mutableStateOf("workflow.json") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.performExport(context, uri)
        } else {
            viewModel.cancelExport()
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WorkflowManagementEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is WorkflowManagementEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
                is WorkflowManagementEvent.LaunchEditor -> {
                    val pending = event.pendingUpload
                    val mappingState = pending.mappingState
                    if (mappingState != null) {
                        val intent = WorkflowEditorActivity.createIntentForMapping(
                            context = context,
                            jsonContent = pending.jsonContent,
                            name = pending.name,
                            description = pending.description,
                            mappingState = mappingState
                        )
                        editorLauncher.launch(intent)
                    }
                }
                is WorkflowManagementEvent.LaunchExportFilePicker -> {
                    exportFilename = event.suggestedFilename
                    exportLauncher.launch(event.suggestedFilename)
                }
                is WorkflowManagementEvent.WorkflowsChanged -> {
                    // Handled by SettingsContainerActivity to set result
                }
            }
        }
    }

    val allWorkflows = uiState.ttiWorkflows + uiState.itiInpaintingWorkflows + uiState.itiEditingWorkflows +
        uiState.ttvWorkflows + uiState.itvWorkflows + uiState.h3Workflows
    val enabledTotal = allWorkflows.count { it.id !in uiState.disabledWorkflowIds }
    val filter = uiState.filter

    val openWorkflow: (WorkflowManager.Workflow) -> Unit = { workflow ->
        if (workflow.space == WorkflowManager.WorkflowSpace.H3) {
            openH3Workspace(context, workflow, onNavigateToGeneration)
        } else {
            context.startActivity(WorkflowEditorActivity.createIntent(context, workflow.id))
        }
    }
    val onEditStructure: (WorkflowManager.Workflow) -> Unit = {
        editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id))
    }
    val onRename: (WorkflowManager.Workflow) -> Unit = { viewModel.onEditWorkflow(it) }
    val onDuplicate: (WorkflowManager.Workflow) -> Unit = { viewModel.onDuplicateWorkflow(it) }
    val onExport: (WorkflowManager.Workflow, ExportFormat) -> Unit = { workflow, format ->
        viewModel.onExportWorkflow(workflow, format)
    }
    val onDelete: (WorkflowManager.Workflow) -> Unit = { viewModel.onDeleteWorkflow(it) }
    val onEnabledChange: (WorkflowManager.Workflow, Boolean) -> Unit = { workflow, enabled ->
        viewModel.onWorkflowEnabledChange(workflow, enabled)
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title_workflows_settings))
                        Text(
                            text = "已启用 $enabledTotal/${allWorkflows.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    SettingsMenuDropdown(
                        onGeneration = onNavigateToGeneration,
                        onLogout = onLogout
                    )
                }
            )
        },
        floatingActionButton = {
            AddWorkflowFabMenu(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                onCreate = {
                    createEditorLauncher.launch(
                        WorkflowEditorActivity.createIntentForNewWorkflow(context)
                    )
                },
                onImportFromFile = { jsonPickerLauncher.launch("application/json") },
                onImportFromServer = { showServerImportSheet = true }
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            NoOverscrollContainer(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    // Filter chips row
                    item(key = "type_filter_chips") {
                        WorkflowFilterChips(filter, viewModel::onFilterChange)
                    }

                    // Text-to-Image
                    if (WorkflowType.TTI in filter.types) {
                        item(key = WorkflowType.TTI.name) {
                            WorkflowSection(
                                title = stringResource(R.string.workflow_section_tti),
                                workflows = uiState.ttiWorkflows,
                                disabledWorkflowIds = uiState.disabledWorkflowIds,
                                onOpen = openWorkflow,
                                onEnabledChange = onEnabledChange,
                                onSectionEnabledChange = { viewModel.onSectionEnabledChange(WorkflowType.TTI, it) },
                                onEditStructure = onEditStructure,
                                onRename = onRename,
                                onDuplicate = onDuplicate,
                                onExport = onExport,
                                onDelete = onDelete
                            )
                        }
                    }

                    // Image to Image: Editing
                    if (WorkflowType.ITI_EDITING in filter.types) {
                        item(key = WorkflowType.ITI_EDITING.name) {
                            WorkflowSection(
                                title = stringResource(R.string.workflow_section_iti_editing),
                                workflows = uiState.itiEditingWorkflows,
                                disabledWorkflowIds = uiState.disabledWorkflowIds,
                                onOpen = openWorkflow,
                                onEnabledChange = onEnabledChange,
                                onSectionEnabledChange = { viewModel.onSectionEnabledChange(WorkflowType.ITI_EDITING, it) },
                                onEditStructure = onEditStructure,
                                onRename = onRename,
                                onDuplicate = onDuplicate,
                                onExport = onExport,
                                onDelete = onDelete
                            )
                        }
                    }

                    // Image to Image: Inpainting
                    if (WorkflowType.ITI_INPAINTING in filter.types) {
                        item(key = WorkflowType.ITI_INPAINTING.name) {
                            WorkflowSection(
                                title = stringResource(R.string.workflow_section_iti_inpainting),
                                workflows = uiState.itiInpaintingWorkflows,
                                disabledWorkflowIds = uiState.disabledWorkflowIds,
                                onOpen = openWorkflow,
                                onEnabledChange = onEnabledChange,
                                onSectionEnabledChange = { viewModel.onSectionEnabledChange(WorkflowType.ITI_INPAINTING, it) },
                                onEditStructure = onEditStructure,
                                onRename = onRename,
                                onDuplicate = onDuplicate,
                                onExport = onExport,
                                onDelete = onDelete
                            )
                        }
                    }

                    // Text-to-Video
                    if (WorkflowType.TTV in filter.types) {
                        item(key = WorkflowType.TTV.name) {
                            WorkflowSection(
                                title = stringResource(R.string.workflow_section_ttv),
                                workflows = uiState.ttvWorkflows,
                                disabledWorkflowIds = uiState.disabledWorkflowIds,
                                onOpen = openWorkflow,
                                onEnabledChange = onEnabledChange,
                                onSectionEnabledChange = { viewModel.onSectionEnabledChange(WorkflowType.TTV, it) },
                                onEditStructure = onEditStructure,
                                onRename = onRename,
                                onDuplicate = onDuplicate,
                                onExport = onExport,
                                onDelete = onDelete
                            )
                        }
                    }

                    // Image-to-Video
                    if (WorkflowType.ITV in filter.types) {
                        item(key = WorkflowType.ITV.name) {
                            WorkflowSection(
                                title = stringResource(R.string.workflow_section_itv),
                                workflows = uiState.itvWorkflows,
                                disabledWorkflowIds = uiState.disabledWorkflowIds,
                                onOpen = openWorkflow,
                                onEnabledChange = onEnabledChange,
                                onSectionEnabledChange = { viewModel.onSectionEnabledChange(WorkflowType.ITV, it) },
                                onEditStructure = onEditStructure,
                                onRename = onRename,
                                onDuplicate = onDuplicate,
                                onExport = onExport,
                                onDelete = onDelete
                            )
                        }
                    }

                    // H3 video workspace
                    if (filter.includeH3) {
                        item(key = "h3") {
                            WorkflowSection(
                                title = "H3 视频工作区",
                                workflows = uiState.h3Workflows,
                                disabledWorkflowIds = uiState.disabledWorkflowIds,
                                onOpen = openWorkflow,
                                onEnabledChange = onEnabledChange,
                                onSectionEnabledChange = { viewModel.onH3SectionEnabledChange(it) },
                                onEditStructure = onEditStructure,
                                onRename = onRename,
                                onDuplicate = onDuplicate,
                                onExport = onExport,
                                onDelete = onDelete,
                                subtitle = { h3CapabilityLabel(it) }
                            )
                        }
                    }

            // Bottom padding
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
            }
        }
    }
}

    // Import dialog
    if (uiState.showImportDialog) {
        ImportWorkflowDialog(
            selectedType = uiState.importSelectedType,
            onTypeSelected = viewModel::onImportTypeSelected,
            autoH3 = uiState.importAutoH3,
            onH3Selected = viewModel::onImportH3Selected,
            isTypeDropdownExpanded = uiState.importTypeDropdownExpanded,
            onToggleTypeDropdown = viewModel::onToggleTypeDropdown,
            name = uiState.importName,
            onNameChange = viewModel::onImportNameChange,
            nameError = uiState.importNameError,
            description = uiState.importDescription,
            onDescriptionChange = viewModel::onImportDescriptionChange,
            descriptionError = uiState.importDescriptionError,
            isValidating = uiState.isValidatingNodes,
            onConfirm = { viewModel.proceedWithImport(context, ConnectionManager.clientOrNull) },
            onDismiss = viewModel::cancelImport
        )
    }

    // Missing nodes dialog
    if (uiState.showMissingNodesDialog) {
        MissingNodesDialog(
            missingNodes = uiState.missingNodes,
            onDismiss = viewModel::dismissMissingNodesDialog
        )
    }

    // Missing fields dialog
    if (uiState.showMissingFieldsDialog) {
        MissingFieldsDialog(
            missingFields = uiState.missingFields,
            onDismiss = viewModel::dismissMissingFieldsDialog
        )
    }

    // Duplicate name dialog
    if (uiState.showDuplicateNameDialog) {
        DuplicateNameDialog(
            onDismiss = viewModel::dismissDuplicateNameDialog
        )
    }

    if (showServerImportSheet) {
        sh.hnet.comfychair.ui.components.ServerImportSheet(
            onDismiss = { showServerImportSheet = false },
            onImportSuccess = {
                viewModel.reloadAndRefreshWorkflows()
            }
        )
    }

    // Edit dialog
    if (uiState.showEditDialog) {
        EditWorkflowDialog(
            name = uiState.editName,
            onNameChange = viewModel::onEditNameChange,
            nameError = uiState.editNameError,
            description = uiState.editDescription,
            onDescriptionChange = viewModel::onEditDescriptionChange,
            descriptionError = uiState.editDescriptionError,
            onConfirm = viewModel::confirmEdit,
            onDismiss = viewModel::cancelEdit
        )
    }

    // Delete confirmation dialog
    if (uiState.showDeleteDialog) {
        DeleteWorkflowDialog(
            workflowName = uiState.workflowToDelete?.name ?: "",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete
        )
    }

    // Duplicate dialog
    if (uiState.showDuplicateDialog) {
        DuplicateWorkflowDialog(
            name = uiState.duplicateName,
            onNameChange = viewModel::onDuplicateNameChange,
            nameError = uiState.duplicateNameError,
            description = uiState.duplicateDescription,
            onDescriptionChange = viewModel::onDuplicateDescriptionChange,
            descriptionError = uiState.duplicateDescriptionError,
            onConfirm = viewModel::confirmDuplicate,
            onDismiss = viewModel::cancelDuplicate
        )
    }
}

/**
 * Parse field mappings from JSON string
 */
private fun parseMappingsFromJson(jsonString: String): Map<String, Pair<String, String>> {
    val mappings = mutableMapOf<String, Pair<String, String>>()
    try {
        val json = org.json.JSONObject(jsonString)
        for (key in json.keys()) {
            val mapping = json.getJSONObject(key)
            mappings[key] = Pair(
                mapping.getString("nodeId"),
                mapping.getString("inputKey")
            )
        }
    } catch (e: Exception) {
        // Return empty mappings on error
    }
    return mappings
}

private val filterLabels = listOf(
    WorkflowFilter.ALL to "全部",
    WorkflowFilter.TTI to "文生图",
    WorkflowFilter.ITI to "图生图",
    WorkflowFilter.TTV to "文生视频",
    WorkflowFilter.ITV to "图生视频",
    WorkflowFilter.H3 to "H3 视频"
)

/** Which types the page shows: one choice, scrolled into view when picked. */
@Composable
private fun WorkflowFilterChips(selected: WorkflowFilter, onSelect: (WorkflowFilter) -> Unit) {
    val rowState = rememberLazyListState()
    LaunchedEffect(selected) {
        rowState.animateScrollToItem(filterLabels.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    }
    androidx.compose.foundation.lazy.LazyRow(
        state = rowState,
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(filterLabels.size) { index ->
            val (option, label) = filterLabels[index]
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = { Text(label) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null
            )
        }
    }
}

/**
 * A type's workflows: its heading with the count of those switched on (a menu switches all on or
 * off), a warning when none is on, then your workflows (by name) and the built-in ones, each a
 * group of name-and-switch rows.
 */
@Composable
private fun WorkflowSection(
    title: String,
    workflows: List<WorkflowManager.Workflow>,
    disabledWorkflowIds: Set<String>,
    onOpen: (WorkflowManager.Workflow) -> Unit,
    onEnabledChange: (WorkflowManager.Workflow, Boolean) -> Unit,
    onSectionEnabledChange: (Boolean) -> Unit,
    onEditStructure: (WorkflowManager.Workflow) -> Unit,
    onRename: (WorkflowManager.Workflow) -> Unit,
    onDuplicate: (WorkflowManager.Workflow) -> Unit,
    onExport: (WorkflowManager.Workflow, ExportFormat) -> Unit,
    onDelete: (WorkflowManager.Workflow) -> Unit,
    /** Extra line under the name, e.g. the modes an H3 template supports. */
    subtitle: (WorkflowManager.Workflow) -> String? = { null }
) {
    val enabledCount = workflows.count { it.id !in disabledWorkflowIds }
    val own = workflows.filter { !it.isBuiltIn }.sortedBy { it.name.lowercase() }
    val builtIn = workflows.filter { it.isBuiltIn }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, top = 20.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (workflows.isNotEmpty()) {
                SectionCountButton(enabledCount, workflows.size, onSectionEnabledChange)
            }
        }

        if (workflows.isEmpty()) {
            SettingsFootnote(stringResource(R.string.workflow_section_empty))
        } else if (enabledCount == 0) {
            NoneOnCard(title)
            Spacer(Modifier.height(6.dp))
        }

        listOf("自有" to own, "内置" to builtIn).forEach { (label, group) ->
            if (group.isNotEmpty()) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 5.dp)
                )
                SettingsGroupCard {
                    Column {
                        group.forEachIndexed { index, workflow ->
                            key(workflow.id) {
                                WorkflowToggleRow(
                                    workflow = workflow,
                                    enabled = workflow.id !in disabledWorkflowIds,
                                    onOpen = { onOpen(workflow) },
                                    onEnabledChange = { onEnabledChange(workflow, it) },
                                    onEditStructure = { onEditStructure(workflow) },
                                    onRename = { onRename(workflow) },
                                    onDuplicate = { onDuplicate(workflow) },
                                    onExport = { format -> onExport(workflow, format) },
                                    onDelete = { onDelete(workflow) },
                                    subtitle = subtitle(workflow)
                                )
                                if (index < group.size - 1) SettingsRowDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "n of m on" as a button opening Switch all on / Switch all off; in the error colour when none is on. */
@Composable
private fun SectionCountButton(enabledCount: Int, total: Int, onSectionEnabledChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (enabledCount == 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("已启用 $enabledCount/$total", fontSize = 11.sp)
            Icon(
                if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // The one that would change nothing is disabled
            listOf(
                "全部开启" to true,
                "全部关闭" to false
            ).forEach { (label, on) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    enabled = if (on) enabledCount < total else enabledCount > 0,
                    onClick = {
                        expanded = false
                        onSectionEnabledChange(on)
                    }
                )
            }
        }
    }
}

/** No workflow of this type is on: the screen can't generate. Switching all off is allowed; this is the warning. */
@Composable
private fun NoneOnCard(sectionTitle: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                "“$sectionTitle”屏幕没有开启的工作流，无法生成。",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * A workflow: its name (opens it) and its actions menu, then past a divider its switch — two
 * targets, so the switch is never hit by accident while opening the workflow.
 */
@Composable
private fun WorkflowToggleRow(
    workflow: WorkflowManager.Workflow,
    enabled: Boolean,
    onOpen: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onEditStructure: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onDelete: () -> Unit,
    subtitle: String? = null
) {
    var showContextMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 7.dp)
        ) {
            Text(
                text = workflow.name,
                style = MaterialTheme.typography.titleSmall
            )
            if (workflow.description.isNotEmpty()) {
                Text(
                    text = workflow.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (workflow.isBuiltIn) {
                Text(
                    text = stringResource(R.string.label_workflow_built_in),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Context menu
        Box {
            IconButton(onClick = { showContextMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.content_description_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                // Edit workflow (structure) - only for custom workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_edit_structure)) },
                    onClick = {
                        showContextMenu = false
                        onEditStructure()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Tune, contentDescription = null)
                    },
                    enabled = !workflow.isBuiltIn
                )

                // Rename - only for custom workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_rename)) },
                    onClick = {
                        showContextMenu = false
                        onRename()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    },
                    enabled = !workflow.isBuiltIn
                )

                // Duplicate - available for all workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_duplicate)) },
                    onClick = {
                        showContextMenu = false
                        onDuplicate()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                )

                // Export (Internal format) - available for all workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_export)) },
                    onClick = {
                        showContextMenu = false
                        onExport(ExportFormat.INTERNAL)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                    }
                )

                // Export (API Format) - available for all workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_export_api)) },
                    onClick = {
                        showContextMenu = false
                        onExport(ExportFormat.API)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                    }
                )

                // Divider above Delete
                HorizontalDivider()

                // Delete - only for custom workflows
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.workflow_menu_delete),
                            color = if (!workflow.isBuiltIn) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = if (!workflow.isBuiltIn) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    enabled = !workflow.isBuiltIn
                )
            }
        }

        VerticalDivider(
            modifier = Modifier
                .height(24.dp)
                .padding(end = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportWorkflowDialog(
    selectedType: WorkflowType?,
    onTypeSelected: (WorkflowType) -> Unit,
    autoH3: Boolean,
    onH3Selected: () -> Unit,
    isTypeDropdownExpanded: Boolean,
    onToggleTypeDropdown: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionError: String?,
    isValidating: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedTypeName =
        if (autoH3) H3_IMPORT_LABEL
        else selectedType?.let { stringResource(WorkflowTypeDisplay.getDisplayNameResId(it)) } ?: ""

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = { Text(stringResource(R.string.title_import_workflow)) },
        text = {
            Column {
                // Type dropdown
                Text(
                    text = stringResource(R.string.label_workflow_type),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = isTypeDropdownExpanded,
                    onExpandedChange = { if (!isValidating) onToggleTypeDropdown() }
                ) {
                    OutlinedTextField(
                        value = selectedTypeName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isTypeDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        enabled = !isValidating
                    )
                    ExposedDropdownMenu(
                        expanded = isTypeDropdownExpanded,
                        onDismissRequest = onToggleTypeDropdown
                    ) {
                        // H3 first: it is where an H3 file lands regardless of the type below,
                        // so the entry has to exist or the dialog looks like it has no H3 home.
                        DropdownMenuItem(
                            text = { Text(H3_IMPORT_LABEL) },
                            onClick = onH3Selected,
                            leadingIcon = if (autoH3) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                        HorizontalDivider()
                        WorkflowTypeDisplay.allTypes.forEach { (type, displayNameResId) ->
                            key(type) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(displayNameResId)) },
                                    onClick = { onTypeSelected(type) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.label_workflow_name)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description field
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.label_workflow_description)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )

                if (isValidating) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.msg_validating_workflow))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = selectedType != null && name.isNotBlank() && !isValidating
            ) {
                Text(stringResource(R.string.button_continue))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !isValidating) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun MissingNodesDialog(
    missingNodes: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_missing_nodes)) },
        text = {
            Column {
                Text(stringResource(R.string.msg_missing_nodes))
                Spacer(modifier = Modifier.height(8.dp))
                NoOverscrollContainer(modifier = Modifier.heightIn(max = 200.dp)) {
                    LazyColumn {
                        items(missingNodes, key = { it }) { node ->
                            Text(
                                text = "- $node",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.button_dismiss))
            }
        }
    )
}

@Composable
private fun MissingFieldsDialog(
    missingFields: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_missing_fields)) },
        text = {
            Column {
                Text(stringResource(R.string.msg_missing_fields))
                Spacer(modifier = Modifier.height(8.dp))
                missingFields.forEach { field ->
                    key(field) {
                        Text(
                            text = "- $field",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.button_dismiss))
            }
        }
    )
}

@Composable
private fun DuplicateNameDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_duplicate_name)) },
        text = { Text(stringResource(R.string.msg_duplicate_name)) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.button_ok))
            }
        }
    )
}

@Composable
private fun EditWorkflowDialog(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionError: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_edit_workflow)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.label_workflow_name)) },
                    singleLine = true,
                    isError = nameError != null || name.isBlank(),
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.label_workflow_description)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank() && nameError == null && descriptionError == null
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun DeleteWorkflowDialog(
    workflowName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_delete_workflow)) },
        text = {
            Text(stringResource(R.string.msg_delete_workflow, workflowName))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.button_delete))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun DuplicateWorkflowDialog(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionError: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_duplicate_workflow)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.label_workflow_name)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.label_workflow_description)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank() && nameError == null && descriptionError == null
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

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

/** Import destinations are ordinary types plus this one; H3 is decided by the file. */
private const val H3_IMPORT_LABEL = "H3 视频工作区（按文件自动识别）"

@Composable
private fun AddWorkflowFabMenu(
    expanded: Boolean,
    onToggle: () -> Unit,
    onCreate: () -> Unit,
    onImportFromFile: () -> Unit,
    onImportFromServer: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. 新建
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = { onToggle(); onCreate() },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("新建", fontWeight = FontWeight.Bold) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                // 2. 从文件
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = { onToggle(); onImportFromFile() },
                    icon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                    text = { Text("从文件", fontWeight = FontWeight.Bold) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                // 3. 从服务器
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = { onToggle(); onImportFromServer() },
                    icon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                    text = { Text("从服务器", fontWeight = FontWeight.Bold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        androidx.compose.material3.FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = if (expanded) androidx.compose.material.icons.Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (expanded) "关闭" else "添加工作流"
            )
        }
    }
}
