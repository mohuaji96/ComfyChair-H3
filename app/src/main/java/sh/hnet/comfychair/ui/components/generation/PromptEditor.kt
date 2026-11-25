package sh.hnet.comfychair.ui.components.generation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.PromptOptimizeSheet
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.viewmodel.PromptPresetUiState
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel

/** The two prompts of a workflow, as tabs of the prompt editor. */
enum class PromptTab { POSITIVE, NEGATIVE }

private val ComposerShape = RoundedCornerShape(24.dp)

/**
 * The prompt in the dock and its full-screen editor.
 * The dock allows direct inline editing (fixed 3-line height), with top-right Clear and bottom-right Fullscreen expand.
 */
@Composable
fun PromptDock(
    state: CommonGenerationState,
    onPositiveChange: (String) -> Unit,
    onNegativeChange: (String) -> Unit,
    preview: Bitmap?,
    presetViewModel: PromptPresetViewModel,
    presetUiState: PromptPresetUiState,
    spellCheckEnabled: Boolean,
    generateEnabled: Boolean,
    onGenerate: () -> Unit,
    // The optimizer's template is written for still images, so only the image-from-text screen offers it.
    optimizeEnabled: Boolean = false
) {
    val hasPositive = state.capabilities.hasPositivePrompt
    val hasNegative = state.capabilities.hasNegativePrompt
    if (!hasPositive && !hasNegative) return
    val context = LocalContext.current
    var editorTab by rememberSaveable { mutableStateOf<PromptTab?>(null) }
    var showOptimizeSheet by rememberSaveable { mutableStateOf(false) }
    val optimizeAction: (() -> Unit)? = if (optimizeEnabled) ({ showOptimizeSheet = true }) else null

    val positiveChange: (String) -> Unit = {
        onPositiveChange(it)
        presetViewModel.clearActivePreset()
    }

    if (hasPositive) {
        PromptComposer(
            text = state.positivePrompt,
            onTextChange = positiveChange,
            placeholderRes = R.string.hint_describe_generation,
            spellCheckEnabled = spellCheckEnabled,
            onOpenFullscreen = { editorTab = PromptTab.POSITIVE },
            onClear = { positiveChange("") },
            leading = {
                PromptLibraryButton(
                    positivePrompt = state.positivePrompt,
                    presetViewModel = presetViewModel,
                    presetUiState = presetUiState,
                    onOptimizePrompt = optimizeAction
                )
            }
        )
    } else {
        PromptComposer(
            text = state.negativePrompt,
            onTextChange = onNegativeChange,
            placeholderRes = R.string.hint_negative_prompt,
            spellCheckEnabled = spellCheckEnabled,
            onOpenFullscreen = { editorTab = PromptTab.NEGATIVE },
            onClear = { onNegativeChange("") }
        )
    }

    editorTab?.let { tab ->
        PromptEditor(
            initialTab = tab,
            hasPositive = hasPositive,
            hasNegative = hasNegative,
            positive = state.positivePrompt,
            onPositiveChange = positiveChange,
            negative = state.negativePrompt,
            onNegativeChange = onNegativeChange,
            preview = preview,
            presetViewModel = presetViewModel,
            presetUiState = presetUiState,
            spellCheckEnabled = spellCheckEnabled,
            generateEnabled = generateEnabled,
            onOptimizePrompt = optimizeAction,
            onGenerate = {
                onGenerate()
                editorTab = null
            },
            onDismiss = { editorTab = null }
        )
    }

    if (showOptimizeSheet) {
        PromptOptimizeSheet(
            libraryPrompt = state.positivePrompt,
            onDismiss = { showOptimizeSheet = false },
            onCopy = { text ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("prompt", text))
                Toast.makeText(context, context.getString(R.string.pe_copied), Toast.LENGTH_SHORT).show()
                showOptimizeSheet = false
            },
            onApply = { text ->
                positiveChange(text)
                showOptimizeSheet = false
            }
        )
    }
}

/**
 * Inline-editable 3-line prompt field in the dock.
 * Left: bookmark menu. Center: 3-line editable text field.
 * Right: top-right Clear (×) and bottom-right Fullscreen (⤢), vertically separated to prevent accidental taps.
 */
