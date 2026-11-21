package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.WebSocketState
import sh.hnet.comfychair.workflow.ProgressStage

/**
 * The bar under the title while this screen's job is running.
 *
 * Two halves on one line: what the server is doing on the left, how long it has been doing it on
 * the right. The right edge lines up with every other value on the page (node count, pixel count,
 * steps), so the eye finds the clock without reading.
 *
 * Only sampling has real numbers behind it. Every other stage is a wait with no progress to report,
 * so the track goes indeterminate instead of faking a fill.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenerationProgressBar(
    stage: ProgressStage,
    progress: Int,
    maxProgress: Int,
    startedAt: Long = 0L,
    modifier: Modifier = Modifier
) {
    val elapsed = rememberElapsedLabel(startedAt)
    val steppable = stage == ProgressStage.SAMPLE && maxProgress > 0
    // Hiccups last about a second before the silent reconnect lands, so an immediate label would
    // blink constantly. Only admit it once it is clearly not instant.
    val socketState by ConnectionManager.webSocketState.collectAsState()
    val dropped = socketState !is WebSocketState.Connected
    var showDropped by remember { mutableStateOf(false) }
    LaunchedEffect(dropped) {
        if (dropped) {
            delay(1_500)
            showDropped = true
        } else {
            showDropped = false
        }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (steppable) "${stage.label} · 第 $progress/$maxProgress 步" else stage.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Takes the slack and gives it back first: with large system fonts the step counter
                // shortens, and the clock stays whole on the right.
                modifier = Modifier.weight(1f)
            )
            if (elapsed != null) {
                Text(
                    // A frozen bar reads as a hang. Saying "重连中" tells the truth: the server is
                    // fine, the phone is rebuilding the link.
                    text = if (showDropped) "已用时 $elapsed · 重连中" else "已用时 $elapsed",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (showDropped) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (steppable) {
            LinearWavyProgressIndicator(
                progress = { (progress.toFloat() / maxProgress).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(5.dp))
    }
}

/**
 * Ticks once a second while composed. Returns null when the start time is unknown (a job restored
 * from disk has none), and the clock is then left out rather than showing a wrong zero.
 */
@Composable
private fun rememberElapsedLabel(startedAt: Long): String? {
    if (startedAt <= 0L) return null
    var now by remember(startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return formatElapsed((now - startedAt).coerceAtLeast(0L))
}

/**
 * `1:23` for anything under an hour, `1:02:33` past it — long videos on a slow card really do
 * cross the hour, and `62:33` reads wrong.
 */
internal fun formatElapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
