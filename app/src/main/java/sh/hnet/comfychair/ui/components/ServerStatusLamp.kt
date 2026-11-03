package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.ConnectionState
import sh.hnet.comfychair.storage.AppSettings

/**
 * One lamp per page, always in the same place: left of the page's own icons.
 *
 * Green is a dot and nothing else -- every header already has two or three icons and a sentence
 * would push the title. Yellow and red add one short phrase because "still reading" and "offline"
 * are the two states where doing nothing looks like a bug.
 */
private val LAMP_READY = Color(0xFF4A8C5A)
private val LAMP_LOADING = Color(0xFFCE962E)
private val LAMP_OFFLINE = Color(0xFFC14A3C)

private enum class LampState { READY, LOADING, OFFLINE, PROBLEM }

@Composable
fun ServerStatusLamp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connection by ConnectionManager.connectionState.collectAsState()
    val connecting by ConnectionManager.isConnecting.collectAsState()
    val models by ConnectionManager.modelCache.collectAsState()
    var open by remember { mutableStateOf(false) }

    val offlineMode = remember { AppSettings.isOfflineMode(context) }
    val reading = models.isLoading || models.nodeDefinitionsLoading
    val state = when {
        offlineMode -> LampState.OFFLINE
        connection is ConnectionState.Connected ->
            when {
                reading || !models.isLoaded || models.nodeDefinitionsRevision == 0L -> LampState.LOADING
                models.lastError != null || models.nodeDefinitionsError != null -> LampState.PROBLEM
                else -> LampState.READY
            }
        connecting -> LampState.LOADING
        else -> LampState.OFFLINE
    }
    val color = when (state) {
        LampState.READY -> LAMP_READY
        LampState.LOADING -> LAMP_LOADING
        LampState.OFFLINE -> LAMP_OFFLINE
        LampState.PROBLEM -> LAMP_OFFLINE
    }
    val label = when (state) {
        LampState.READY -> null
        LampState.LOADING -> if (connection is ConnectionState.Connected) "读取中" else "连接中"
        LampState.OFFLINE -> "离线"
        LampState.PROBLEM -> "读取失败"
    }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
                Spacer(Modifier.width(5.dp))
            }
            Box(
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp)) {
                Text("服务器状态", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.padding(top = 6.dp))
                lampRow("地址", ConnectionManager.hostname.ifBlank { "未设置" })
                lampRow(
                    "连接",
                    when {
                        offlineMode -> "离线模式"
                        connection is ConnectionState.Connected -> "已连接"
                        connecting -> "连接中"
                        else -> "未连接"
                    }
                )
                lampRow(
                    "节点参数",
                    when {
                        models.nodeDefinitionsLoading -> "读取中"
                        models.nodeDefinitionsError != null -> "失败"
                        models.nodeDefinitionsRevision > 0L -> "已就绪"
                        else -> "未读取"
                    }
                )
                lampRow(
                    "模型清单",
                    when {
                        models.isLoading -> "读取中"
                        models.lastError != null -> models.lastError!!
                        models.isLoaded -> listOfNotNull(
                            models.unets.size.takeIf { it > 0 }?.let { "模型 $it" },
                            models.loras.size.takeIf { it > 0 }?.let { "LoRA $it" },
                            models.clips.size.takeIf { it > 0 }?.let { "文本编码 $it" },
                            models.vaes.size.takeIf { it > 0 }?.let { "VAE $it" },
                            models.checkpoints.size.takeIf { it > 0 }?.let { "检查点 $it" }
                        ).chunked(2).joinToString("\n") { it.joinToString(" · ") }
                            .ifEmpty { "没有找到模型文件" }
                        else -> "未读取"
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    Modifier.width(260.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            open = false
                            ConnectionManager.refreshServerData()
                        },
                        enabled = !offlineMode && connection is ConnectionState.Connected
                    ) {
                        Text(if (state == LampState.PROBLEM) "重试" else "刷新")
                    }
                    TextButton(onClick = { open = false }) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun lampRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp).width(260.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 4)
    }
}
