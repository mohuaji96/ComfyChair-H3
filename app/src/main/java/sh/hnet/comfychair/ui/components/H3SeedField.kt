package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.H3Seed

/**
 * The seed control: a shuffle button that means "roll a fresh one every run", the value itself
 * with a one-step nudge on either side, and a die that rolls one right now.
 *
 * The title is a plain row label rather than the floating notch ComfyUI draws. A floating label
 * needs an opaque patch to sit on, and a Material card is not a flat colour — the patch showed
 * up as a white sticker on the warm card, and this was the only control in the sheet wearing one.
 */
@Composable
fun H3SeedField(
    value: String,
    auto: Boolean,
    enabled: Boolean,
    onAutoChange: (Boolean) -> Unit,
    onRoll: () -> Unit,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val editable = enabled && !auto

    fun step(delta: Long) {
        val current = value.toLongOrNull() ?: 0L
        val next = (current + delta).coerceIn(0L, H3Seed.MAX)
        onValueChange(next.toString())
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.seed_title),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface
            )
            Box(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (auto) R.string.seed_mode_random else R.string.seed_mode_fixed
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (auto) colors.primary else colors.onSurfaceVariant
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Surface(
                onClick = { onAutoChange(!auto) },
                enabled = enabled,
                shape = CircleShape,
                color = if (auto) colors.primary else colors.surface,
                contentColor = if (auto) colors.onPrimary else colors.onSurfaceVariant,
                border = if (auto) null else androidx.compose.foundation.BorderStroke(
                    1.dp, colors.outlineVariant
                ),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = stringResource(R.string.seed_mode_random),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(
                        1.dp,
                        if (editable) colors.outline else colors.outlineVariant,
                        RoundedCornerShape(22.dp)
                    )
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepCell("−", editable) { step(-1L) }
                    BasicTextField(
                        value = value,
                        onValueChange = { raw -> onValueChange(raw.filter(Char::isDigit)) },
                        enabled = editable,
                        singleLine = true,
                        textStyle = TextStyle(
                            textAlign = TextAlign.Center,
                            color = if (editable) colors.onSurface else colors.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    StepCell("+", editable) { step(1L) }
                }
            }

            Surface(
                onClick = onRoll,
                // While the number is being rolled on every run, a manual roll would be
                // overwritten by the next submit — so the die only lives in pinned mode.
                enabled = enabled && !auto,
                shape = CircleShape,
                color = colors.surface,
                contentColor = if (enabled && !auto) colors.onSurfaceVariant else colors.outlineVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Casino,
                        contentDescription = stringResource(R.string.seed_cd_roll),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        if (!auto) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { onAutoChange(true) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        stringResource(R.string.seed_restore_random),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

/** One of the two nudges inside the pill. Laid out to keep the number dead centre. */
@Composable
private fun StepCell(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp, 36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 17.sp,
            color = if (enabled) colors.onSurfaceVariant else colors.outlineVariant.copy(alpha = 0.6f)
        )
    }
}
