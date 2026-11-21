package sh.hnet.comfychair.ui.components

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.ServerWorkflow
import sh.hnet.comfychair.connection.ServerWorkflows

/**
 * Bottom sheet displaying workflows stored on the connected ComfyUI server's user data folder,
 * allowing users to inspect and import them directly into the app with a single tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerImportSheet(
    onDismiss: () -> Unit,
    onImportSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var loading by remember { mutableStateOf(true) }
    var downloadingPath by remember { mutableStateOf<String?>(null) }
    var workflows by remember { mutableStateOf<List<ServerWorkflow>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshList() {
        val client = ConnectionManager.clientOrNull
        if (client == null) {
            loading = false
            errorMessage = "未连接到 ComfyUI 服务器"
            return
        }
        loading = true
        errorMessage = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { ServerWorkflows.list(client) }
            loading = false
            when (res) {
                is ServerWorkflows.ListResult.Ok -> workflows = res.workflows
                ServerWorkflows.ListResult.MultiUser -> errorMessage = "该服务器启用了多用户模式，暂不支持直接枚举"
                is ServerWorkflows.ListResult.Failed -> errorMessage = "无法读取服务器工作流：${res.details ?: "未知错误"}"
            }
        }
    }

    LaunchedEffect(Unit) { refreshList() }

    val filtered = remember(workflows, searchQuery) {
        if (searchQuery.isBlank()) workflows
        else workflows.filter { it.name.contains(searchQuery, ignoreCase = true) || it.folder.contains(searchQuery, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "从服务器导入工作流",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = { refreshList() }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索工作流名字...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            when {
                loading -> {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                errorMessage != null -> {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                filtered.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "没有找到匹配的工作流" else "服务器 userdata/workflows/ 目录下暂无工作流",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.path }) { wf ->
                            val isDownloading = downloadingPath == wf.path
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isDownloading) {
                                        val client = ConnectionManager.clientOrNull ?: return@clickable
                                        downloadingPath = wf.path
                                        scope.launch {
                                            val dl = withContext(Dispatchers.IO) { ServerWorkflows.download(client, wf) }
                                            downloadingPath = null
                                            when (dl) {
                                                is ServerWorkflows.DownloadResult.Ok -> {
                                                    val imported = WorkflowManager.addUserWorkflow("${wf.name}.json", wf.name, "", dl.json).getOrNull()
                                                    if (imported != null) {
                                                        Toast.makeText(context, "已成功导入：${wf.name}", Toast.LENGTH_SHORT).show()
                                                        onImportSuccess(imported.id)
                                                        onDismiss()
                                                    } else {
                                                        Toast.makeText(context, "导入工作流解析失败", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                ServerWorkflows.DownloadResult.TooLarge -> {
                                                    Toast.makeText(context, "工作流文件过大", Toast.LENGTH_SHORT).show()
                                                }
                                                is ServerWorkflows.DownloadResult.NotWorkflow -> {
                                                    Toast.makeText(context, "文件非合法 ComfyUI 工作流", Toast.LENGTH_SHORT).show()
                                                }
                                                is ServerWorkflows.DownloadResult.Failed -> {
                                                    Toast.makeText(context, "下载失败：${dl.details}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = wf.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (wf.folder.isNotBlank()) {
                                            Text(
                                                text = "目录: ${wf.folder}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = "大小: ${Formatter.formatShortFileSize(context, wf.size)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (isDownloading) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.SaveAlt,
                                            contentDescription = "导入",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
