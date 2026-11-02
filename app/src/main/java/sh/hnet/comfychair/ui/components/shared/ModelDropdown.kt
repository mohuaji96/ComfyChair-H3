package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A dropdown component for selecting models (checkpoints, UNETs, VAEs, CLIPs, etc.)
 *
 * Automatically switches between flat and hierarchical (folder tree) display based on
 * whether any option contains path separators.
 *
 * Hierarchical behavior:
 * - Folders shown first, then root-level models, both sorted alphabetically
 * - Clicking a folder expands it (accordion: collapses siblings at the same level)
 * - Re-opening auto-expands to the currently selected model's folder
 * - Selected model highlighted in primary color
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val tree = remember(options) { buildModelTree(options) }
    val hasFolders = remember(tree) { modelTreeHasFolders(tree) }
    var expandedPath by remember(selectedValue) { mutableStateOf(folderPathOf(selectedValue)) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it) expandedPath = folderPathOf(selectedValue)
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (!hasFolders) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { ModelPathText(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            } else {
                HierarchicalTreeItems(
                    nodes = tree,
                    expandedPath = expandedPath,
                    selectedValue = selectedValue,
                    depth = 0,
                    onExpandFolder = { expandedPath = it },
                    onSelectModel = {
                        onValueChange(it)
                        expanded = false
                    }
                )
            }
        }
    }
}
