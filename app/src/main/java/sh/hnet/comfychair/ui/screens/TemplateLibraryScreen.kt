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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            notice = "文件读不出来，或内容太短不像是模板"
            return@rememberLauncherForActivityResult
        }
        val created = PromptTemplate(
            id = "custom_${UUID.randomUUID()}",
            name = PromptTemplates.uniqueName(list, queryName(context, uri) ?: "导入模板"),
            body = text,
            builtIn = false,
            updatedAt = System.currentTimeMillis()
        )
        PromptTemplateStore.saveCustom(context, created)
        PromptTemplateStore.setActive(context, created.id)
        notice = "已导入「${created.name}」并启用"
        refresh()
    }

    SettingsDetailScaffold(
        title = "模板库",
        onBack = onBack,
        status = {
            Text(
                "${customs.size} 份自定义",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        bottomBar = {
            BottomActionBar {
                SettingsSecondaryButton(
                    text = "从文件导入",
                    onClick = {
                        importLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                    },
                    modifier = Modifier.weight(1f)
                )
                SettingsPrimaryButton(
                    text = "新建空白",
                    onClick = {
                        editing = PromptTemplate(
                            id = "custom_${UUID.randomUUID()}",
                            name = PromptTemplates.uniqueName(list, "新模板"),
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

        SettingsSectionLabel("内置", note = "只读")
        SettingsGroupCard {
            builtIns.forEachIndexed { index, template ->
                if (index > 0) SettingsRowDivider()
                SettingsItemRow(
                    title = template.name,
                    meta = "${template.blurb} · ${template.body.length} 字",
                    active = template.id == activeId,
                    onClick = { onOpenDetail(template.id) },
                    trailing = {
                        SimpleTextAction("复制") {
                            val copy = PromptTemplateStore.copyAsCustom(context, template)
                            PromptTemplateStore.setActive(context, copy.id)
                            notice = "已复制为「${copy.name}」并启用"
                            refresh()
                        }
                        if (template.id != activeId) {
                            SimpleTextAction("启动") {
                                PromptTemplateStore.setActive(context, template.id)
                                notice = "已启用「${template.name}」"
                                refresh()
                            }
                        }
                    }
                )
            }
        }

        SettingsSectionLabel("自定义", note = if (customs.isEmpty()) "还没有" else "${customs.size} 份")
        if (customs.isEmpty()) {
            Text(
                "还没有自定义模板。复制内置的一份，或者从文件导入。",
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
                            template.sourceName.takeIf { it.isNotEmpty() }?.let { "复制自「$it」" }
                                ?: "手动导入",
                            "${template.body.length} 字"
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
                notice = "已保存「${saved.name}」"
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

