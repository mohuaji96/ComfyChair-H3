package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.SettingsFootnote
import sh.hnet.comfychair.ui.components.SettingsGroupCard
import sh.hnet.comfychair.ui.components.SettingsNavRow
import sh.hnet.comfychair.ui.components.SettingsProgressRow
import sh.hnet.comfychair.ui.components.SettingsRowDivider
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.ui.components.SettingsSectionLabel
import sh.hnet.comfychair.ui.components.SettingsValueRow
import sh.hnet.comfychair.viewmodel.GpuInfo
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import java.util.Locale

@Composable
fun ServerSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.serverSettingsState.collectAsState()

    // State and effects
    // Load system stats on first composition and start auto-refresh
    LaunchedEffect(Unit) {
        viewModel.loadSystemStats()
    }

    // Start/stop auto-refresh when screen is shown/hidden
    DisposableEffect(Unit) {
        viewModel.startResourceAutoRefresh()
        onDispose {
            viewModel.stopResourceAutoRefresh()
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is SettingsEvent.RefreshNeeded -> {
                    // Handled by SettingsContainerActivity
                }
                is SettingsEvent.ShowRestoreDialog -> {
                    // Handled by ApplicationSettingsScreen
                }
                is SettingsEvent.NavigateToLogin -> {
                    // Handled by SettingsContainerActivity
                }
            }
        }
    }

    // UI composition
    SettingsScreenScaffold(
        title = stringResource(R.string.title_server_settings),
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout
    ) {
        val stats = uiState.systemStats

        SettingsSectionLabel(stringResource(R.string.title_server_info))
        SettingsGroupCard {
            SettingsValueRow(
                stringResource(R.string.label_hostname),
                uiState.hostname.ifBlank { "—" }
            )
            SettingsRowDivider()
            SettingsValueRow(stringResource(R.string.label_port), uiState.port.toString())
            if (stats != null) {
                SettingsRowDivider()
                SettingsValueRow(stringResource(R.string.label_server_info_os), stats.os)
                SettingsRowDivider()
                SettingsValueRow(stringResource(R.string.label_server_info_comfyui), stats.comfyuiVersion)
                SettingsRowDivider()
                SettingsValueRow(stringResource(R.string.label_server_info_python), stats.pythonVersion)
                SettingsRowDivider()
                SettingsValueRow(stringResource(R.string.label_server_info_pytorch), stats.pytorchVersion)
            }
        }
        if (stats == null && uiState.isLoadingStats) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在读取服务器信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (stats != null && stats.ramTotalGB > 0) {
            val ramUsed = stats.ramTotalGB - stats.ramFreeGB
            SettingsSectionLabel(stringResource(R.string.title_ram_usage))
            SettingsGroupCard {
                SettingsProgressRow(
                    title = stringResource(R.string.title_ram_usage),
                    value = String.format(Locale.US, "%.1f / %.1f GB", ramUsed, stats.ramTotalGB),
                    fraction = (ramUsed / stats.ramTotalGB).toFloat()
                )
            }
        }

        if (stats != null && stats.gpus.isNotEmpty()) {
            SettingsSectionLabel(stringResource(R.string.title_gpu_usage))
            SettingsGroupCard {
                stats.gpus.forEachIndexed { index, gpu ->
                    if (index > 0) SettingsRowDivider()
                    val title = if (stats.gpus.size > 1) {
                        "${stringResource(R.string.title_gpu_usage)} ${index + 1}"
                    } else {
                        stringResource(R.string.title_gpu_usage)
                    }
                    if (gpu.vramTotalGB > 0) {
                        val vramUsed = gpu.vramTotalGB - gpu.vramFreeGB
                        SettingsProgressRow(
                            title = title,
                            value = String.format(
                                Locale.US, "%.1f / %.1f GB", vramUsed, gpu.vramTotalGB
                            ),
                            fraction = (vramUsed / gpu.vramTotalGB).toFloat(),
                            note = gpu.name
                        )
                    } else {
                        SettingsValueRow(title, gpu.name)
                    }
                }

                SettingsRowDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var freeing by remember { mutableStateOf(false) }
                    androidx.compose.material3.FilledTonalButton(
                        onClick = {
                            val client = sh.hnet.comfychair.connection.ConnectionManager.clientOrNull ?: return@FilledTonalButton
                            freeing = true
                            client.freeMemory(unloadModelsOnly = false) { ok ->
                                freeing = false
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    Toast.makeText(context, if (ok) "内存已释放" else "释放失败", Toast.LENGTH_SHORT).show()
                                    viewModel.refreshServerData()
                                }
                            }
                        },
                        enabled = !freeing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("释放内存")
                    }

                    androidx.compose.material3.FilledTonalButton(
                        onClick = {
                            val client = sh.hnet.comfychair.connection.ConnectionManager.clientOrNull ?: return@FilledTonalButton
                            freeing = true
                            client.freeMemory(unloadModelsOnly = true) { ok ->
                                freeing = false
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    Toast.makeText(context, if (ok) "模型已卸载" else "卸载失败", Toast.LENGTH_SHORT).show()
                                    viewModel.refreshServerData()
                                }
                            }
                        },
                        enabled = !freeing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("卸载模型")
                    }
                }
            }
        }

        val refreshState by sh.hnet.comfychair.connection.ConnectionManager.modelCache.collectAsState()

        SettingsSectionLabel(stringResource(R.string.title_server_management))
        SettingsGroupCard {
            SettingsNavRow(
                label = stringResource(R.string.button_refresh_models),
                value = "",
                enabled = !uiState.isRefreshingModels,
                onClick = { viewModel.refreshServerData() }
            )
            SettingsRowDivider()
            sh.hnet.comfychair.ui.components.SettingsButtonRow(
                label = stringResource(R.string.button_clear_history),
                icon = Icons.Default.Delete,
                danger = true,
                enabled = !uiState.isClearingHistory,
                onClick = { viewModel.clearHistory() }
            )
        }

        val statusLine = listOf(refreshState.fileRefreshStatus, refreshState.nodeRefreshStatus)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (statusLine.isNotBlank()) SettingsFootnote(statusLine)
        refreshState.nodeDefinitionsError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}
