package sh.hnet.comfychair.ui.components.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A choice of file names (models, LoRAs) in the shape the ported H3 panel is written against.
 *
 * The options sheet shows choices as list rows ([ChoiceRow]) with the chosen value as the headline; this keeps that
 * row and its menu ([ChoiceMenu]), so the two sides share one look and one set of behaviours (folders, long names).
 */
@Composable
fun ModelDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    ChoiceRow(
        role = label,
        selectedValue = selectedValue,
        options = options,
        onValueChange = onValueChange,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    )
}
