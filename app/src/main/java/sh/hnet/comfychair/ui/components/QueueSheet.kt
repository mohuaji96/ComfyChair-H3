package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.ConnectionState
import sh.hnet.comfychair.queue.JobRegistry

object QueueSheetController {
    var visible by mutableStateOf(false)
}

/**
 * Top pill indicator showing live queue counts: [• 👤 ownCount  👥 totalCount].
 * Tapping it pops up the global queue management drawer.
 */
@Composable
fun QueueIndicatorPill(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { QueueSheetController.visible = true }
) {
    val queueState by JobRegistry.queueState.collectAsState()
    val connection by ConnectionManager.connectionState.collectAsState()
    val connecting by ConnectionManager.isConnecting.collectAsState()

    val ownCount = queueState.ownActiveJobCount
    // Others counts jobs of other clients only; the total includes ours, so it never matched the glyph
    val otherCount = (queueState.totalQueueSize - queueState.ownActiveJobCount).coerceAtLeast(0)
    val offline = connection !is ConnectionState.Connected

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.height(30.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            when {
                offline -> {
                    Icon(
                        Icons.Default.CloudOff, contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("离线", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                connecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("连接中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The chip stays put with an empty queue: it is the only way to open the queue by hand
                ownCount == 0 && otherCount == 0 -> Icon(
                    Icons.Default.Queue, contentDescription = "队列",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (ownCount > 0) MaterialTheme.colorScheme.primary else Color(0xFFE28822), CircleShape)
                    )
                    if (ownCount > 0) CountGlyph(Icons.Default.Person, ownCount, MaterialTheme.colorScheme.onSurface)
                    if (otherCount > 0) CountGlyph(Icons.Default.Group, otherCount, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CountGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, color: Color) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color
    )
}

@Composable
private fun QueueSectionLabel(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/**
 * One queued job: whose it is, its prompt (the part that says what the job actually is), and —
 * while running — a bar. The cancel action is only offered for our own jobs.
 */
@Composable
private fun QueueJobRow(
    prompt: String?,
    owner: androidx.compose.ui.graphics.vector.ImageVector,
    running: Boolean,
    onCancel: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (onCancel != null) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                owner, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = prompt ?: "（服务器未提供该任务的提示词）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (prompt != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (running) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "取消", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Drawer showing all running and pending jobs in the queue, allowing users to
 * cancel own jobs, prioritize them to front, or clear the entire queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    onDismiss: () -> Unit = { QueueSheetController.visible = false }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queueState by JobRegistry.queueState.collectAsState()

    val client = ConnectionManager.clientOrNull
    val ownJobs = queueState.ownJobs.values.toList()
    val totalCount = queueState.totalQueueSize
    val otherCount = (totalCount - queueState.ownActiveJobCount).coerceAtLeast(0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "队列管理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                // 1. Running: ours first (cancel), then the other clients' (read-only, theirs to stop)
                if (queueState.isOwnJobExecuting) {
                    item {
                        QueueSectionLabel("正在运行的任务（本机）", MaterialTheme.colorScheme.primary)
                        QueueJobRow(
                            prompt = queueState.promptOf(queueState.executingPromptId),
                            owner = Icons.Default.Person,
                            running = true,
                            onCancel = { client?.interruptExecution { } }
                        )
                    }
                } else if (queueState.isExecuting) {
                    item {
                        QueueSectionLabel("正在运行的任务（其他客户端）", MaterialTheme.colorScheme.onSurfaceVariant)
                        QueueJobRow(
                            prompt = queueState.promptOf(queueState.executingPromptId),
                            owner = Icons.Default.Group,
                            running = true,
                            onCancel = null
                        )
                    }
                }

                // 2. Our waiting jobs
                val waitingOwn = ownJobs.filter { it.promptId != queueState.executingPromptId }
                if (waitingOwn.isNotEmpty()) {
                    item { QueueSectionLabel("等待中的任务（本机 ${waitingOwn.size}）", MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(waitingOwn, key = { it.promptId }) { job ->
                        QueueJobRow(
                            prompt = queueState.promptOf(job.promptId),
                            owner = Icons.Default.Person,
                            running = false,
                            onCancel = { client?.deleteQueueItems(listOf(job.promptId)) { } }
                        )
                    }
                }

                // 3. Other clients' waiting jobs: only how many. Their contents are theirs, and a
                //    list of prompts nobody can act on would just be noise.
                if (otherCount > 0) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "另有 $otherCount 个其他客户端的任务排队中",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (totalCount <= 0 && ownJobs.isEmpty() && !queueState.isExecuting) {
                    item {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            Text("队列为空", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Clear queue button
            OutlinedButton(
                onClick = {
                    client?.clearQueue { }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.ClearAll, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("清空全局队列", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
