package sh.hnet.comfychair.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ContentProfile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.model.StyleGroup
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.storage.TagComposerState
import sh.hnet.comfychair.ui.components.ServerStatusLamp
import sh.hnet.comfychair.ui.components.PromptOptimizeSheet
import sh.hnet.comfychair.viewmodel.PromptOptimizerViewModel
import sh.hnet.comfychair.viewmodel.TagComposerViewModel
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick

private data class SlotRef(val categoryId: String, val slotId: String)

/**
 * Standalone random tag combiner.
 *
 * Nothing here touches a workflow or another screen: it builds one prompt line and copies
 * it out. Wiring it into a generation screen would need that screen's view model, which is
 * exactly the coupling this page is meant to avoid.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagComposerScreen(onOpenStory: () -> Unit = {}, viewModel: TagComposerViewModel = viewModel()) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val picks = viewModel.picks
    // The preview engine has to know the mode: it was built once with the defaults, so 普通模式
    // still printed the 增强 sentence while the draw itself honoured the switch. Keying the cache
    // on strict is what makes the text follow the toggle.
    val engine = remember(library, viewModel.strict) {
        TagComposerEngine(library, emptySet(), viewModel.strict)
    }
    val composed = remember(picks, library, viewModel.strict) { engine.compose(picks) }
    val skipped = remember(picks, library, viewModel.strict) { engine.skipped(picks) }
    val promptViewModel: PromptOptimizerViewModel = viewModel()

    val context = LocalContext.current
    LaunchedEffect(Unit) { promptViewModel.initialize(context) }
    val optimizeState by promptViewModel.optimize.collectAsStateWithLifecycle()
    // The tag library is Chinese; only follow the optimiser once it has actually produced
    // a result, otherwise the negatives would flip language before the prompt did.
    val negativesEnglish = optimizeState.hasResult && optimizeState.english
    val negatives = remember(picks, library, negativesEnglish) {
        engine.negatives(picks, english = negativesEnglish)
    }
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SlotRef?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }
    var showOptimize by remember { mutableStateOf(false) }

    val importMergeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLibrary(it, merge = true) }
    }
    val importReplaceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLibrary(it, merge = false) }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportLibrary) }

    var sheetNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.message, editing) {
        val text = viewModel.message ?: return@LaunchedEffect
        // With the sheet open the snackbar is out of sight underneath it, so hand the text to
        // the sheet and let it stay until the next tap.
        if (editing != null) sheetNote = text else snackbar.showSnackbar(text)
        viewModel.clearMessage()
    }
    LaunchedEffect(editing) { if (editing == null) sheetNote = null }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // The host NavHost already reserved the system bars; letting this Scaffold add them
        // again is what produced the dead band above the header.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            ComposerBottomBar(
                composed = composed,
                onExpand = { if (composed.isNotBlank()) showFull = true },
                onRandomize = { viewModel.randomizeAll() },
                onCopy = {
                    copyToClipboard(context, composed)
                    viewModel.hint(context.getString(R.string.tc_copied))
                },
                onOptimize = { showOptimize = true },
                optimizeHint = if (optimizeState.hasResult) {
                    stringResource(R.string.tc_optimized_ms, optimizeState.elapsedMs)
                } else null
            )
        }
    ) { padding ->
        val visible = viewModel.visibleCategories()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = 16.dp)
        ) {
            ComposerHeader(
                groupCount = visible.size,
                tagCount = library.tagCount,
                version = library.version,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onImportMerge = { importMergeLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onImportReplace = { importReplaceLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onExport = {
                    exportLauncher.launch(
                        viewModel.profile.exportFileName(
                            context.getString(R.string.tc_export_file_name),
                            BuildConfig.VERSION_NAME
                        )
                    )
                },
                onReset = { confirmReset = true },
                onStory = onOpenStory,
                onStatusLamp = { ServerStatusLamp() }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    ModeSwitch(
                        mode = viewModel.mode,
                        sfw = viewModel.profile.isSfw,
                        onSelect = viewModel::switchMode
                    )
                }
                GuardPill(
                    strict = viewModel.strict,
                    avoidRepeat = viewModel.avoidRepeat,
                    sfw = viewModel.profile.isSfw,
                    onStrict = viewModel::setStrictMode,
                    onAvoidRepeat = viewModel::setAvoidRepeatMode,
                    onSfw = { wantSfw ->
                        viewModel.switchProfile(
                            if (wantSfw) ContentProfile.SFW else ContentProfile.NSFW
                        )
                    }
                )
            }
            Spacer(Modifier.height(2.dp))

            if (viewModel.showSfwIntro) {
                SfwIntroDialog(onDismiss = viewModel::dismissSfwIntro)
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (library.categories.isEmpty()) {
                    item {
                        Text(
                             stringResource(R.string.tc_library_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }

                // The place rules read the chosen scene. With the scene switched off there is
                // nothing to compare against, so every "needs a wall / needs a bed" check passes
                // silently -- the output gets stranger and nothing on screen explains why.
                val scenePick = picks["scene.place"]
                if (scenePick != null && scenePick.ignored) {
                    item(key = "scene-ignored-note") {
                        Text(
                             stringResource(R.string.tc_scene_ignored),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                val sections = library.groups.mapNotNull { group ->
                    val cats = visible.filter { it.group == group.id }
                    if (cats.isEmpty()) null else group to cats
                }
                val ungrouped = visible.filter { category ->
                    library.groups.none { it.id == category.group }
                }

                sections.forEach { (group, cats) ->
                    item(key = "section-" + group.id) {
                        SectionHeader(
                            label = group.label,
                            locked = group.id in viewModel.lockedGroups,
                            onToggleLock = { viewModel.toggleSectionLock(group.id) },
                            onReroll = { viewModel.randomizeSection(group.id) }
                        )
                    }
                    items(cats, key = { it.id }) { category ->
                        CategoryRow(
                            category = category,
                            picks = picks,
                            skipped = category.id in skipped,
                            held = group.id in viewModel.lockedGroups,
                            onLabelClick = { viewModel.randomizeCategory(category.id) },
                            onSlotClick = { slot -> editing = SlotRef(category.id, slot.id) }
                        )
                    }
                }
                if (ungrouped.isNotEmpty()) {
                    item(key = "section-ungrouped") {
                        SectionHeader(stringResource(R.string.tc_other)) { }
                    }
                    items(ungrouped, key = { it.id }) { category ->
                        CategoryRow(
                            category = category,
                            picks = picks,
                            skipped = category.id in skipped,
                            held = false,
                            onLabelClick = { viewModel.randomizeCategory(category.id) },
                            onSlotClick = { slot -> editing = SlotRef(category.id, slot.id) }
                        )
                    }
                }
            }
        }
    }

    if (showFull) {
        FullPromptDialog(
            text = composed,
            negative = negatives,
            onDismiss = { showFull = false },
            onCopy = {
                copyToClipboard(context, composed)
                viewModel.hint(context.getString(R.string.tc_copied))
                showFull = false
            },
            onCopyNegative = {
                copyToClipboard(context, negatives)
                viewModel.hint(context.getString(R.string.tc_copied_negative))
            }
        )
    }

    if (showOptimize) {
        PromptOptimizeSheet(
            libraryPrompt = composed,
            onDismiss = { showOptimize = false },
            onCopy = {
                copyToClipboard(context, it)
                viewModel.hint(context.getString(R.string.tc_copied_optimized))
            }
        )
    }

    val ref = editing
    if (ref != null) {
        val category = library.category(ref.categoryId)
        val slot = category?.slots?.firstOrNull { it.id == ref.slotId }
        if (category != null && slot != null) {
            SlotSheet(
                category = category,
                slot = slot,
                groups = if (ref.categoryId == "mood") library.styleGroups else emptyList(),
                reason = viewModel.blockReason(ref.categoryId, slot.id),
                blocked = viewModel.blockedWords(ref.categoryId, slot.id),
                note = sheetNote,
                pool = viewModel.slotPool(ref.categoryId, slot, includeExcluded = true),
                pick = picks["${ref.categoryId}.${ref.slotId}"] ?: TagPick(),
                onDismiss = { editing = null },
                // Any tap starts a new turn: the previous note would otherwise describe a change
                // the user has already moved past.
                onPick = { sheetNote = null; viewModel.setValue(ref.categoryId, ref.slotId, it) },
                onLock = { sheetNote = null; viewModel.toggleLock(ref.categoryId, ref.slotId) },
                onIgnore = { sheetNote = null; viewModel.toggleIgnore(ref.categoryId, ref.slotId) },
                onClear = { sheetNote = null; viewModel.clearSlot(ref.categoryId, ref.slotId) },
                onAdd = { viewModel.addTag(ref.categoryId, slot, it) },
                onRemove = { viewModel.removeTag(ref.categoryId, slot, it) },
                onToggleExcluded = { viewModel.toggleExcluded(ref.categoryId, slot, it) }
            )
        }
    }

    viewModel.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingImport() },
            title = { Text(stringResource(R.string.tc_import_confirm_title, pending.found.label)) },
            text = {
                Text(
                    stringResource(
                        if (pending.merge) R.string.tc_import_confirm_merge
                        else R.string.tc_import_confirm_replace,
                        viewModel.profile.label,
                        pending.found.label
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingImport() }) {
                    Text(stringResource(R.string.tc_import_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingImport() }) {
                    Text(stringResource(R.string.tc_cancel))
                }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.tc_reset_confirm_title)) },
            text = {
                Text(stringResource(R.string.tc_reset_confirm_body, library.tagCount))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetLibrary()
                }) { Text(stringResource(R.string.tc_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.tc_cancel))
                }
            }
        )
    }
}

@Composable
private fun ComposerHeader(
    groupCount: Int,
    tagCount: Int,
    version: String,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onImportMerge: () -> Unit,
    onImportReplace: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
    onStory: () -> Unit,
    onStatusLamp: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tc_title), style = MaterialTheme.typography.titleMedium)
            // 两个组合页的顶端写成同一副样子：数量、版本一行，操作提示一行。
            Text(
                stringResource(R.string.tc_header_counts, groupCount, tagCount) +
                    if (version.isBlank()) "" else " · $version",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.tc_header_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onStory) { Text(stringResource(R.string.tc_story)) }
        // Queue pill, same as the generation tabs: a run started elsewhere stays watchable here.
        sh.hnet.comfychair.ui.components.QueueIndicatorPill(modifier = Modifier.padding(horizontal = 4.dp))
        onStatusLamp()
        val navigate = sh.hnet.comfychair.ui.navigation.LocalScreenNavigator.current
        IconButton(onClick = { navigate(sh.hnet.comfychair.navigation.MainRoute.Settings) }) {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.tc_settings)
            )
        }
        Box {
            IconButton(onClick = { onMenuOpenChange(true) }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.tc_library_menu))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tc_import_merge)) },
                    onClick = { onMenuOpenChange(false); onImportMerge() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tc_import_replace)) },
                    onClick = { onMenuOpenChange(false); onImportReplace() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tc_export_library)) },
                    onClick = { onMenuOpenChange(false); onExport() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tc_reset_library)) },
                    onClick = { onMenuOpenChange(false); onReset() }
                )
            }
        }
    }
}

/**
 * Solo and group are two independent sheets on one library. Switching keeps both selections,
 * so a single-subject line and a group line can be prepared side by side.
 */
