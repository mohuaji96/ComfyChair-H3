package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.storage.OptimizeHistoryStore
import sh.hnet.comfychair.storage.OptimizeRecord
import sh.hnet.comfychair.viewmodel.PromptOptimizerViewModel

/**
 * Rewrite sheet for the composer output.
 *
 * The input field is pre-filled with what the tag library produced but stays fully
 * editable — typing a description over it is the free-text path, so there is no second
 * entry point to maintain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptOptimizeSheet(
    libraryPrompt: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    viewModel: PromptOptimizerViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.initialize(context) }

    val state by viewModel.optimize.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val currentId by viewModel.currentId.collectAsStateWithLifecycle()
    val provider = providers.firstOrNull { it.id == currentId } ?: providers.firstOrNull()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by remember { mutableStateOf(libraryPrompt) }
    var resultDraft by remember { mutableStateOf("") }
    var providerMenu by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }
    var historyRows by remember { mutableStateOf(emptyList<OptimizeRecord>()) }

    // Parsing up to a hundred records off the main thread: the sheet must open instantly.
    LaunchedEffect(Unit) {
        historyRows = withContext(Dispatchers.IO) { OptimizeHistoryStore.all(context) }
    }

    LaunchedEffect(state.result) {
        if (state.result.isNotBlank()) {
            resultDraft = state.result
            noteDraft = state.note
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "优化提示词",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { historyOpen = true }) {
                    Text("历史 ${historyRows.size}/${OptimizeHistoryStore.MAX_RECORDS}")
                }
                Box {
                    AssistChip(
                        onClick = { providerMenu = true },
                        label = {
                            Text(
                                provider?.name?.ifBlank { "未命名供应商" } ?: "未配置",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = providerMenu,
                        onDismissRequest = { providerMenu = false }
                    ) {
                        providers.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Text(item.name.ifBlank { "未命名供应商" }, maxLines = 1)
                                },
                                leadingIcon = {
                                    if (item.id == currentId) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    } else {
                                        Spacer(Modifier.width(24.dp))
                                    }
                                },
                                onClick = {
                                    viewModel.select(item.id)
                                    viewModel.clearResult()
                                    input = libraryPrompt
                                    resultDraft = ""
                                    providerMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "输入",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${input.length} 字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (input != libraryPrompt) {
                    TextButton(onClick = { input = libraryPrompt }) { Text("用词库结果") }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("词库组合的结果会填在这里，也可以直接写一段描述。") },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 220.dp)
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.runOptimize(input) },
                    enabled = !state.running && input.isNotBlank()
                ) {
                    if (state.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("优化中")
                    } else {
                        Text(if (state.hasResult) "重新优化" else "开始优化")
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    when {
                        provider == null -> "先去设置 → 提示词优化 里配一个供应商"
                        !provider.isUsable -> "供应商还缺 API URL 或模型名"
                        state.hasResult -> "${state.elapsedMs} ms · ${
                            if (state.english) "English" else "简体中文"
                        }"
                        else -> if (provider.english) "输出 English" else "输出 简体中文"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            if (state.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.error?.let { error ->
                Spacer(Modifier.height(10.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                state.errorDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "原始返回：" + detail.take(220),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.hasResult ||
                (state.error == null && !state.running && resultDraft.isNotBlank())
            ) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "优化后",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${resultDraft.length} 字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = resultDraft,
                    onValueChange = { resultDraft = it },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                )
                if (noteDraft.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已调整：" + noteDraft,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "可以直接改，比如删掉模型自己加的人或道具。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.runOptimize(input, force = true) },
                        enabled = !state.running,
                        modifier = Modifier.weight(1f)
                    ) { Text("再来一次") }
                    Button(
                        onClick = { onCopy(resultDraft) },
                        enabled = resultDraft.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("复制") }
                }
            }
        }
    }

    if (historyOpen) {
        OptimizeHistorySheet(
            rows = historyRows,
            onDismiss = { historyOpen = false },
            onPick = { record ->
                input = record.input
                resultDraft = record.output
                noteDraft = record.note
                historyOpen = false
            },
            onToggleFavorite = { id ->
                historyRows = OptimizeHistoryStore.toggleFavorite(context, id)
            },
            onDelete = { id ->
                historyRows = OptimizeHistoryStore.remove(context, id)
            },
            onClear = {
                OptimizeHistoryStore.clear(context)
                historyRows = emptyList()
            }
        )
    }
}