@Composable
private fun PromptComposer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholderRes: Int,
    spellCheckEnabled: Boolean,
    onOpenFullscreen: () -> Unit,
    onClear: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val visualTransformation = rememberSpellCheckVisualTransformation(text = text, enabled = spellCheckEnabled)
    Surface(
        shape = ComposerShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 92.dp)
                .padding(
                    start = if (leading != null) 4.dp else 20.dp,
                    end = 4.dp,
                    top = 4.dp,
                    bottom = 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 76.dp)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.TopStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(placeholderRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = spellCheckEnabled
                    ),
                    visualTransformation = visualTransformation,
                    minLines = 3,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Column(
                modifier = Modifier.height(84.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (text.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.content_description_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(36.dp))
                }
                IconButton(
                    onClick = onOpenFullscreen,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.OpenInFull,
                        contentDescription = stringResource(R.string.pe_cd_fullscreen),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptLibraryButton(
    positivePrompt: String,
    presetViewModel: PromptPresetViewModel,
    presetUiState: PromptPresetUiState,
    onOptimizePrompt: (() -> Unit)? = null
) {
    PromptPresetDropdown(
        favorites = presetUiState.favorites,
        activePresetId = presetUiState.activePresetId,
        currentPromptIsEmpty = positivePrompt.isBlank(),
        onPresetSelected = { presetViewModel.onPresetSelected(it) },
        onOpenLibrary = { presetViewModel.showLibrary() },
        onSaveCurrentPrompt = { presetViewModel.showSaveDialog(positivePrompt) },
        onResetPrompt = { presetViewModel.resetPrompt() },
        onOptimizePrompt = onOptimizePrompt
    )
}

/**
 * Full-screen editor for the positive and negative prompts.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PromptEditor(
    initialTab: PromptTab,
    hasPositive: Boolean,
    hasNegative: Boolean,
    positive: String,
    onPositiveChange: (String) -> Unit,
    negative: String,
    onNegativeChange: (String) -> Unit,
    preview: Bitmap?,
    presetViewModel: PromptPresetViewModel,
    presetUiState: PromptPresetUiState,
    spellCheckEnabled: Boolean,
    generateEnabled: Boolean,
    onOptimizePrompt: (() -> Unit)?,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val text = when (tab) {
        PromptTab.POSITIVE -> positive
        PromptTab.NEGATIVE -> negative
    }
    val onTextChange = when (tab) {
        PromptTab.POSITIVE -> onPositiveChange
        PromptTab.NEGATIVE -> onNegativeChange
    }
    var fieldValue by remember(tab) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (fieldValue.text != text) {
        fieldValue = fieldValue.copy(text = text, selection = TextRange(text.length))
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(tab) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    preview?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    if (hasPositive && hasNegative) {
                        PromptTabGroup(tab = tab, onTabChange = { tab = it })
                    } else {
                        Text(
                            text = stringResource(
                                if (hasPositive) R.string.tab_prompt_positive else R.string.tab_prompt_negative
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TooltipIconButton(Icons.Rounded.Check, stringResource(R.string.button_done), onDismiss)
                }

                // Writing area
                TextField(
                    value = fieldValue,
                    onValueChange = {
                        fieldValue = it
                        if (it.text != text) onTextChange(it.text)
                    },
                    placeholder = {
                        Text(
                            stringResource(
                                when (tab) {
                                    PromptTab.POSITIVE -> R.string.hint_describe_generation
                                    PromptTab.NEGATIVE -> R.string.hint_negative_prompt
                                }
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = spellCheckEnabled
                    ),
                    visualTransformation = rememberSpellCheckVisualTransformation(text = fieldValue.text, enabled = spellCheckEnabled),
                    shape = ComposerShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .focusRequester(focusRequester)
                )

                // Bottom bar: prompt actions on the left + AI Optimize + Generate on the right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tab == PromptTab.POSITIVE) {
                        PromptLibraryButton(
                            positivePrompt = positive,
                            presetViewModel = presetViewModel,
                            presetUiState = presetUiState,
                            onOptimizePrompt = onOptimizePrompt
                        )
                        TooltipIconButton(
                            icon = Icons.Rounded.BookmarkAdd,
                            label = stringResource(R.string.metadata_save_prompt),
                            onClick = { presetViewModel.showSaveDialog(positive) },
                            enabled = positive.isNotBlank()
                        )
                    }
                    TooltipIconButton(
                        icon = Icons.AutoMirrored.Rounded.Backspace,
                        label = stringResource(R.string.content_description_clear),
                        onClick = { onTextChange("") },
                        enabled = text.isNotEmpty()
                    )
                    if (tab == PromptTab.POSITIVE && onOptimizePrompt != null) {
                        Spacer(Modifier.width(4.dp))
                        FilledTonalButton(
                            onClick = onOptimizePrompt,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.pe_optimize))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val size = ButtonDefaults.MediumContainerHeight
                    Button(
                        onClick = onGenerate,
                        enabled = generateEnabled,
                        modifier = Modifier.heightIn(min = size),
                        contentPadding = ButtonDefaults.contentPaddingFor(size)
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(size))
                        )
                        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(size)))
                        Text(stringResource(R.string.button_generate), fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PromptTabGroup(tab: PromptTab, onTabChange: (PromptTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        PromptTab.entries.forEachIndexed { index, item ->
            val selected = item == tab
            ToggleButton(
                checked = selected,
                onCheckedChange = { if (it) onTabChange(item) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    else -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                }
            ) {
                Text(
                    stringResource(
                        when (item) {
                            PromptTab.POSITIVE -> R.string.tab_prompt_positive
                            PromptTab.NEGATIVE -> R.string.tab_prompt_negative
                        }
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
