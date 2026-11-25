@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.TagLibraryStore
import sh.hnet.comfychair.ui.components.WhatsNew
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsLinkRow
import sh.hnet.comfychair.ui.components.settings.SettingsNavigationRow
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.ui.components.settings.SettingsSwitchRow
import sh.hnet.comfychair.update.UpdateCenter
import sh.hnet.comfychair.update.UpdateInfo
import sh.hnet.comfychair.update.UpdateState

@Composable
fun AboutSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenWhatsNew: () -> Unit,
    onOpenLicense: () -> Unit
) {
    val context = LocalContext.current
    val notes = remember { WhatsNew.notes(context) }
    var seen by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        seen = WhatsNew.isSeen(context)
        onPauseOrDispose { }
    }

    val libraryVersion = remember {
        runCatching { TagLibraryStore.get(context).library.value.version }.getOrDefault("")
    }

    val updateState by UpdateCenter.state.collectAsState()
    var autoCheck by remember { mutableStateOf(UpdateCenter.isEnabled(context)) }
    var updateSheet by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) { UpdateCenter.restore(context) }

    val statusText = when (val current = updateState) {
        is UpdateState.Checking -> stringResource(R.string.value_update_checking)
        is UpdateState.Available -> stringResource(R.string.value_update_available, current.info.versionName)
        is UpdateState.UpToDate -> stringResource(R.string.value_update_uptodate)
        is UpdateState.Failed -> stringResource(R.string.value_update_failed)
        UpdateState.Idle -> stringResource(R.string.value_update_idle)
    }

    fun openLink(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    SettingsPage(stringResource(R.string.title_about_settings), onNavigateBack) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(200.dp)
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = stringResource(R.string.label_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (libraryVersion.isNotBlank()) {
                Text(
                    text = "${stringResource(R.string.label_library_version)}：$libraryVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SettingsGroup(stringResource(R.string.section_version)) {
            row { shapes ->
                SettingsNavigationRow(
                    shapes = shapes,
                    icon = Icons.Rounded.SystemUpdateAlt,
                    title = stringResource(R.string.label_check_update),
                    supporting = statusText,
                    badge = if (updateState is UpdateState.Available) stringResource(R.string.whats_new_badge) else null,
                    onClick = {
                        val current = updateState
                        if (current is UpdateState.Available) updateSheet = current.info
                        else if (current !is UpdateState.Checking) UpdateCenter.check(context, force = true)
                    }
                )
            }
            row { shapes ->
                SettingsSwitchRow(
                    shapes = shapes,
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.label_update_auto),
                    supporting = stringResource(R.string.desc_update_auto),
                    checked = autoCheck,
                    onCheckedChange = {
                        autoCheck = it
                        UpdateCenter.setEnabled(context, it)
                        if (it) UpdateCenter.check(context)
                    }
                )
            }
            notes?.let { notes ->
                row { shapes ->
                    val count = notes.changelog.itemCount
                    SettingsNavigationRow(
                        shapes = shapes,
                        icon = Icons.Rounded.NewReleases,
                        title = stringResource(R.string.whats_new_title),
                        supporting = if (count > 0) {
                            pluralStringResource(R.plurals.whats_new_row_supporting, count, notes.version, count)
                        } else {
                            stringResource(R.string.label_about_version, notes.version)
                        },
                        badge = if (seen) null else stringResource(R.string.whats_new_badge),
                        onClick = onOpenWhatsNew
                    )
                }
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(
                    shapes = shapes,
                    icon = Icons.Rounded.WorkspacePremium,
                    title = stringResource(R.string.title_about_license),
                    supporting = stringResource(R.string.label_about_license_name),
                    onClick = onOpenLicense
                )
            }
            row { shapes ->
                SettingsLinkRow(
                    shapes = shapes,
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.title_about_source),
                    supporting = H3_SOURCE_ADDRESS,
                    onClick = { openLink("https://$H3_SOURCE_ADDRESS") }
                )
            }
            row { shapes ->
                SettingsLinkRow(
                    shapes = shapes,
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.title_about_upstream),
                    supporting = SOURCE_ADDRESS,
                    onClick = { openLink("https://$SOURCE_ADDRESS") }
                )
            }
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

private fun notesExcerpt(raw: String, maxChars: Int = 700): String {
    val lines = raw.lineSequence()
        .map { it.trim().trimStart('#').trim() }
        .filter { it.isNotEmpty() }
        .take(14)
        .toList()
    val text = lines.joinToString("\n")
    return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
}

private const val H3_SOURCE_ADDRESS = "github.com/mohuaji96/ComfyChair-H3"
private const val SOURCE_ADDRESS = "github.com/legal-hkr/comfychair"