@Composable
private fun ModeSwitch(mode: String, sfw: Boolean, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModeButton(
            stringResource(R.string.tc_mode_solo),
            mode == TagComposerState.MODE_SOLO, sfw, Modifier.weight(1f)
        ) {
            onSelect(TagComposerState.MODE_SOLO)
        }
        ModeButton(
            stringResource(R.string.tc_mode_multi),
            mode == TagComposerState.MODE_MULTI, sfw, Modifier.weight(1f)
        ) {
            onSelect(TagComposerState.MODE_MULTI)
        }
    }
}

// 淡绿是「当前在 SFW 档」的唯一标识，两处共用：单人/多人选项框与模式药丸。
// 不跟着主题色走——主题一换就看不出档位了，这个标识的意义就没了。
private val SfwFill = Color(0xFFDCEFDF)
private val SfwInk = Color(0xFF2F6B46)

/**
 * Three switches, one of them a pair.
 *
 * 增强 and 普通 are the two faces of the single `strict` flag, so they get two rows instead of one
 * toggle labelled with the "on" state. Both rows write the same value: whichever row you touch,
 * the other lights up. There is no way to leave the pair unlit, which is why no extra guard is
 * needed here. 去重 is genuinely independent.
 */
@Composable
private fun GuardPill(
    strict: Boolean,
    avoidRepeat: Boolean,
    sfw: Boolean,
    onStrict: (Boolean) -> Unit,
    onAvoidRepeat: (Boolean) -> Unit,
    onSfw: (Boolean) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    // SFW 库没有增强模式的约束表，这一档固定按普通跑，状态里也不提「增强」。
    val strictLabel = stringResource(
        if (strict) R.string.tc_guard_strict else R.string.tc_guard_normal
    )
    val repeatLabel = stringResource(
        if (avoidRepeat) R.string.tc_guard_dedupe else R.string.tc_guard_no_dedupe
    )
    val ageLabel = if (sfw) "SFW" else stringResource(R.string.tc_guard_adult)
    val state = (if (sfw) "" else "$strictLabel · ") + "$repeatLabel · " + ageLabel
    Box {
        Surface(
            color = when {
                sfw -> SfwFill
                open -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(
                1.dp,
                if (sfw) SfwInk.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.clickable { open = true }
        ) {
            Text(
                text = "$state ▾",
                style = MaterialTheme.typography.labelMedium,
                color = if (sfw) SfwInk else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(258.dp)
        ) {
            if (!sfw) {
                GuardRow(
                    title = stringResource(R.string.tc_guard_strict_title),
                    desc = stringResource(R.string.tc_guard_strict_desc),
                    on = strict,
                    dim = !strict
                ) { onStrict(!strict); open = false }
                GuardRow(
                    title = stringResource(R.string.tc_guard_normal_title),
                    desc = stringResource(R.string.tc_guard_normal_desc),
                    on = !strict,
                    dim = strict
                ) { onStrict(!strict); open = false }
            }
            GuardRow(
                title = stringResource(R.string.tc_guard_dedupe_title),
                desc = stringResource(R.string.tc_guard_dedupe_desc),
                on = avoidRepeat
            ) { onAvoidRepeat(!avoidRepeat); open = false }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GuardRow(
                title = stringResource(R.string.tc_guard_nsfw_title),
                desc = stringResource(R.string.tc_guard_nsfw_desc),
                on = !sfw
                // 开关不收起菜单：拨完还要接着点「详细说明」，收起来等于多一步。
            ) { onSfw(!sfw) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.tc_guard_detail),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = false; showDetail = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
    if (showDetail) GuardDetailDialog(sfw = sfw, onDismiss = { showDetail = false })
}

@Composable
private fun GuardRow(
    title: String,
    desc: String,
    on: Boolean,
    dim: Boolean = false,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                // The unlit half of the mode pair is still tappable, so it is dimmed rather than
                // greyed out like a disabled control.
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = on, onCheckedChange = { onToggle() })
    }
}

/**
 * The long form of the three rows. Kept in a dialog because the reasons only make sense together:
 * "why did 普通 come on by itself" is a question about the pair, not about one switch.
 */
@Composable
private fun GuardDetailDialog(sfw: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tc_got_it)) }
        },
        title = {
            Text(
                stringResource(
                    if (sfw) R.string.tc_guard_detail_title_three
                    else R.string.tc_guard_detail_title_four
                ),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (sfw) R.string.tc_guard_detail_intro_three
                        else R.string.tc_guard_detail_intro_four
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!sfw) {
                    GuardBlock(
                        tag = stringResource(R.string.tc_guard_strict_title),
                        accent = MaterialTheme.colorScheme.primary,
                        headline = stringResource(R.string.tc_guard_strict_desc),
                        lines = listOf(
                            stringResource(R.string.tc_guard_block_strict_1),
                            stringResource(R.string.tc_guard_block_strict_2),
                            stringResource(R.string.tc_guard_block_strict_3)
                        )
                    )
                    GuardTip()
                }
                GuardBlock(
                    tag = stringResource(R.string.tc_guard_normal_title),
                    accent = MaterialTheme.colorScheme.tertiary,
                    headline = stringResource(R.string.tc_guard_normal_desc),
                    lines = listOf(
                        stringResource(R.string.tc_guard_block_normal_1),
                        stringResource(R.string.tc_guard_block_normal_2),
                        stringResource(R.string.tc_guard_block_normal_3)
                    )
                )
                GuardBlock(
                    tag = stringResource(R.string.tc_guard_dedupe_title),
                    accent = MaterialTheme.colorScheme.secondary,
                    headline = stringResource(R.string.tc_guard_dedupe_desc),
                    lines = listOf(
                        stringResource(R.string.tc_guard_block_dedupe_1),
                        stringResource(R.string.tc_guard_block_dedupe_2)
                    )
                )
                GuardBlock(
                    tag = stringResource(R.string.tc_guard_nsfw_title),
                    accent = MaterialTheme.colorScheme.tertiary,
                    headline = stringResource(R.string.tc_guard_nsfw_desc),
                    lines = listOf(
                        stringResource(R.string.tc_guard_block_nsfw_1),
                        stringResource(R.string.tc_guard_block_nsfw_2),
                        stringResource(R.string.tc_guard_block_nsfw_3)
                    )
                )
                if (!sfw) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                     Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                         Text(
                             stringResource(R.string.tc_guard_note_pair),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant
                         )
                         Text(
                             stringResource(R.string.tc_guard_note_strike),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant
                         )
                     }
                }
                }
            }
        }
    )
}

