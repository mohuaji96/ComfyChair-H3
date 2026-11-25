package sh.hnet.comfychair.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.PromptTemplate
import sh.hnet.comfychair.storage.PromptTemplateStore
import sh.hnet.comfychair.storage.PromptTemplates
import sh.hnet.comfychair.ui.components.ActiveTag
import sh.hnet.comfychair.ui.components.BottomActionBar
import sh.hnet.comfychair.ui.components.OnResume
import sh.hnet.comfychair.ui.components.SettingsDetailScaffold
import sh.hnet.comfychair.ui.components.SettingsGroupCard
import sh.hnet.comfychair.ui.components.SettingsItemRow
import sh.hnet.comfychair.ui.components.SettingsPrimaryButton
import sh.hnet.comfychair.ui.components.SettingsRowDivider
import sh.hnet.comfychair.ui.components.SettingsSecondaryButton
import sh.hnet.comfychair.ui.components.SettingsSectionLabel
import sh.hnet.comfychair.ui.components.TemplateEditorDialog
import sh.hnet.comfychair.ui.components.queryName
import java.util.UUID

/**
 * The template library as a page.
 *
 * Import and copy only ever append — the bundled rules cannot be overwritten. Per-row buttons are
 * down to the one decision that belongs here ("which one is on"); rename, export and delete moved
 * into the detail page.
 */
@Composable
fun TemplateLibraryScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PromptTemplate?>(null) }
    val list = remember(version) { PromptTemplateStore.load(context) }
    val activeId = remember(version) { PromptTemplateStore.activeId(context) }
    val builtIns = list.filter { it.builtIn }
    val customs = list.filter { !it.builtIn }

    fun refresh() { version++ }

    OnResume { version++ }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank() || text.length < 200) {
            notice = context.getString(R.string.tpl_notice_import_failed)
            return@rememberLauncherForActivityResult
        }
        val created = PromptTemplate(
            id = "custom_${UUID.randomUUID()}",
            name = PromptTemplates.uniqueName(
                list,
                queryName(context, uri) ?: context.getString(R.string.tpl_default_imported_name)
            ),
            body = text,
            builtIn = false,
            updatedAt = System.currentTimeMillis()
        )
        PromptTemplateStore.saveCustom(context, created)
        PromptTemplateStore.setActive(context, created.id)
        notice = context.getString(R.string.tpl_notice_imported, created.name)
        refresh()
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.tpl_title),
        onBack = onBack,
        status = {
            Text(
                stringResource(R.string.tpl_custom_count, customs.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        bottomBar = {
            BottomActionBar {
                SettingsSecondaryButton(
                    text = stringResource(R.string.tpl_import_from_file),
                    onClick = {
                        importLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                    },
                    modifier = Modifier.weight(1f)
                )
                SettingsPrimaryButton(
                    text = stringResource(R.string.tpl_new_blank),
                    onClick = {
                        editing = PromptTemplate(
                            id = "custom_${UUID.randomUUID()}",
                            name = PromptTemplates.uniqueName(
                                list,
                                context.getString(R.string.tpl_default_new_name)
                            ),
                            body = "",
                            builtIn = false
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        notice?.let {
            Text(
                it,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        SettingsSectionLabel(
            stringResource(R.string.tpl_section_builtin),
            note = stringResource(R.string.tpl_note_readonly)
        )
        SettingsGroupCard {
            builtIns.forEachIndexed { index, template ->
                if (index > 0) SettingsRowDivider()
                SettingsItemRow(
                    title = template.name,
                    meta = stringResource(
                        R.string.tpl_meta_chars_with_blurb,
                        template.blurb,
                        template.body.length
                    ),
                    active = template.id == activeId,
                    onClick = { onOpenDetail(template.id) },
                    trailing = {
                        SimpleTextAction(stringResource(R.string.tpl_action_copy)) {
                            val copy = PromptTemplateStore.copyAsCustom(context, template)
                            PromptTemplateStore.setActive(context, copy.id)
                            notice = context.getString(R.string.tpl_notice_copied, copy.name)
                            refresh()
                        }
                        if (template.id != activeId) {
                            SimpleTextAction(stringResource(R.string.tpl_action_activate)) {
                                PromptTemplateStore.setActive(context, template.id)
                                notice = context.getString(R.string.tpl_notice_activated, template.name)
                                refresh()
                            }
                        }
                    }
                )
            }
        }

        SettingsSectionLabel(
            stringResource(R.string.tpl_section_custom),
            note = if (customs.isEmpty()) stringResource(R.string.tpl_note_none_yet)
            else stringResource(R.string.tpl_note_count, customs.size)
        )
        if (customs.isEmpty()) {
            Text(
                stringResource(R.string.tpl_empty_custom),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            SettingsGroupCard {
                customs.forEachIndexed { index, template ->
                    if (index > 0) SettingsRowDivider()
                    SettingsItemRow(
                        title = template.name,
                        meta = listOf(
                            template.sourceName.takeIf { it.isNotEmpty() }
                                ?.let { stringResource(R.string.tpl_meta_copied_from, it) }
                                ?: stringResource(R.string.tpl_meta_manual_import),
                            stringResource(R.string.tpl_meta_chars, template.body.length)
                        ).joinToString(" · "),
                        active = template.id == activeId,
                        onClick = { onOpenDetail(template.id) }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    editing?.let { target ->
        TemplateEditorDialog(
            template = target,
            onDismiss = { editing = null },
            onSave = { name, body ->
                val saved = target.copy(name = name, body = body, updatedAt = System.currentTimeMillis())
                PromptTemplateStore.saveCustom(context, saved)
                if (PromptTemplateStore.activeId(context) == null) {
                    PromptTemplateStore.setActive(context, saved.id)
                }
                notice = context.getString(R.string.tpl_notice_saved, saved.name)
                editing = null
                refresh()
            }
        )
    }
}

@Composable
private fun SimpleTextAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text, fontSize = 11.5.sp) }
}

