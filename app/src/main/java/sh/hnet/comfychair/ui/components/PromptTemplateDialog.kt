package sh.hnet.comfychair.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.storage.PromptTemplate

/**
 * Full-screen editor for one custom template.
 *
 * Kept as a dialog rather than a route: the text is 20-40 KB, and leaving it any other way would
 * mean rebuilding the whole body on every navigation save. Everything around the dialog (library,
 * detail) is a normal page now.
 */
@Composable
fun TemplateEditorDialog(
    template: PromptTemplate,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(template.id) { mutableStateOf(template.name) }
    var body by remember(template.id) { mutableStateOf(template.body) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑模板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { onSave(name.trim().ifEmpty { template.name }, body) },
                        enabled = body.isNotBlank()
                    ) { Text("保存") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${body.lineSequence().count()} 行 · ${body.length} 字",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 8.dp)
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    ),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SettingsSecondaryButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    SettingsPrimaryButton(
                        text = "保存",
                        onClick = { onSave(name.trim().ifEmpty { template.name }, body) },
                        enabled = body.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

internal fun queryName(context: Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.substringBeforeLast('.') else null
    }
}.getOrNull()