/**
 * 首次切到 SFW 的一次性说明卡。
 *
 * 三条都要留着：删掉「性感还在」那条，用户会以为没生效；删掉「只去掉露骨」那条，
 * 用户不知道开关到底拦了什么。不做二次确认，也不给「以后再说」。
 */
@Composable
private fun SfwIntroDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tc_got_it)) }
        },
        title = {
            Text(
                stringResource(R.string.tc_sfw_intro_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SfwIntroLine(
                    stringResource(R.string.tc_sfw_intro_head_1),
                    stringResource(R.string.tc_sfw_intro_body_1)
                )
                SfwIntroLine(
                    stringResource(R.string.tc_sfw_intro_head_2),
                    stringResource(R.string.tc_sfw_intro_body_2)
                )
                SfwIntroLine(
                    stringResource(R.string.tc_sfw_intro_head_3),
                    stringResource(R.string.tc_sfw_intro_body_3)
                )
            }
        }
    )
}

@Composable
private fun SfwIntroLine(head: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", style = MaterialTheme.typography.bodySmall, color = SfwInk)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                head,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuardBlock(tag: String, accent: Color, headline: String, lines: List<String>) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(color = accent, shape = RoundedCornerShape(4.dp)) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColorFor(accent),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Text(headline, style = MaterialTheme.typography.labelLarge)
        }
        lines.forEach {
            Text(
                "· $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/**
 * Sits under 增强 because it is a caveat about that row's "优化" claim, not about the page.
 */
@Composable
private fun GuardTip() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.tc_guard_tip_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                stringResource(R.string.tc_guard_tip_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    active: Boolean,
    sfw: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = when {
            active && sfw -> SfwFill
            active -> MaterialTheme.colorScheme.secondaryContainer
            sfw -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        border = if (active && sfw) BorderStroke(1.dp, SfwInk.copy(alpha = 0.35f)) else null,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                active && sfw -> SfwInk
                active -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
/**
 * One section heading. 锁住 keeps this section out of the global draw; 整段重抽 still rerolls it.
 *
 * 两个动作分开可点：整行原来统一是重抽，加一个锁之后点歪一下就把整段换掉了。
 */
@Composable
private fun SectionHeader(
    label: String,
    locked: Boolean = false,
    onToggleLock: () -> Unit = {},
    onReroll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (locked) "▪" else "▸",
            style = MaterialTheme.typography.labelMedium,
            color = if (locked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            color = if (locked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(if (locked) R.string.tc_locked else R.string.tc_lock),
            style = MaterialTheme.typography.labelSmall,
            color = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleLock)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.tc_reroll_section),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onReroll)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun CategoryRow(
    category: TagCategory,
    picks: Map<String, TagPick>,
    skipped: Boolean,
    held: Boolean = false,
    onLabelClick: () -> Unit,
    onSlotClick: (TagSlot) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (skipped) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(56.dp)
                .clickable(enabled = !skipped, onClick = onLabelClick)
                .padding(top = 4.dp)
        )
        Spacer(Modifier.width(6.dp))
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (skipped) {
                 Text(
                     stringResource(R.string.tc_skipped),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 5.dp)
                )
            } else {
                category.slots.forEach { slot ->
                    SlotPill(
                        pick = picks["${category.id}.${slot.id}"] ?: TagPick(),
                        // With one slot the category label already names the thing; two or more
                        // and 未选 × 3 is unreadable without saying which is which.
                        label = if (category.slots.size > 1) slot.label else null,
                        held = held,
                        onClick = { onSlotClick(slot) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotPill(pick: TagPick, label: String?, held: Boolean = false, onClick: () -> Unit) {
    // An ignored slot is off, not empty: showing its leftover value made the state invisible.
    val text = when {
        pick.ignored -> stringResource(R.string.tc_ignored)
        pick.value.isEmpty() -> stringResource(R.string.tc_unselected)
        else -> pick.value
    }
    // 段锁用整段淡底 + 描边，单格锁仍是实心琥珀，两层状态互不吃掉。
    val tint: Color = when {
        pick.ignored -> MaterialTheme.colorScheme.surfaceVariant
        pick.locked -> MaterialTheme.colorScheme.tertiaryContainer
        held -> MaterialTheme.colorScheme.surfaceVariant
        pick.value.isEmpty() -> Color.Transparent
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (pick.value.isEmpty() || pick.ignored) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(end = 3.dp)
            )
        }
        Surface(
            color = tint,
            shape = MaterialTheme.shapes.small,
            border = when {
                held && !pick.locked && !pick.ignored ->
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                pick.value.isEmpty() || pick.ignored ->
                    BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                else -> null
            },
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pick.locked) {
                    Spacer(Modifier.width(4.dp))
                    Text("🔒", fontSize = 9.sp, color = textColor)
                }
            }
        }
    }
}

@Composable
private fun ComposerBottomBar(
    composed: String,
    optimizeHint: String?,
    onExpand: () -> Unit,
    onRandomize: () -> Unit,
    onCopy: () -> Unit,
    onOptimize: () -> Unit
) {
    // Flat like every other settings footer: the shadow used to read as a second surface.
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().clickable(onClick = onExpand)) {
                if (composed.isBlank()) {
                     Text(
                         stringResource(R.string.tc_empty_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = composed,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            optimizeHint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = composed.isNotBlank(),
                     shape = RoundedCornerShape(13.dp)
                 ) { Text(stringResource(R.string.tc_copy)) }
                 OutlinedButton(
                     onClick = onRandomize,
                     modifier = Modifier.weight(1f).height(44.dp),
                     shape = RoundedCornerShape(13.dp)
                 ) { Text(stringResource(R.string.tc_randomize)) }
                 Button(
                     onClick = onOptimize,
                     modifier = Modifier.weight(1f).height(44.dp),
                     shape = RoundedCornerShape(13.dp)
                 ) { Text(stringResource(R.string.tc_optimize)) }
            }
        }
    }
}

@Composable
private fun FullPromptDialog(
    text: String,
    negative: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onCopyNegative: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tc_full_prompt_title)) },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
                if (negative.isNotEmpty()) {
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(16.dp)
                    )
                    Text(
                         text = stringResource(R.string.tc_negative_label),
                        style = MaterialTheme.typography.labelMedium
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(6.dp)
                    )
                    SelectionContainer {
                        Text(text = negative, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.tc_copy)) }
        },
        dismissButton = {
            if (negative.isNotEmpty()) {
                TextButton(onClick = onCopyNegative) {
                    Text(stringResource(R.string.tc_copy_negative))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tc_close)) }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SlotSheet(
    category: TagCategory,
    slot: TagSlot,
    groups: List<StyleGroup> = emptyList(),
    reason: String? = null,
    blocked: Map<String, String> = emptyMap(),
    // The snackbar sits below this sheet, so anything it says here would need the sheet dragged
    // down first. Notes raised while the sheet is open are shown inside it instead.
    note: String? = null,
    pool: List<String>,
    pick: TagPick,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onLock: () -> Unit,
    onIgnore: () -> Unit,
    onClear: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggleExcluded: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
     var manage by remember(category.id, slot.id) { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                category.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                slot.valueLabel(),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLock) {
                    Text(stringResource(if (pick.locked) R.string.tc_slot_locked else R.string.tc_slot_lock))
                }
                OutlinedButton(onClick = onIgnore) {
                    Text(stringResource(if (pick.ignored) R.string.tc_ignored else R.string.tc_ignore))
                }
                OutlinedButton(onClick = onClear) {
                    Text(stringResource(R.string.tc_clear))
                }
            }
            if (note != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                ) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            var why by remember { mutableStateOf<String?>(null) }
            if (why != null) {
                Text(
                    why!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (reason != null) {
                Text(
                     stringResource(R.string.tc_why_empty, reason),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                 Text(
                     stringResource(
                         if (manage) R.string.tc_manage_hint else R.string.tc_pick_hint
                     ),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.weight(1f)
                 )
                 TextButton(onClick = { manage = !manage }) {
                     Text(stringResource(if (manage) R.string.tc_done else R.string.tc_manage))
                 }
            }
            Spacer(Modifier.height(4.dp))

            if (groups.isEmpty()) {
                PoolChips(
                    pool, pick.value, slot.excluded, manage, blocked,
                     { tag, why2 -> why = context.getString(R.string.tc_word_reason, tag, why2) },
                     onPick, onToggleExcluded, onRemove
                 )
             } else {
                // 33 mediums in one flat run is a wall of chips. The library ships the three
                // buckets the player actually thinks in, and anything hand-added lands last.
                val known = groups.flatMap { it.words }.toSet()
                val loose = pool.filter { it !in known }
                val sections = groups.map {
                    it.label.ifBlank { stringResource(R.string.lib_other_group) } to
                        it.words.filter { word -> word in pool }
                }
                    .filter { it.second.isNotEmpty() } +
                    if (loose.isEmpty()) emptyList()
                    else listOf(stringResource(R.string.tc_other) to loose)
                sections.forEach { (label, words) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                    PoolChips(
                        words, pick.value, slot.excluded, manage, blocked,
                        { tag, why2 -> why = context.getString(R.string.tc_word_reason, tag, why2) },
                        onPick, onToggleExcluded, onRemove
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                     label = { Text(stringResource(R.string.tc_add_to_group)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onAdd(draft)
                            draft = ""
                        }
                    }
                ) {
                     Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tc_add))
                }
            }
        }
    }
}

