package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.storage.LiteGraphSourceStore
import sh.hnet.comfychair.storage.QuickParameterPins
import sh.hnet.comfychair.workflow.QuickParameterCatalog

/** Quick LiteGraph editor; safe changes are validated again during prompt preparation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickParameterSheet(workflowId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LiteGraphSourceStore(context) }
    val pinsStore = remember(context) { QuickParameterPins(context) }
    var entries by remember(workflowId) {
        mutableStateOf(runCatching { store.readWorking(workflowId)?.let(QuickParameterCatalog::discover) ?: emptyList() }
            .getOrDefault(emptyList()))
    }
    var definitionRevision by remember { mutableStateOf(0) }
    val sharedModels by sh.hnet.comfychair.connection.ConnectionManager.modelCache.collectAsState()
    val activeClient = sh.hnet.comfychair.connection.ConnectionManager.clientOrNull
    androidx.compose.runtime.LaunchedEffect(workflowId, activeClient) {
        activeClient?.fetchFullObjectInfo { info ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (info != null && activeClient === sh.hnet.comfychair.connection.ConnectionManager.clientOrNull) {
                    sh.hnet.comfychair.connection.ConnectionManager.nodeTypeRegistry.parseObjectInfo(info)
                    definitionRevision++
                }
            }
        }
    }
    var pinned by remember(workflowId) { mutableStateOf(pinsStore.read(workflowId)) }
    var showAll by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val visible = entries.filter { showAll || !it.bypassed || it.key in pinned }
        .sortedWith(compareByDescending<QuickParameterCatalog.Entry> { it.key in pinned }
            .thenBy { it.group.ordinal }.thenBy { it.nodeId })

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("快捷参数（工作副本）", style = MaterialTheme.typography.titleLarge)
            Text(
                "更改保存到独立工作副本。同名字段以快捷参数为准，会覆盖生成页和节点临时编辑的值。生成前会同步到执行 JSON；如果节点定义不完整或映射不可靠，将阻止提交。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "仅显示常用/已固定" else "显示全部及备用") }
                TextButton(onClick = {
                    entries = runCatching { store.readWorking(workflowId)?.let(QuickParameterCatalog::discover) ?: emptyList() }
                        .getOrDefault(emptyList())
                    message = "已重新读取工作副本"
                }) { Text("刷新") }
            }
            if (message != null) Text(message!!, color = MaterialTheme.colorScheme.primary)
            if (visible.isEmpty()) {
                Text("没有可显示的参数；可点击‘显示全部及备用’或前往专业节点编辑器。")
            }
            LazyColumn(modifier = Modifier.heightIn(max = 540.dp)) {
                items(visible, key = { it.key }) { entry ->
                    QuickParameterRow(
                        entry = entry,
                        options = (definitionRevision + sharedModels.nodeDefinitionsRevision).let {
                            sh.hnet.comfychair.connection.ConnectionManager.nodeTypeRegistry
                                .getNodeDefinition(entry.nodeType)?.inputs?.firstOrNull { it.name == entry.field }?.options ?: emptyList()
                        },
                        pinned = entry.key in pinned,
                        onPinChange = { checked ->
                            runCatching { pinsStore.setPinned(workflowId, entry.key, checked) }
                                .onSuccess { pinned = pinsStore.read(workflowId); message = null }
                                .onFailure { message = "固定失败：${it.message}" }
                        },
                        onSave = { value ->
                            runCatching {
                                store.changeWidget(workflowId, entry.nodeId, entry.field, value)
                                entries = QuickParameterCatalog.discover(store.readWorking(workflowId)!!)
                            }.onSuccess { message = "已保存：${entry.label}。生成前会重新校验；未验证服务器执行。" }
                                .onFailure { message = "无法安全修改：${it.message}" }
                        }
                    )
                    HorizontalDivider()
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

@Composable
private fun QuickParameterRow(
    entry: QuickParameterCatalog.Entry,
    options: List<String>,
    pinned: Boolean,
    onPinChange: (Boolean) -> Unit,
    onSave: (Any) -> Unit
) {
    var draft by remember(entry.key, entry.value) { mutableStateOf(entry.value.toString()) }
    var choosing by remember(entry.key) { mutableStateOf(false) }
    val numeric = entry.value is Number
    val boolean = entry.value is Boolean
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(entry.label, style = MaterialTheme.typography.titleSmall)
        Text("节点 #${entry.nodeId} · ${entry.nodeType}${if (entry.bypassed) " · 已绕过" else ""}",
            style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { onPinChange(!pinned) }) { Text(if (pinned) "取消固定" else "固定到常用") }
        }
        if (!entry.editable) {
            Text(entry.reason ?: "此字段不可安全编辑", color = MaterialTheme.colorScheme.error)
        } else if (boolean) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前：${entry.value}")
                Switch(checked = draft.toBoolean(), onCheckedChange = { draft = it.toString() })
                Button(enabled = draft != entry.value.toString(), onClick = { onSave(draft.toBoolean()) }) { Text("保存") }
            }
        } else {
            if (options.isNotEmpty()) {
                TextButton(onClick = { choosing = true }) { Text("选择服务器选项（${options.size}）") }
                if (choosing) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { choosing = false },
                    title = { Text(entry.label) },
                    text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(options) { option -> TextButton(onClick = { draft = option; choosing = false }) { Text(option) } }
                    } },
                    confirmButton = { TextButton(onClick = { choosing = false }) { Text("取消") } }
                )
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                label = { Text(if (numeric) "数字" else "内容") },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (entry.group == QuickParameterCatalog.Group.PROMPT) 3 else 1,
                maxLines = if (entry.group == QuickParameterCatalog.Group.PROMPT) 6 else 2
            )
            val typed: Any? = when (entry.value) {
                is Int -> draft.toIntOrNull()
                is Long -> draft.toLongOrNull()
                is Float -> draft.toFloatOrNull()?.takeIf { it.isFinite() }
                is Double -> draft.toDoubleOrNull()?.takeIf { it.isFinite() }
                else -> draft
            }
            Button(
                enabled = typed != null && draft != entry.value.toString(),
                onClick = { typed?.let(onSave) },
                modifier = Modifier.padding(top = 4.dp)
            ) { Text("保存到工作副本") }
            if (entry.group == QuickParameterCatalog.Group.MEDIA) {
                Text("这里只编辑服务器文件名，不提供素材上传。请使用现有素材上传功能。",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
