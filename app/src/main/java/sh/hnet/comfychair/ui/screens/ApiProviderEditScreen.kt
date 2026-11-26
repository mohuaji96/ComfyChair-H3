package sh.hnet.comfychair.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ApiFormat
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.net.PromptOptimizer
import sh.hnet.comfychair.ui.components.BottomActionBar
import sh.hnet.comfychair.ui.components.SettingsDangerButton
import sh.hnet.comfychair.ui.components.SettingsDetailScaffold
import sh.hnet.comfychair.ui.components.SettingsFootnote
import sh.hnet.comfychair.ui.components.SettingsPrimaryButton
import sh.hnet.comfychair.ui.components.SettingsSecondaryButton
import sh.hnet.comfychair.ui.components.SettingsSectionLabel
import sh.hnet.comfychair.ui.components.SettingsSelectField
import sh.hnet.comfychair.ui.components.SettingsTextField
import sh.hnet.comfychair.ui.components.StatusPill
import sh.hnet.comfychair.ui.components.StatusTone
import sh.hnet.comfychair.viewmodel.PromptOptimizerViewModel

/**
 * Everything one provider needs, and nothing that belongs to a single run.
 *
 * Every edit is written as it is typed, so leaving the page is safe and the bar only carries the
 * three things that are decisions: delete, test, done.
 */
