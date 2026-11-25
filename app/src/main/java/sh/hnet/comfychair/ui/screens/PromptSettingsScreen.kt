package sh.hnet.comfychair.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ApiFormat
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.storage.PromptTemplateStore
import sh.hnet.comfychair.ui.components.OnResume
import sh.hnet.comfychair.ui.components.SettingsDetailScaffold
import sh.hnet.comfychair.ui.components.SettingsFootnote
import sh.hnet.comfychair.ui.components.SettingsGroupCard
import sh.hnet.comfychair.ui.components.SettingsNavRow
import sh.hnet.comfychair.ui.components.SettingsRowDivider
import sh.hnet.comfychair.ui.components.SettingsSectionLabel
import sh.hnet.comfychair.ui.components.SettingsSegmented
import sh.hnet.comfychair.ui.components.SettingsPrimaryButton
import sh.hnet.comfychair.viewmodel.PromptOptimizerViewModel
import java.util.Locale

/**
 * Home of the optimiser settings: who does the work, which template it sends, and the two knobs
 * that change per run.
 *
 * No action button lives here on purpose — the optimiser is started from the tag composer sheet,
 * and a "start" button on a settings page is what made the old screen read as a control panel.
 */
@Composable
fun PromptSettingsScreen(
    onOpenProviders: () -> Unit,
    onOpenTemplates: () -> Unit,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit,
    viewModel: PromptOptimizerViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.initialize(context) }

    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val currentId by viewModel.currentId.collectAsStateWithLifecycle()
    var templateVersion by remember { mutableIntStateOf(0) }
    val activeTemplate = remember(templateVersion) { PromptTemplateStore.active(context) }
    val current = providers.firstOrNull { it.id == currentId } ?: providers.firstOrNull()

    // The template name lives in its own prefs file, so it is re-read when this page comes back.
    OnResume { templateVersion++ }

    // One-time move of the old per-provider prompt into the library, so edits survive.
    LaunchedEffect(providers.isNotEmpty()) {
        if (providers.isEmpty()) return@LaunchedEffect
        // Runs every visit: a restored backup can bring the phantom copy back, and it costs one
        // string compare per custom template.
        PromptTemplateStore.pruneBuiltInCopies(context)
        if (!PromptTemplateStore.isMigrated(context)) {
            PromptTemplateStore.migrateLegacyPrompt(context, providers.first().systemPrompt)
        }
        templateVersion++
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_prompt_optimize_title),
        onBack = onNavigateToGeneration
    ) {
        Spacer(Modifier.height(4.dp))

        if (current == null) {
            Text(
                stringResource(R.string.po_no_provider),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
            )
            SettingsPrimaryButton(
                text = stringResource(R.string.po_add_provider),
                onClick = onOpenProviders,
                modifier = Modifier.fillMaxWidth()
            )
            return@SettingsDetailScaffold
        }

        SettingsSectionLabel(stringResource(R.string.po_current))
        SettingsGroupCard {
            SettingsNavRow(
                label = stringResource(R.string.po_provider),
                value = current.name.ifBlank { stringResource(R.string.po_unnamed_provider) },
                sub = formatName(context, current.format) + " · " + current.model.ifBlank { stringResource(R.string.po_no_model) },
                onClick = onOpenProviders
            )
            SettingsRowDivider()
            SettingsNavRow(
                label = stringResource(R.string.po_template),
                value = activeTemplate.name,
                sub = (if (activeTemplate.builtIn) stringResource(R.string.po_builtin) else stringResource(R.string.po_custom)) + " · " +
                    stringResource(R.string.po_lines, activeTemplate.body.lineSequence().count()),
                onClick = onOpenTemplates
            )
        }

        SettingsSectionLabel(stringResource(R.string.po_this_run))
        SettingsGroupCard {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.po_output_language),
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SettingsSegmented(
                    options = listOf(stringResource(R.string.po_lang_english), stringResource(R.string.po_lang_chinese)),
                    selected = if (current.english) 0 else 1,
                    onSelect = { index ->
                        val english = index == 0
                        if (english != current.english) {
                            viewModel.upsert(current.copy(english = english))
                        }
                    }
                )
            }
            SettingsRowDivider()
            TemperatureBlock(
                value = current.temperature,
                onChange = { viewModel.upsert(current.copy(temperature = it)) }
            )
        }

        SettingsFootnote(stringResource(R.string.po_footnote))
    }
}

@Composable
private fun TemperatureBlock(value: Float, onChange: (Float) -> Unit) {
    // Held locally so dragging does not write to disk on every pixel; the store gets the final
    // value when the finger lifts.
    var draft by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.po_temperature),
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                String.format(Locale.US, "%.2f", draft),
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = (it * 20).toInt() / 20f },
            onValueChangeFinished = { onChange(draft) },
            valueRange = 0f..1f,
            steps = 19
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.po_temp_low),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
            Text(
                stringResource(R.string.po_temp_high),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
    }
}

private fun formatName(context: Context, format: ApiFormat): String = when (format) {
    ApiFormat.OPENAI_CHAT -> context.getString(R.string.po_format_openai_compat)
    ApiFormat.OPENAI_RESPONSES -> "OpenAI Responses"
    ApiFormat.ANTHROPIC -> "Anthropic"
    ApiFormat.GEMINI -> "Gemini"
}

