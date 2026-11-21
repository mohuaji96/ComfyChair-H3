package sh.hnet.comfychair.ui.screens

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
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
        SettingsDetailScaffold(title = "供应商", onBack = onBack) {
            Text(
                "这个供应商已经不在了。",
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
            ?: ApiProvider.DEFAULT_TIMEOUT_SEC
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
        title = provider.name.ifBlank { "未命名供应商" },
        onBack = onBack,
        status = {
            StatusPill(
                text = if (provider.isUsable) "可用" else "待配置",
                tone = if (provider.isUsable) StatusTone.OK else StatusTone.OFF
            )
        },
        bottomBar = {
            BottomActionBar {
                SettingsDangerButton("删除", onClick = { confirmDelete = true })
                SettingsSecondaryButton(
                    text = if (testState.running) "测试中…" else "测试连接",
                    onClick = {
                        push()
                        if (keyDraft.isNotBlank()) viewModel.saveApiKey(provider.id, keyDraft)
                        viewModel.runTest(snapshot(), keyDraft.ifBlank { null })
                    },
                    enabled = !testState.running,
                    modifier = Modifier.weight(1f)
                )
                SettingsPrimaryButton(
                    text = "完成",
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        SettingsSectionLabel("连接")

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTextField(
                value = name,
                onValueChange = { name = it; push() },
                label = "名称",
                placeholder = "例如：DeepSeek / 中转站"
            )
            SettingsTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; push() },
                label = "接口地址",
                placeholder = "https://api.example.com",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            SettingsTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = "API Key",
                placeholder = if (keyPresent) "留空则保留当前 Key" else "未配置",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (keyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (keyPresent) {
                            TextButton(onClick = {
                                viewModel.clearApiKey(provider.id)
                                keyDraft = ""
                            }) { Text("清除", fontSize = 11.sp) }
                        }
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) "隐藏" else "显示",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )
            SettingsTextField(
                value = model,
                onValueChange = { model = it; push() },
                label = "模型名",
                placeholder = "例如：deepseek-chat / gpt-4o-mini"
            )
        }

        SettingsSectionLabel("请求")
        Box {
            SettingsSelectField(
                label = "格式",
                value = formatLabel(format, apiPath.isNotBlank()),
                onClick = { formatOpen = true }
            )
            DropdownMenu(expanded = formatOpen, onDismissRequest = { formatOpen = false }) {
                ApiFormat.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(formatLabel(option, apiPath.isNotBlank()))
                                Text(
                                    formatHint(option),
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
        AdvancedToggle(open = advancedOpen, onToggle = { advancedOpen = !advancedOpen })

        if (advancedOpen) {
            Spacer(Modifier.height(9.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it.filter(Char::isDigit).take(6); push() },
                        label = "最大 token",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        value = timeoutSec,
                        onValueChange = { timeoutSec = it.filter(Char::isDigit).take(3); push() },
                        label = "超时（秒）",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义接口路径", fontSize = 13.sp)
                        Text(
                            "Azure、自建网关等路径不规则时使用",
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
                        label = "接口路径",
                        placeholder = "/openai/deployments/gpt-4/chat/completions",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Text(
                        "从根地址之后写起，可带查询参数；打开后程序不再自动补版本段。",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (baseUrl.isNotBlank()) {
            SettingsFootnote(
                "实际请求：" + PromptOptimizer.endpointFor(
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
                    "原始返回：" + detail.take(220),
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
            title = { Text("删除这个供应商？") },
            text = { Text("地址、Key 和模型名会一起删掉，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(provider.id)
                    onDeleted()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
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
                "高级 · 最大 token · 超时 · 自定义路径",
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

private fun formatLabel(format: ApiFormat, customPath: Boolean): String = when (format) {
    ApiFormat.OPENAI_CHAT -> if (customPath) "OpenAI 兼容" else "OpenAI 兼容 · /chat/completions"
    ApiFormat.OPENAI_RESPONSES -> if (customPath) "OpenAI Responses" else "OpenAI Responses · /responses"
    ApiFormat.ANTHROPIC -> if (customPath) "Anthropic" else "Anthropic · /messages"
    ApiFormat.GEMINI -> if (customPath) "Gemini" else "Gemini · generateContent"
}

private fun formatHint(format: ApiFormat): String = when (format) {
    ApiFormat.OPENAI_CHAT -> "中转站、DeepSeek、Kimi、通义、智谱、OpenRouter"
    ApiFormat.OPENAI_RESPONSES -> "OpenAI 新一代 Responses 接口"
    ApiFormat.ANTHROPIC -> "Claude 官方接口"
    ApiFormat.GEMINI -> "Google Gemini 官方接口"
}