@Composable
fun ApiProviderEditScreen(
    providerId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: PromptOptimizerViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.initialize(context) }

    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val keyPresent by viewModel.keyPresent.collectAsStateWithLifecycle()
    val testState by viewModel.test.collectAsStateWithLifecycle()
    val provider = providers.firstOrNull { it.id == providerId }

    if (provider == null) {
        SettingsDetailScaffold(title = stringResource(R.string.po_provider), onBack = onBack) {
            Text(
                stringResource(R.string.po_provider_gone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        return
    }

    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var model by remember(provider.id) { mutableStateOf(provider.model) }
    var format by remember(provider.id) { mutableStateOf(provider.format) }
    var apiPath by remember(provider.id) { mutableStateOf(provider.apiPath) }
    var maxTokens by remember(provider.id) { mutableStateOf(provider.maxTokens.toString()) }
    var timeoutSec by remember(provider.id) { mutableStateOf(provider.timeoutSec.toString()) }
    var keyDraft by remember(provider.id) { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var thinking by remember(provider.id) { mutableStateOf(provider.thinking) }
    var advancedOpen by remember { mutableStateOf(false) }
    var formatOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun snapshot(): ApiProvider = provider.copy(
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        format = format,
        apiPath = apiPath.trim(),
        maxTokens = maxTokens.toIntOrNull()
            ?.coerceIn(64, 32_000) ?: ApiProvider.DEFAULT_MAX_TOKENS,
        timeoutSec = timeoutSec.toIntOrNull()
            ?.coerceIn(ApiProvider.MIN_TIMEOUT_SEC, ApiProvider.MAX_TIMEOUT_SEC)
            ?: ApiProvider.DEFAULT_TIMEOUT_SEC,
        thinking = thinking
    )

    fun push() { viewModel.upsert(snapshot()) }

    // No save button and no debounce on the fields: a pending delay would be cancelled when the
    // user leaves the screen, silently dropping the edit. The key keeps a short one because
    // encrypting on every character is expensive.
    LaunchedEffect(keyDraft) {
        if (keyDraft.isNotBlank()) {
            delay(300)
            viewModel.saveApiKey(provider.id, keyDraft)
        }
    }

    SettingsDetailScaffold(
        title = provider.name.ifBlank { stringResource(R.string.po_unnamed_provider) },
        onBack = onBack,
        status = {
            StatusPill(
                text = if (provider.isUsable) stringResource(R.string.po_status_usable) else stringResource(R.string.po_status_unconfigured),
                tone = if (provider.isUsable) StatusTone.OK else StatusTone.OFF
            )
        },
        bottomBar = {
            BottomActionBar {
                SettingsDangerButton(stringResource(R.string.common_delete), onClick = { confirmDelete = true })
                SettingsSecondaryButton(
                    text = if (testState.running) stringResource(R.string.po_testing) else stringResource(R.string.po_test_connection),
                    onClick = {
                        push()
                        if (keyDraft.isNotBlank()) viewModel.saveApiKey(provider.id, keyDraft)
                        viewModel.runTest(snapshot(), keyDraft.ifBlank { null })
                    },
                    enabled = !testState.running,
                    modifier = Modifier.weight(1f)
                )
                SettingsPrimaryButton(
                    text = stringResource(R.string.common_done),
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        SettingsSectionLabel(stringResource(R.string.po_section_connection))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTextField(
                value = name,
                onValueChange = { name = it; push() },
                label = stringResource(R.string.po_field_name),
                placeholder = stringResource(R.string.po_name_placeholder)
            )
            SettingsTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; push() },
                label = stringResource(R.string.po_field_base_url),
                placeholder = "https://api.example.com",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            SettingsTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = "API Key",
                placeholder = if (keyPresent) stringResource(R.string.po_key_keep) else stringResource(R.string.po_status_unconfigured),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (keyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (keyPresent) {
                            TextButton(onClick = {
                                viewModel.clearApiKey(provider.id)
                                keyDraft = ""
                            }) { Text(stringResource(R.string.po_clear), fontSize = 11.sp) }
                        }
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) stringResource(R.string.po_hide) else stringResource(R.string.po_show),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )
            SettingsTextField(
                value = model,
                onValueChange = { model = it; push() },
                label = stringResource(R.string.po_field_model),
                placeholder = stringResource(R.string.po_model_placeholder)
            )
        }

        SettingsSectionLabel(stringResource(R.string.po_section_request))
        Box {
            SettingsSelectField(
                label = stringResource(R.string.po_field_format),
                value = formatLabel(context, format, apiPath.isNotBlank()),
                onClick = { formatOpen = true }
            )
            DropdownMenu(expanded = formatOpen, onDismissRequest = { formatOpen = false }) {
                ApiFormat.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(formatLabel(context, option, apiPath.isNotBlank()))
                                Text(
                                    formatHint(context, option),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            format = option
                            formatOpen = false
                            push()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.po_field_thinking), fontSize = 13.sp)
                Text(
                    stringResource(R.string.po_thinking_hint),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Say so up front: turning this off on these hosts only lowers the effort, and
                // an unexplained "still slow" is worse than a warning.
                if (!thinking && PromptOptimizer.thinkingCannotDisable(
                        provider.copy(baseUrl = baseUrl, model = model)
                    )
                ) {
                    Text(
                        stringResource(R.string.po_thinking_limited),
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Switch(
                checked = thinking,
                onCheckedChange = { on -> thinking = on; push() }
            )
        }

        Spacer(Modifier.height(9.dp))
        AdvancedToggle(open = advancedOpen, onToggle = { advancedOpen = !advancedOpen })

        if (advancedOpen) {
            Spacer(Modifier.height(9.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it.filter(Char::isDigit).take(6); push() },
                        label = stringResource(R.string.po_field_max_tokens),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        value = timeoutSec,
                        onValueChange = { timeoutSec = it.filter(Char::isDigit).take(3); push() },
                        label = stringResource(R.string.po_field_timeout),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.po_custom_path), fontSize = 13.sp)
                        Text(
                            stringResource(R.string.po_custom_path_hint),
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = apiPath.isNotBlank(),
                        onCheckedChange = { on ->
                            apiPath = if (on) "/chat/completions" else ""
                            push()
                        }
                    )
                }
                if (apiPath.isNotBlank()) {
                    SettingsTextField(
                        value = apiPath,
                        onValueChange = { apiPath = it; push() },
                        label = stringResource(R.string.po_field_path),
                        placeholder = "/openai/deployments/gpt-4/chat/completions",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Text(
                        stringResource(R.string.po_path_hint),
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (baseUrl.isNotBlank()) {
            SettingsFootnote(
                stringResource(R.string.po_actual_request) + PromptOptimizer.endpointFor(
                    provider.copy(baseUrl = baseUrl, format = format, model = model, apiPath = apiPath)
                )
            )
        }

        testState.message?.let { message ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (testState.running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    message,
                    fontSize = 11.5.sp,
                    color = when (testState.ok) {
                        true -> MaterialTheme.colorScheme.tertiary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            testState.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    stringResource(R.string.po_raw_response) + detail.take(220),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.po_delete_title)) },
            text = { Text(stringResource(R.string.po_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(provider.id)
                    onDeleted()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun AdvancedToggle(open: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.po_advanced_toggle),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatLabel(context: Context, format: ApiFormat, customPath: Boolean): String = when (format) {
    ApiFormat.OPENAI_CHAT -> context.getString(if (customPath) R.string.po_format_openai_compat else R.string.po_format_openai_chat_path)
    ApiFormat.OPENAI_RESPONSES -> context.getString(if (customPath) R.string.po_format_openai_responses else R.string.po_format_openai_responses_path)
    ApiFormat.ANTHROPIC -> context.getString(if (customPath) R.string.po_format_anthropic else R.string.po_format_anthropic_path)
    ApiFormat.GEMINI -> context.getString(if (customPath) R.string.po_format_gemini else R.string.po_format_gemini_path)
}

private fun formatHint(context: Context, format: ApiFormat): String = when (format) {
    ApiFormat.OPENAI_CHAT -> context.getString(R.string.po_hint_openai_chat)
    ApiFormat.OPENAI_RESPONSES -> context.getString(R.string.po_hint_openai_responses)
    ApiFormat.ANTHROPIC -> context.getString(R.string.po_hint_anthropic)
    ApiFormat.GEMINI -> context.getString(R.string.po_hint_gemini)
}


