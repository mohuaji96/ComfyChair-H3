package sh.hnet.comfychair.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.PromptTemplateStore
import sh.hnet.comfychair.storage.PromptTemplates
import sh.hnet.comfychair.ui.components.BottomActionBar
import sh.hnet.comfychair.ui.components.OnResume
import sh.hnet.comfychair.ui.components.SettingsDangerButton
import sh.hnet.comfychair.ui.components.SettingsDetailScaffold
import sh.hnet.comfychair.ui.components.SettingsFootnote
import sh.hnet.comfychair.ui.components.SettingsGroupCard
import sh.hnet.comfychair.ui.components.SettingsNavRow
import sh.hnet.comfychair.ui.components.SettingsPrimaryButton
import sh.hnet.comfychair.ui.components.SettingsSecondaryButton
import sh.hnet.comfychair.ui.components.SettingsSectionLabel
import sh.hnet.comfychair.ui.components.TemplateEditorDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How much of the body is drawn in the preview; the editor is where the whole thing is read. */
private const val PREVIEW_LINES = 120

/**
 * One template: what it is, what is inside, and the three things you can do with it. Actions used
 * to sit on every row of the library, which turned a list into a wall of buttons.
 */
@Composable
fun TemplateDetailScreen(
    templateId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf("") }
    val list = remember(version) { PromptTemplateStore.load(context) }
    val activeId = remember(version) { PromptTemplateStore.activeId(context) }
    OnResume { version++ }
    val template = list.firstOrNull { it.id == templateId }

    if (template == null) {
        SettingsDetailScaffold(title = stringResource(R.string.tpl_detail_title), onBack = onBack) {
            Text(
                stringResource(R.string.tpl_detail_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        return
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(template.body.toByteArray(Charsets.UTF_8))
            }
        }.isSuccess
        notice = if (ok) context.getString(R.string.tpl_notice_exported, template.name)
        else context.getString(R.string.tpl_notice_export_failed)
    }

    val lines = remember(template.id, template.body) { template.body.lineSequence().count() }
    val preview = remember(template.id, template.body) {
        val all = template.body.lines()
        if (all.size <= PREVIEW_LINES) template.body
        else all.take(PREVIEW_LINES).joinToString("\n") + "\n……"
    }

    SettingsDetailScaffold(
        title = template.name,
        onBack = onBack,
        status = {
            Text(
                stringResource(
                    if (template.builtIn) R.string.tpl_status_builtin else R.string.tpl_status_custom
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        bottomBar = {
            BottomActionBar {
                if (template.builtIn) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.tpl_action_copy_as_custom),
                        onClick = {
                            val copy = PromptTemplateStore.copyAsCustom(context, template)
                            PromptTemplateStore.setActive(context, copy.id)
                            notice = context.getString(R.string.tpl_notice_copied, copy.name)
                            version++
                        },
                        modifier = Modifier.weight(1f)
                    )
                    SettingsPrimaryButton(
                        text = stringResource(
                            if (template.id == activeId) R.string.tpl_action_enabled
                            else R.string.tpl_action_activate
                        ),
                        onClick = {
                            PromptTemplateStore.setActive(context, template.id)
                            version++
                        },
                        enabled = template.id != activeId,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    SettingsDangerButton(
                        stringResource(R.string.tpl_action_delete),
                        onClick = { confirmDelete = true }
                    )
                    SettingsSecondaryButton(
                        text = stringResource(R.string.tpl_action_export_md),
                        onClick = { exportLauncher.launch(template.name + ".md") },
                        modifier = Modifier.weight(1f)
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.tpl_action_edit),
                        onClick = { editing = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) {
        // What this template is for, in the player's words: the two built-ins differ in how much
        // reasoning the model does, not in quality, and the choice is reversible at any time.
        if (template.blurb.isNotEmpty()) {
            Text(
                when (template.id) {
                    PromptTemplates.LITE_ID -> stringResource(R.string.tpl_blurb_lite)
                    PromptTemplates.LONG_ID -> stringResource(R.string.tpl_blurb_long)
                    else -> template.blurb
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.tpl_meta_lines, lines),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.tpl_meta_chars, template.body.length),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (template.updatedAt > 0) {
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(
                        R.string.tpl_meta_modified_at,
                        SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(template.updatedAt))
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (template.sourceName.isNotBlank()) {
            Text(
                stringResource(R.string.tpl_meta_copied_from, template.sourceName),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (!template.builtIn) {
            SettingsSectionLabel(stringResource(R.string.tpl_section_name))
            SettingsGroupCard {
                SettingsNavRow(
                    label = stringResource(R.string.tpl_field_name),
                    value = template.name,
                    onClick = {
                        renameDraft = template.name
                        renameOpen = true
                    }
                )
            }
        }

        notice?.let {
            Text(
                it,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        SettingsSectionLabel(
            stringResource(R.string.tpl_section_body),
            note = stringResource(R.string.tpl_note_readonly_preview)
        )
        SettingsGroupCard {
            Text(
                preview,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)
            )
        }

        if (template.builtIn) {
            SettingsFootnote(stringResource(R.string.tpl_footnote_builtin))
        } else {
            SettingsFootnote(stringResource(R.string.tpl_footnote_custom, PREVIEW_LINES))
        }

        Spacer(Modifier.height(8.dp))
    }

    if (editing && !template.builtIn) {
        TemplateEditorDialog(
            template = template,
            onDismiss = { editing = false },
            onSave = { name, body ->
                PromptTemplateStore.saveCustom(
                    context,
                    template.copy(name = name, body = body, updatedAt = System.currentTimeMillis())
                )
                editing = false
                notice = context.getString(R.string.tpl_notice_saved_short)
                version++
            }
        )
    }


    if (renameOpen && !template.builtIn) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringResource(R.string.tpl_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text(stringResource(R.string.tpl_field_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameDraft.trim()
                        if (newName.isNotEmpty()) {
                            PromptTemplateStore.saveCustom(context, template.copy(name = newName))
                            notice = context.getString(R.string.tpl_notice_renamed, newName)
                            version++
                        }
                        renameOpen = false
                    }
                ) { Text(stringResource(R.string.tpl_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) {
                    Text(stringResource(R.string.tpl_cancel))
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tpl_delete_title, template.name)) },
            text = { Text(stringResource(R.string.tpl_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val next = PromptTemplates.nextActiveAfterDelete(list, activeId, template.id)
                    PromptTemplateStore.delete(context, template.id)
                    PromptTemplateStore.setActive(context, next)
                    onBack()
                }) { Text(stringResource(R.string.tpl_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.tpl_cancel))
                }
            }
        )
    }
}

