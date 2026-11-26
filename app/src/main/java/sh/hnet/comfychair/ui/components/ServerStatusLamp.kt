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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.ConnectionState
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.theme.LocalSemanticColors

/**
 * One lamp per page, always in the same place: left of the page's own icons.
 *
 * Green is a dot and nothing else -- every header already has two or three icons and a sentence
 * would push the title. Yellow and red add one short phrase because "still reading" and "offline"
 * are the two states where doing nothing looks like a bug.
 */
// 灯色改由跨主题语义 Token 提供（任务书 §4）：成功 / 警告 / 错误，不随品牌主色漂移。

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
    val semantic = LocalSemanticColors.current
    val color = when (state) {
        LampState.READY -> semantic.success
        LampState.LOADING -> semantic.warning
        LampState.OFFLINE -> semantic.error
        LampState.PROBLEM -> semantic.error
    }
    val label = when (state) {
        LampState.READY -> null
        LampState.LOADING -> if (connection is ConnectionState.Connected) stringResource(R.string.h3_lamp_reading) else stringResource(R.string.h3_lamp_connecting)
        LampState.OFFLINE -> stringResource(R.string.h3_lamp_offline)
        LampState.PROBLEM -> stringResource(R.string.h3_lamp_problem)
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
                Text(stringResource(R.string.h3_server_status), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.padding(top = 6.dp))
                lampRow(stringResource(R.string.h3_address), ConnectionManager.hostname.ifBlank { stringResource(R.string.h3_not_set) })
                lampRow(
                    stringResource(R.string.h3_connection),
                    when {
                        offlineMode -> stringResource(R.string.h3_offline_mode)
                        connection is ConnectionState.Connected -> stringResource(R.string.h3_connected)
                        connecting -> stringResource(R.string.h3_lamp_connecting)
                        else -> stringResource(R.string.h3_not_connected)
                    }
                )
                lampRow(
                    stringResource(R.string.h3_node_params),
                    when {
                        models.nodeDefinitionsLoading -> stringResource(R.string.h3_lamp_reading)
                        models.nodeDefinitionsError != null -> stringResource(R.string.h3_failed)
                        models.nodeDefinitionsRevision > 0L -> stringResource(R.string.h3_ready)
                        else -> stringResource(R.string.h3_not_read)
                    }
                )
                lampRow(
                    stringResource(R.string.h3_model_inventory),
                    when {
                        models.isLoading -> stringResource(R.string.h3_lamp_reading)
                        models.lastError != null -> models.lastError!!
                        models.isLoaded -> listOfNotNull(
                            models.unets.size.takeIf { it > 0 }?.let { stringResource(R.string.h3_model_count, it) },
                            models.loras.size.takeIf { it > 0 }?.let { "LoRA $it" },
                            models.clips.size.takeIf { it > 0 }?.let { stringResource(R.string.h3_text_encoder_count, it) },
                            models.vaes.size.takeIf { it > 0 }?.let { "VAE $it" },
                            models.checkpoints.size.takeIf { it > 0 }?.let { stringResource(R.string.h3_checkpoint_count, it) }
                        ).chunked(2).joinToString("\n") { it.joinToString(" · ") }
                            .ifEmpty { stringResource(R.string.h3_no_models) }
                        else -> stringResource(R.string.h3_not_read)
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
                        Text(if (state == LampState.PROBLEM) stringResource(R.string.h3_retry) else stringResource(R.string.h3_refresh))
                    }
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.h3_close)) }
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
