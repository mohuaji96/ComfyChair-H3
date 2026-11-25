package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.ConnectionState
import sh.hnet.comfychair.queue.JobRegistry

/**
 * The queue pill's state only. The drawer itself lives in the generation package
 * (`ui.components.generation.QueueSheet`), which is the one all five tabs show; the copy that used to
 * sit in this file had not been reachable since the generation package took over.
 */
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
                // Always the same capsule, zeros included. It is the only way to open the queue by
                // hand, and a button that changes shape once the queue empties reads as another control.
                else -> {
                    val dot = when {
                        ownCount > 0 -> MaterialTheme.colorScheme.primary
                        otherCount > 0 -> Color(0xFFE28822)
                        // Nothing running, nothing waiting: the dot is only there to keep the row steady
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                    Box(modifier = Modifier.size(6.dp).background(dot, CircleShape))
                    CountGlyph(
                        Icons.Default.Person, ownCount,
                        if (ownCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CountGlyph(Icons.Default.Group, otherCount, MaterialTheme.colorScheme.onSurfaceVariant)
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