private fun TagSlot.valueLabel(): String = if (label.isBlank()) id else label

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoolChips(
    words: List<String>,
    selected: String,
    excluded: List<String>,
    manage: Boolean,
    blocked: Map<String, String> = emptyMap(),
    onBlocked: (String, String) -> Unit = { _, _ -> },
    onPick: (String) -> Unit,
    onToggleExcluded: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        words.forEach { tag ->
            val why = blocked[tag]
            PoolChip(
                tag = tag,
                selected = tag == selected,
                excluded = tag in excluded,
                manage = manage,
                blockedReason = why,
                onClick = { if (why != null) onBlocked(tag, why) else if (!manage) onPick(tag) },
                onToggleExcluded = { onToggleExcluded(tag) },
                onRemove = { onRemove(tag) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoolChip(
    tag: String,
    selected: Boolean,
    excluded: Boolean,
    manage: Boolean,
    blockedReason: String? = null,
    onClick: () -> Unit,
    onToggleExcluded: () -> Unit,
    onRemove: () -> Unit
) {
    // A greyed word is one that directly contradicts something already chosen. It stays visible so
    // the pool does not look like it shrank, and stays tappable so the reason can be explained.
    val container = when {
        blockedReason != null -> MaterialTheme.colorScheme.surfaceVariant
        selected -> MaterialTheme.colorScheme.primaryContainer
        excluded -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        blockedReason != null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(
                start = 9.dp,
                end = if (manage) 2.dp else 9.dp,
                top = 5.dp,
                bottom = 5.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                textDecoration = if (blockedReason != null) TextDecoration.LineThrough else null
            )
            if (manage) {
                Text(
                    if (excluded) "🚫" else "👁",
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onToggleExcluded)
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                )
                Text(
                    "🗑",
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onRemove)
                        .padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("prompt", text))
}

