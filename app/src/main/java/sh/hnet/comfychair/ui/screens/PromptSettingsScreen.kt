package sh.hnet.comfychair.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
        title = "提示词优化",
        onBack = onNavigateToGeneration
    ) {
        Spacer(Modifier.height(4.dp))

        if (current == null) {
            Text(
                "还没有供应商。先加一个，优化才能用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
            )
            SettingsPrimaryButton(
                text = "添加供应商",
                onClick = onOpenProviders,
                modifier = Modifier.fillMaxWidth()
            )
            return@SettingsDetailScaffold
        }

        SettingsSectionLabel("当前使用")
        SettingsGroupCard {
            SettingsNavRow(
                label = "供应商",
                value = current.name.ifBlank { "未命名供应商" },
                sub = formatName(current.format) + " · " + current.model.ifBlank { "未填模型名" },
                onClick = onOpenProviders
            )
            SettingsRowDivider()
            SettingsNavRow(
                label = "模板",
                value = activeTemplate.name,
                sub = (if (activeTemplate.builtIn) "内置" else "自定义") + " · " +
                    activeTemplate.body.lineSequence().count() + " 行",
                onClick = onOpenTemplates
            )
        }

        SettingsSectionLabel("本次优化")
        SettingsGroupCard {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "输出语言",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SettingsSegmented(
                    options = listOf("English", "中文"),
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

        SettingsFootnote("token、超时、接口路径在「API 供应商」里配一次即可。优化入口在「词库组合」窗口底部。")
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
                "温度",
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
                "0 忠于原标签",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
            Text(
                "1 自由改写",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
    }
}

private fun formatName(format: ApiFormat): String = when (format) {
    ApiFormat.OPENAI_CHAT -> "OpenAI 兼容"
    ApiFormat.OPENAI_RESPONSES -> "OpenAI Responses"
    ApiFormat.ANTHROPIC -> "Anthropic"
    ApiFormat.GEMINI -> "Gemini"
}

