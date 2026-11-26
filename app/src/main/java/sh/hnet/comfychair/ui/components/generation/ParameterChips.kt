package sh.hnet.comfychair.ui.components.generation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.ui.components.shared.ConstrainedNumberField
import sh.hnet.comfychair.ui.components.shared.NumberParameter
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel

private val PopupWidth = 280.dp

// Four chips plus the gaps have to fit the popup's content width, so the cells share one size.
private val SizeRatioChipWidth = 60.dp
private val SizeRatioChipHeight = 32.dp

/** One of the shapes the size popup offers, as width : height. */
internal data class SizeRatio(val label: String, val width: Int, val height: Int)

/** The shapes in the two rows the popup shows: four across, then three. */
internal val SizeRatioRows = listOf(
    listOf(SizeRatio("3:4", 3, 4), SizeRatio("4:3", 4, 3), SizeRatio("2:3", 2, 3), SizeRatio("3:2", 3, 2)),
    listOf(SizeRatio("1:1", 1, 1), SizeRatio("9:16", 9, 16), SizeRatio("16:9", 16, 9))
)

/** Flat view of the same shapes, for the popup's readout of which one the numbers already make. */
internal val SizeRatios: List<SizeRatio> = SizeRatioRows.flatten()

// The width and height fields step by 8, so a computed side is snapped to that grid.
private const val SIZE_ALIGN = 8
private const val SIZE_MIN = 16
private const val SIZE_MAX = 16384
private const val SIZE_DEFAULT_SIDE = 1024

/**
 * The two sides for [ratio], keeping the side that was typed and filling the other in: with 1080
 * typed and 9:16 picked, this answers 1080×1920. The typed side is kept exactly as it stands, only
 * the side the app fills is aligned to the grid the fields step on.
 */
internal fun sizeFromAnchor(anchor: Int, ratio: SizeRatio, keepWidth: Boolean): Pair<Int, Int> {
    val base = if (anchor > 0) anchor else SIZE_DEFAULT_SIDE
    val factor = if (keepWidth) ratio.height.toDouble() / ratio.width else ratio.width.toDouble() / ratio.height
    val filled = ((base * factor / SIZE_ALIGN).roundToInt() * SIZE_ALIGN).coerceIn(SIZE_MIN, SIZE_MAX)
    return if (keepWidth) base to filled else filled to base
}

/**
 * The dock's parameter chips, after the workflow chip: one fixed set in a fixed order (size or megapixels,
 * steps, seed, length, scale), each only when the workflow has that field – the options sheet's quick fields. A chip shows the value; tapping it opens
 * a small popup above it with the same control as the options sheet. Values apply at once; tapping outside closes it.
 */
@Composable
fun ParameterChips(state: CommonGenerationState, viewModel: BaseGenerationViewModel<*>) {
    val caps = state.capabilities
    val workflowName = state.selectedWorkflow

    // Size leads the row: it is read at a glance far more often than the seed, and the row is cut off
    // on a narrow dock, so the two numbers people reach for should not be the ones pushed out.
    if (caps.hasWidth && caps.hasHeight) {
        ParameterChip(
            title = stringResource(R.string.label_size),
            label = "${state.width}×${state.height}",
            isError = state.widthError != null || state.heightError != null
        ) {
            // Which side a ratio keeps: whatever was typed last, and when only one side has a number,
            // plainly that side – filling 1080 and picking 9:16 should give height, not a new width.
            var lastTypedWidth by remember { mutableStateOf(true) }
            val width = state.width.toIntOrNull() ?: 0
            val height = state.height.toIntOrNull() ?: 0
            val keepWidth = when {
                width > 0 && height <= 0 -> true
                height > 0 && width <= 0 -> false
                else -> lastTypedWidth
            }
            // A shape first, the exact numbers second: picking 9:16 twice as fast as typing it
            SizeRatioRow(
                currentWidth = width,
                currentHeight = height,
                keepWidth = keepWidth
            ) { nextWidth, nextHeight ->
                viewModel.onWidthChange(nextWidth.toString())
                viewModel.onHeightChange(nextHeight.toString())
            }
            // One under the other: side by side, a menu-wide popup leaves too little room for four digits
            ConstrainedNumberField(
                NumberParameter.WIDTH, workflowName, state.width,
                { lastTypedWidth = true; viewModel.onWidthChange(it) }, state.widthError
            )
            ConstrainedNumberField(
                NumberParameter.HEIGHT, workflowName, state.height,
                { lastTypedWidth = false; viewModel.onHeightChange(it) }, state.heightError
            )
        }
    } else if (caps.hasMegapixels) {
        ParameterChip(
            title = stringResource(R.string.label_megapixels),
            label = stringResource(R.string.label_megapixels_value, state.megapixels),
            isError = state.megapixelsError != null
        ) {
            ConstrainedNumberField(
                NumberParameter.MEGAPIXELS, workflowName, state.megapixels, viewModel::onMegapixelsChange, state.megapixelsError
            )
        }
    }
    if (caps.hasSteps) {
        val steps = state.steps.toIntOrNull()
        ParameterChip(
            title = stringResource(R.string.label_steps),
            label = steps?.let { pluralStringResource(R.plurals.label_steps_count, it, it) } ?: state.steps,
            isError = state.stepsError != null
        ) {
            ConstrainedNumberField(NumberParameter.STEPS, workflowName, state.steps, viewModel::onStepsChange, state.stepsError)
        }
    }
    if (caps.hasSeed) SeedChip(state, viewModel)
    if (caps.hasLength) {
        val length = state.length.toIntOrNull()
        ParameterChip(
            title = stringResource(R.string.label_length),
            label = length?.let { pluralStringResource(R.plurals.label_frames_count, it, it) } ?: state.length,
            isError = state.lengthError != null
        ) {
            ConstrainedNumberField(NumberParameter.LENGTH, workflowName, state.length, viewModel::onLengthChange, state.lengthError)
        }
    }
    if (caps.hasScaleBy) {
        ParameterChip(
            title = stringResource(R.string.label_scale_by),
            label = stringResource(R.string.label_scale_value, state.scaleBy),
            isError = state.scaleByError != null
        ) {
            ConstrainedNumberField(NumberParameter.SCALE_BY, workflowName, state.scaleBy, viewModel::onScaleByChange, state.scaleByError)
        }
    }
}

