package sh.hnet.comfychair.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.TagLibraryStore
import sh.hnet.comfychair.ui.components.SettingsGroupCard
import sh.hnet.comfychair.ui.components.SettingsNavRow
import sh.hnet.comfychair.ui.components.SettingsRowDivider
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.ui.components.SettingsSectionLabel
import sh.hnet.comfychair.ui.components.SettingsSwitchRow
import sh.hnet.comfychair.ui.components.SettingsValueRow
import sh.hnet.comfychair.update.UpdateCenter
import sh.hnet.comfychair.update.UpdateInfo
import sh.hnet.comfychair.update.UpdateState

@Composable
fun AboutSettingsScreen(
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    // Reads the already-parsed library singleton; no file work on this page.
    val libraryVersion = remember {
        runCatching { TagLibraryStore.get(context).library.value.version }.getOrDefault("")
    }

    fun openLink(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    val updateState by UpdateCenter.state.collectAsState()
    var autoCheck by remember { mutableStateOf(UpdateCenter.isEnabled(context)) }
    var updateSheet by remember { mutableStateOf<UpdateInfo?>(null) }
    // 缓存里已有上次的结果就先摆出来，进这个页面不用等网络。
    LaunchedEffect(Unit) { UpdateCenter.restore(context) }

    val statusText = when (val current = updateState) {
        is UpdateState.Checking -> stringResource(R.string.value_update_checking)
        is UpdateState.Available -> stringResource(R.string.value_update_available, current.info.versionName)
        is UpdateState.UpToDate -> stringResource(R.string.value_update_uptodate)
        is UpdateState.Failed -> stringResource(R.string.value_update_failed)
        UpdateState.Idle -> stringResource(R.string.value_update_idle)
    }

    SettingsScreenScaffold(
        title = stringResource(R.string.title_about_settings),
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_comfychair_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(76.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.label_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsSectionLabel(stringResource(R.string.section_version))
        SettingsGroupCard {
            SettingsValueRow(
                stringResource(R.string.label_version_number),
                BuildConfig.VERSION_NAME
            )
            if (libraryVersion.isNotBlank()) {
                SettingsRowDivider()
                SettingsValueRow(stringResource(R.string.label_library_version), libraryVersion)
            }
            SettingsRowDivider()
            SettingsNavRow(
                label = stringResource(R.string.label_check_update),
                value = statusText,
                enabled = updateState !is UpdateState.Checking,
                onClick = {
                    val current = updateState
                    if (current is UpdateState.Available) updateSheet = current.info
                    else UpdateCenter.check(context, force = true)
                }
            )
            SettingsRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.label_update_auto),
                description = stringResource(R.string.desc_update_auto),
                checked = autoCheck,
                onCheckedChange = {
                    autoCheck = it
                    UpdateCenter.setEnabled(context, it)
                    if (it) UpdateCenter.check(context)
                }
            )
        }

        SettingsSectionLabel(stringResource(R.string.title_about_license))
        SettingsGroupCard {
            SettingsValueRow(
                stringResource(R.string.title_about_license),
                "GPL-3.0",
                sub = stringResource(R.string.label_about_license_name)
            )
        }

        SettingsSectionLabel(stringResource(R.string.section_links))
        SettingsGroupCard {
            SettingsNavRow(
                label = stringResource(R.string.title_about_source),
                value = "ComfyChair-H3",
                sub = "github.com/mohuaji96/ComfyChair-H3",
                onClick = { openLink("https://github.com/mohuaji96/ComfyChair-H3") }
            )
            SettingsRowDivider()
            SettingsNavRow(
                label = stringResource(R.string.title_about_upstream),
                value = "comfychair",
                sub = "github.com/legal-hkr/comfychair",
                onClick = { openLink("https://github.com/legal-hkr/comfychair") }
            )
        }
    }

    updateSheet?.let { info ->
        UpdateAvailableSheet(
            info = info,
            currentVersion = BuildConfig.VERSION_NAME,
            onOpen = {
                openLink(
                    info.pageUrl.ifBlank {
                        "https://github.com/mohuaji96/ComfyChair-H3/releases/latest"
                    }
                )
                updateSheet = null
            },
            onSkip = {
                UpdateCenter.skip(context, info.tag)
                updateSheet = null
            },
            onDismiss = { updateSheet = null }
        )
    }
}

/**
 * 新版本提示。只给「去看看」和「跳过这一版」，不在这里下载安装：
 * 安装包要从浏览器下、由系统安装器接管，App 不碰文件也不碰安装权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateAvailableSheet(
    info: UpdateInfo,
    currentVersion: String,
    onOpen: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp)
        ) {
            Text(
                stringResource(R.string.title_update_available, info.versionName),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.label_update_current, currentVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val notes = notesExcerpt(info.notes)
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    notes,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.desc_update_browser),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.action_skip_version))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onOpen) {
                    Text(stringResource(R.string.action_open_release))
                }
            }
        }
    }
}

/** Release notes are Markdown; the sheet only needs the first screenful of plain lines. */
private fun notesExcerpt(raw: String, maxChars: Int = 700): String {
    val lines = raw.lineSequence()
        .map { it.trim().trimStart('#').trim() }
        .filter { it.isNotEmpty() }
        .take(14)
        .toList()
    val text = lines.joinToString("\n")
    return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
}
