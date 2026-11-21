package sh.hnet.comfychair.ui.screens

import android.app.LocaleManager
import android.net.Uri
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import sh.hnet.comfychair.queue.H3RelayService
import sh.hnet.comfychair.storage.AppSettings
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.LocalCachePolicy
import sh.hnet.comfychair.cache.MediaCache
import android.text.format.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ui.components.LanguageDropdown
import sh.hnet.comfychair.ui.components.LanguageOption
import sh.hnet.comfychair.ui.components.LogViewerDialog
import sh.hnet.comfychair.ui.components.SettingsGroupCard
import sh.hnet.comfychair.ui.components.SettingsNavRow
import sh.hnet.comfychair.ui.components.SettingsRowDivider
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.ui.components.SettingsSectionLabel
import sh.hnet.comfychair.ui.components.SettingsSegmented
import sh.hnet.comfychair.ui.components.SettingsSwitchRow
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.workflow.routing.RouterProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApplicationSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToLogin: () -> Unit = {}
) {
    val context = LocalContext.current
    val isLivePreviewEnabled by viewModel.isLivePreviewEnabled.collectAsState()
    var relayNotify by remember { mutableStateOf(AppSettings.isRelayNotifyEnabled(context)) }
    fun enableRelayNotify() {
        AppSettings.setRelayNotifyEnabled(context, true)
        relayNotify = true
    }
    // Android 13+ hides notifications without this grant; the foreground service still keeps the
    // process alive, so a refusal only costs the progress, not the keep-alive.
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableRelayNotify()
            H3RelayService.update(context)
        } else {
            relayNotify = false
            AppSettings.setRelayNotifyEnabled(context, false)
            Toast.makeText(
                context,
                "未授予通知权限：锁屏保活仍然有效，但看不到连拍进度",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    val isMemoryFirstCache by viewModel.isMemoryFirstCache.collectAsState()
    val isMediaCacheDisabled by viewModel.isMediaCacheDisabled.collectAsState()
    val isDebugLoggingEnabled by viewModel.isDebugLoggingEnabled.collectAsState()
    val isAutoConnectEnabled by viewModel.isAutoConnectEnabled.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val edgeRouterId by viewModel.edgeRouterId.collectAsState()
    val isPromptSpellCheckEnabled by viewModel.isPromptSpellCheckEnabled.collectAsState()
    val isPromptExpandEnabled by viewModel.isPromptExpandEnabled.collectAsState()

    // State and effects
    // Backup/restore state
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // Debug logging state
    var showLogViewer by remember { mutableStateOf(false) }

    // File picker for creating backup
    val backupSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.createBackup(context, it) }
    }

    // File picker for restoring backup
    val backupRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.startRestore(it) }
    }

    // File picker for saving debug logs
    val logSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { saveUri ->
            try {
                context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                    outputStream.write(DebugLogger.exportToString().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, R.string.msg_debug_log_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.error_debug_log_save, Toast.LENGTH_SHORT).show()
            }
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
                    pendingRestoreUri = event.uri
                    showRestoreDialog = true
                }
                is SettingsEvent.NavigateToLogin -> {
                    onNavigateToLogin()
                }
            }
        }
    }

    // Restore confirmation dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestoreDialog = false
                pendingRestoreUri = null
            },
            title = { Text(stringResource(R.string.title_backup_restore_confirm)) },
            text = { Text(stringResource(R.string.msg_backup_restore_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestoreUri?.let { viewModel.restoreBackup(context, it) }
                        showRestoreDialog = false
                        pendingRestoreUri = null
                    }
                ) {
                    Text(stringResource(R.string.button_backup_restore_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showRestoreDialog = false
                        pendingRestoreUri = null
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Language options - System default first, then alphabetical by native name
    val languageOptions = remember {
        listOf(
            LanguageOption("", R.string.language_system_default),
            LanguageOption("de", R.string.language_name_de),
            LanguageOption("en", R.string.language_name_en),
            LanguageOption("es", R.string.language_name_es),
            LanguageOption("fr", R.string.language_name_fr),
            LanguageOption("pl", R.string.language_name_pl),
            LanguageOption("zh", R.string.language_name_zh)
        )
    }

    // Get current app locale from LocaleManager
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val currentLocales = localeManager.applicationLocales
    val currentLocaleTag = if (currentLocales.isEmpty) {
        ""
    } else {
        currentLocales.get(0)?.language ?: ""
    }

    var languageDialogOpen by remember { mutableStateOf(false) }
    var cacheLimitDialogOpen by remember { mutableStateOf(false) }
    var confirmTarget by remember { mutableStateOf<ConfirmTarget?>(null) }
    var cacheLimitGb by remember { mutableStateOf(LocalCachePolicy.limitGb(context)) }
    var cacheUsedBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(cacheLimitGb) {
        cacheUsedBytes = withContext(Dispatchers.IO) { MediaCache.diskUsageBytes(context) }
    }
    val currentLanguageName = languageOptions
        .firstOrNull { it.localeTag == currentLocaleTag }
        ?.let { stringResource(it.displayNameResId) }
        ?: stringResource(R.string.language_system_default)
    val cacheLimitLabel = if (cacheLimitGb <= 0L) stringResource(R.string.cache_limit_unlimited)
    else "${cacheLimitGb}GB"

    SettingsScreenScaffold(
        title = stringResource(R.string.title_application_settings),
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout
    ) {
        SettingsSectionLabel(stringResource(R.string.title_settings_language))
        SettingsGroupCard {
            SettingsNavRow(
                label = stringResource(R.string.label_language),
                value = currentLanguageName,
                onClick = { languageDialogOpen = true }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.label_prompt_spell_check),
                description = stringResource(R.string.desc_prompt_spell_check),
                checked = isPromptSpellCheckEnabled,
                onCheckedChange = { viewModel.setPromptSpellCheckEnabled(context, it) }
            )
        }

        SettingsSectionLabel(stringResource(R.string.section_connection_generation))
        SettingsGroupCard {
            SettingsSwitchRow(
                title = stringResource(R.string.label_auto_connect),
                description = stringResource(R.string.desc_auto_connect),
                checked = isAutoConnectEnabled,
                onCheckedChange = { viewModel.setAutoConnectEnabled(context, it) }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.label_offline_mode),
                description = stringResource(
                    if (isMemoryFirstCache) R.string.msg_offline_mode_requires_disk_cache
                    else R.string.desc_offline_mode
                ),
                checked = isOfflineMode,
                enabled = !isMemoryFirstCache,
                onCheckedChange = { viewModel.setOfflineMode(context, it) }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = "连拍通知",
                description = "连拍时在通知栏显示第几段，并可暂停；锁屏时也不容易被系统回收",
                checked = relayNotify,
                onCheckedChange = { on ->
                    if (!on) {
                        relayNotify = false
                        AppSettings.setRelayNotifyEnabled(context, false)
                        H3RelayService.stop(context)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        enableRelayNotify()
                        H3RelayService.update(context)
                    }
                }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.label_live_preview),
                description = stringResource(R.string.desc_live_preview),
                checked = isLivePreviewEnabled,
                onCheckedChange = { viewModel.setLivePreviewEnabled(context, it) }
            )

            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.label_prompt_expand),
                description = stringResource(R.string.desc_prompt_expand),
                checked = isPromptExpandEnabled,
                onCheckedChange = { viewModel.setPromptExpandEnabled(context, it) }
            )
        }

        SettingsSectionLabel(stringResource(R.string.title_settings_cache))
        SettingsGroupCard {
            SettingsSwitchRow(
                title = stringResource(R.string.label_memory_first_cache),
                description = stringResource(R.string.desc_memory_first_cache),
                warning = if (isMemoryFirstCache) stringResource(R.string.desc_memory_first_cache_warning) else null,
                checked = isMemoryFirstCache,
                onCheckedChange = { viewModel.setMemoryFirstCache(context, it) }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.label_disable_media_cache),
                description = stringResource(R.string.desc_disable_media_cache),
                checked = isMediaCacheDisabled,
                enabled = isMemoryFirstCache,
                onCheckedChange = { viewModel.setMediaCacheDisabled(context, it) }
            )
            SettingsRowDivider()
            SettingsNavRow(
                label = stringResource(R.string.cache_limit_title),
                value = Formatter.formatShortFileSize(context, cacheUsedBytes) + " / " + cacheLimitLabel,
                onClick = { cacheLimitDialogOpen = true }
            )
            SettingsRowDivider()
            sh.hnet.comfychair.ui.components.SettingsButtonRow(
                label = stringResource(R.string.button_clear_cache),
                icon = Icons.Default.Delete,
                danger = true,
                onClick = { confirmTarget = ConfirmTarget.CACHE }
            )
        }

        SettingsSectionLabel(stringResource(R.string.title_settings_workflow_editor))
        SettingsGroupCard {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    stringResource(R.string.label_edge_style),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(7.dp))
                SettingsSegmented(
                    options = listOf(
                        stringResource(R.string.option_edge_router_hermite),
                        stringResource(R.string.option_edge_router_bezier)
                    ),
                    selected = if (edgeRouterId == "bezier") 1 else 0,
                    onSelect = { index ->
                        viewModel.setEdgeRouterId(context, if (index == 0) "hermite" else "bezier")
                    }
                )
            }
        }

        SettingsSectionLabel(stringResource(R.string.section_data))
        SettingsGroupCard {
            SettingsNavRow(
                label = stringResource(R.string.button_backup_create),
                value = "",
                onClick = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    backupSaveLauncher.launch("comfychair_backup_$timestamp.json")
                }
            )
            SettingsRowDivider()
            SettingsNavRow(
                label = stringResource(R.string.button_backup_restore),
                value = "",
                onClick = { backupRestoreLauncher.launch("application/json") }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.label_debug_logging_enable),
                description = stringResource(R.string.desc_debug_logging_enable),
                checked = isDebugLoggingEnabled,
                onCheckedChange = { viewModel.setDebugLoggingEnabled(context, it) }
            )
            SettingsRowDivider()
            SettingsNavRow(
                label = stringResource(R.string.button_debug_logging_view),
                value = "",
                enabled = isDebugLoggingEnabled,
                onClick = { showLogViewer = true }
            )
            SettingsRowDivider()
            SettingsNavRow(
                label = stringResource(R.string.button_reset_prompts_and_library),
                value = "",
                danger = true,
                onClick = { confirmTarget = ConfirmTarget.PROMPTS }
            )
            SettingsRowDivider()
            SettingsNavRow(
                label = stringResource(R.string.button_restore_defaults),
                value = "",
                danger = true,
                onClick = { confirmTarget = ConfirmTarget.DEFAULTS }
            )
        }
    }

    if (languageDialogOpen) {
        AlertDialog(
            onDismissRequest = { languageDialogOpen = false },
            title = { Text(stringResource(R.string.label_language)) },
            text = {
                Column {
                    languageOptions.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newLocales = if (option.localeTag.isEmpty()) {
                                        LocaleList.getEmptyLocaleList()
                                    } else {
                                        LocaleList.forLanguageTags(option.localeTag)
                                    }
                                    localeManager.applicationLocales = newLocales
                                    languageDialogOpen = false
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option.localeTag == currentLocaleTag, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(option.displayNameResId))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { languageDialogOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (cacheLimitDialogOpen) {
        AlertDialog(
            onDismissRequest = { cacheLimitDialogOpen = false },
            title = { Text(stringResource(R.string.cache_limit_title)) },
            text = {
                Column {
                    LocalCachePolicy.limitOptionsGb.forEach { gb ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LocalCachePolicy.setLimitGb(context, gb)
                                    MediaCache.trimToLimit(context)
                                    cacheLimitGb = gb
                                    cacheLimitDialogOpen = false
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = gb == cacheLimitGb, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (gb <= 0L) stringResource(R.string.cache_limit_unlimited)
                                else "${gb}GB"
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { cacheLimitDialogOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    confirmTarget?.let { target ->
        val titleRes = when (target) {
            ConfirmTarget.CACHE -> R.string.button_clear_cache
            ConfirmTarget.PROMPTS -> R.string.button_reset_prompts_and_library
            ConfirmTarget.DEFAULTS -> R.string.button_restore_defaults
        }
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text(stringResource(titleRes)) },
            text = {
                val message = when (target) {
                    ConfirmTarget.CACHE -> R.string.msg_confirm_clear_cache
                    ConfirmTarget.PROMPTS -> R.string.msg_confirm_reset_prompts
                    ConfirmTarget.DEFAULTS -> R.string.msg_confirm_irreversible
                }
                Text(stringResource(message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (target) {
                            ConfirmTarget.CACHE -> viewModel.clearCache(context)
                            ConfirmTarget.PROMPTS -> viewModel.resetPromptsAndLibrary(context)
                            ConfirmTarget.DEFAULTS -> viewModel.restoreDefaults(context)
                        }
                        confirmTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Log viewer dialog
    if (showLogViewer) {
        LogViewerDialog(
            onDismiss = { showLogViewer = false },
            onSave = {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                logSaveLauncher.launch("comfychair_log_$timestamp.txt")
            }
        )
    }
}

private enum class ConfirmTarget { CACHE, PROMPTS, DEFAULTS }
