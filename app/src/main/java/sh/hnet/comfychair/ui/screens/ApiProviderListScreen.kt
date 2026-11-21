package sh.hnet.comfychair.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
        title = "API 供应商",
        onBack = onBack,
        status = {
            TextButton(onClick = {
                viewModel.addProvider()?.let { onEdit(it.id) }
            }) { Text("＋ 添加") }
        },
        bottomBar = {
            BottomActionBar {
                SettingsPrimaryButton(
                    text = "新建供应商",
                    onClick = { viewModel.addProvider()?.let { onEdit(it.id) } },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        SettingsSectionLabel("全部", note = "${providers.size} 个")

        if (providers.isEmpty()) {
            Text(
                "还没有供应商。加一个之后，把接口地址、Key 和模型名填上就能用。",
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
                    title = provider.name.ifBlank { "未命名供应商" },
                    meta = listOf(
                        provider.model.ifBlank { "未填模型名" },
                        formatName(provider.format),
                        if (keys[provider.id] == true) "已配置 Key" else "未配置 Key"
                    ).joinToString(" · "),
                    active = provider.id == currentId,
                    onClick = { onEdit(provider.id) },
                    trailing = {
                        Box {
                            IconButton(onClick = { menuFor = provider.id }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "更多",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = menuFor == provider.id,
                                onDismissRequest = { menuFor = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("复制一份") },
                                    onClick = {
                                        menuFor = null
                                        viewModel.duplicate(provider.id)?.let { onEdit(it.id) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("上移") },
                                    enabled = index > 0,
                                    onClick = {
                                        menuFor = null
                                        viewModel.move(provider.id, -1)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("下移") },
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

        SettingsSectionLabel("说明")
        SettingsGroupCard {
            Text(
                "每个供应商的地址、Key、模型各自保存，切换不会丢。删掉正在用的一个时，会自动换到剩下的第一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun formatName(format: ApiFormat): String = when (format) {
    ApiFormat.OPENAI_CHAT -> "OpenAI 兼容"
    ApiFormat.OPENAI_RESPONSES -> "OpenAI Responses"
    ApiFormat.ANTHROPIC -> "Anthropic"
    ApiFormat.GEMINI -> "Gemini"
}

