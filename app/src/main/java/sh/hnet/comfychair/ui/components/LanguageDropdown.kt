package sh.hnet.comfychair.ui.components

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R

/**
 * Data class representing a language option for the language selector.
 * @param localeTag The BCP 47 language tag (e.g., "en", "de") or empty string for system default
 * @param displayNameResId Resource ID for the display name (native name for languages)
 */
data class LanguageOption(
    val localeTag: String,
    @StringRes val displayNameResId: Int
)

/**
 * Dropdown component for selecting the app language.
 * Uses ExposedDropdownMenuBox for consistent styling with other dropdowns in the app.
 *
 * @param languages List of available language options
 * @param selectedLocaleTag Currently selected locale tag, or empty string for system default
 * @param onLanguageSelected Called when a language is selected, with the locale tag
 * @param modifier Modifier for the dropdown container
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDropdown(
    languages: List<LanguageOption>,
    selectedLocaleTag: String,
    onLanguageSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Find the display name for the selected language
    val selectedLanguage = languages.find { it.localeTag == selectedLocaleTag }
    val displayText = selectedLanguage?.let { stringResource(it.displayNameResId) } ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_language)) },
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
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(stringResource(language.displayNameResId)) },
                    onClick = {
                        onLanguageSelected(language.localeTag)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
