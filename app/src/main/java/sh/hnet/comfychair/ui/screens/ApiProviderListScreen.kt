package sh.hnet.comfychair.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ApiFormat
import sh.hnet.comfychair.storage.ApiProviderStore
import sh.hnet.comfychair.ui.components.BottomActionBar
import sh.hnet.comfychair.ui.components.SettingsDetailScaffold
import sh.hnet.comfychair.ui.components.SettingsGroupCard
import sh.hnet.comfychair.ui.components.SettingsItemRow
import sh.hnet.comfychair.ui.components.SettingsPrimaryButton
import sh.hnet.comfychair.ui.components.SettingsRowDivider
import sh.hnet.comfychair.ui.components.SettingsSectionLabel
import sh.hnet.comfychair.viewmodel.PromptOptimizerViewModel

/**
 * Picking a provider used to be a dropdown on the edit form, which made "which one" and "how it is
 * configured" the same screen. They are separate now: this list only chooses or adds.
 */
@Composable
fun ApiProviderListScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: PromptOptimizerViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.initialize(context) }

    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val currentId by viewModel.currentId.collectAsStateWithLifecycle()
    // hasKey touches encrypted storage; recompute only when the list itself changes.
    val keys = remember(providers) {
        providers.associate { it.id to ApiProviderStore.hasApiKey(context, it.id) }
    }
    var menuFor by remember { mutableStateOf<String?>(null) }

    SettingsDetailScaffold(
        title = stringResource(R.string.po_providers_title),
        onBack = onBack,
        status = {
            TextButton(onClick = {
                viewModel.addProvider()?.let { onEdit(it.id) }
            }) { Text(stringResource(R.string.po_add_short)) }
        },
        bottomBar = {
            BottomActionBar {
                SettingsPrimaryButton(
                    text = stringResource(R.string.po_new_provider),
                    onClick = { viewModel.addProvider()?.let { onEdit(it.id) } },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        SettingsSectionLabel(stringResource(R.string.po_all), note = stringResource(R.string.po_provider_count, providers.size))

        if (providers.isEmpty()) {
            Text(
                stringResource(R.string.po_list_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            return@SettingsDetailScaffold
        }

        SettingsGroupCard {
            providers.forEachIndexed { index, provider ->
                if (index > 0) SettingsRowDivider()
                SettingsItemRow(
                    title = provider.name.ifBlank { stringResource(R.string.po_unnamed_provider) },
                    meta = listOf(
                        provider.model.ifBlank { stringResource(R.string.po_no_model) },
                        formatName(context, provider.format),
                        if (keys[provider.id] == true) stringResource(R.string.po_key_set) else stringResource(R.string.po_key_missing)
                    ).joinToString(" · "),
                    active = provider.id == currentId,
                    onClick = { onEdit(provider.id) },
                    trailing = {
                        Box {
                            IconButton(onClick = { menuFor = provider.id }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.po_more),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = menuFor == provider.id,
                                onDismissRequest = { menuFor = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.po_duplicate)) },
                                    onClick = {
                                        menuFor = null
                                        viewModel.duplicate(provider.id)?.let { onEdit(it.id) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.po_move_up)) },
                                    enabled = index > 0,
                                    onClick = {
                                        menuFor = null
                                        viewModel.move(provider.id, -1)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.po_move_down)) },
                                    enabled = index < providers.lastIndex,
                                    onClick = {
                                        menuFor = null
                                        viewModel.move(provider.id, 1)
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }

        SettingsSectionLabel(stringResource(R.string.po_note_section))
        SettingsGroupCard {
            Text(
                stringResource(R.string.po_list_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun formatName(context: Context, format: ApiFormat): String = when (format) {
    ApiFormat.OPENAI_CHAT -> context.getString(R.string.po_format_openai_compat)
    ApiFormat.OPENAI_RESPONSES -> "OpenAI Responses"
    ApiFormat.ANTHROPIC -> "Anthropic"
    ApiFormat.GEMINI -> "Gemini"
}