/** Seed: a die when random, a lock and the number when fixed; the popup switches between them. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SeedChip(state: CommonGenerationState, viewModel: BaseGenerationViewModel<*>) {
    val lastSeed by viewModel.lastSeed.collectAsState()
    ParameterChip(
        title = stringResource(R.string.label_seed),
        label = if (state.randomSeed) stringResource(R.string.option_seed_random) else state.seed,
        icon = if (state.randomSeed) Icons.Rounded.Casino else Icons.Rounded.Lock,
        isError = !state.randomSeed && state.seedError != null
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(
                checked = state.randomSeed,
                onCheckedChange = { if (it && !state.randomSeed) viewModel.onRandomSeedToggle() },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.option_seed_random)) }
            ToggleButton(
                checked = !state.randomSeed,
                onCheckedChange = { if (it && state.randomSeed) viewModel.onRandomSeedToggle() },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.option_seed_fixed)) }
        }
        // A plain field across the popup: a seed has up to 19 digits, and stepping it by one means nothing
        OutlinedTextField(
            value = state.seed,
            onValueChange = viewModel::onSeedChange,
            label = { Text(stringResource(R.string.label_seed)) },
            enabled = !state.randomSeed,
            singleLine = true,
            isError = state.seedError != null,
            supportingText = state.seedError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            trailingIcon = {
                TooltipIconButton(
                    Icons.Rounded.Casino,
                    stringResource(R.string.label_randomize_seed),
                    viewModel::onRandomizeSeed,
                    enabled = !state.randomSeed
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        // The seed the last job of this screen actually got, also when it was random
        TextButton(onClick = viewModel::onReuseLastSeed, enabled = lastSeed != null) {
            Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.button_reuse_last_seed),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * The shapes above the two size fields. The one the current numbers already make is checked, so the
 * row doubles as a readout; a row of chips rather than a menu, since there are only seven. Picking one
 * keeps [keepWidth]'s side as it stands and fills in the other.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SizeRatioRow(
    currentWidth: Int,
    currentHeight: Int,
    keepWidth: Boolean,
    onPick: (Int, Int) -> Unit
) {
    val current = SizeRatios.firstOrNull { ratio ->
        currentWidth > 0 && currentHeight > 0 &&
            ratio.width * currentHeight == ratio.height * currentWidth
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Two rows instead of one scrolling row: seven chips never fit the popup's width, and the
        // ones scrolled out of sight looked like a clipped button.
        SizeRatioRows.forEach { ratios ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ratios.forEach { ratio ->
                    SizeRatioChip(
                        ratio = ratio,
                        selected = ratio == current,
                        onClick = {
                            val anchor = if (keepWidth) currentWidth else currentHeight
                            val (width, height) = sizeFromAnchor(anchor, ratio, keepWidth)
                            onPick(width, height)
                        }
                    )
                }
            }
        }
        // Which side survives a ratio change is the part that used to surprise: say it before it happens.
        Text(
            text = if (keepWidth) "以宽度为准 · 点比例只改高度" else "以高度为准 · 点比例只改宽度",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One shape: a rectangle drawn at the ratio beside its numbers. Fixed width so the four columns of
 * the first row line up with the three of the second.
 */
@Composable
private fun SizeRatioChip(ratio: SizeRatio, selected: Boolean, onClick: () -> Unit) {
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .size(width = SizeRatioChipWidth, height = SizeRatioChipHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = ratio.label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RatioGlyph(ratio, content)
        Spacer(Modifier.width(4.dp))
        Text(ratio.label, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

/** The rectangle itself, longest side fixed so the shapes stay comparable at a glance. */
@Composable
private fun RatioGlyph(ratio: SizeRatio, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val long = size.minDimension
        val wide = ratio.width >= ratio.height
        val width = if (wide) long else long * ratio.width / ratio.height
        val height = if (wide) long * ratio.height / ratio.width else long
        drawRoundRect(
            color = color,
            topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f),
            size = Size(width, height),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(width = 1.2.dp.toPx())
        )
    }
}

/**
 * A chip with a value that opens a small popup above it; the chip looks selected while the popup is open.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ParameterChip(
    title: String,
    label: String,
    isError: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = open,
            onClick = { open = true },
            label = {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) } },
            modifier = Modifier.semantics { contentDescription = "$title, $label" }
        )
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                Column(
                    modifier = Modifier
                        .width(PopupWidth)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                    content()
                }
            }
        }
    }
}
