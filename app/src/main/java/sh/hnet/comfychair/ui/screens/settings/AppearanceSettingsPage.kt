package sh.hnet.comfychair.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.ui.theme.AppTheme
import sh.hnet.comfychair.ui.theme.ThemeRegistry

/** 外观页的一行：五套固定主题由枚举给出，动态取色单独一项。 */
private data class ThemeOption(
    val id: String,
    val label: String,
    val note: String,
    val background: Color,
    val container: Color,
    val primary: Color,
    val onPrimary: Color,
    val onContainer: Color
)

/**
 * 外观与主题设置页（任务书 §7.1）。
 *
 * 五套固定主题 + 一项「跟随系统壁纸」；动态取色属兼容选项，不计入第六套新主题（§7.2）。
 * 点击即写稳定 ID，由主题流即时重组，不重建 Activity，也不触碰任何业务数据。
 */
@Composable
fun AppearanceSettingsPage(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val liveThemeId by AppSettings.themeFlow.collectAsState()
    val currentThemeId = liveThemeId ?: AppSettings.getThemeId(context)
    val isZh = LocalConfiguration.current.locales[0].language.startsWith("zh")

    val dynamicScheme = dynamicLightColorScheme(context)
    val options = buildList {
        AppTheme.entries.forEach { theme ->
            val palette = ThemeRegistry.definition(theme)
            add(
                ThemeOption(
                    id = theme.id,
                    label = "${theme.number} " + if (isZh) theme.displayNameZh else theme.displayNameEn,
                    note = stringResource(R.string.theme_fixed_note),
                    background = palette.background,
                    container = palette.primaryContainer,
                    primary = palette.primary,
                    onPrimary = palette.onPrimary,
                    onContainer = palette.onPrimaryContainer
                )
            )
        }
        add(
            ThemeOption(
                id = AppTheme.DYNAMIC_ID,
                label = stringResource(R.string.theme_dynamic_name),
                note = stringResource(R.string.theme_dynamic_note),
                background = dynamicScheme.background,
                container = dynamicScheme.primaryContainer,
                primary = dynamicScheme.primary,
                onPrimary = dynamicScheme.onPrimary,
                onContainer = dynamicScheme.onPrimaryContainer
            )
        )
    }

    SettingsPage(stringResource(R.string.title_settings_appearance), onNavigateBack) {
        Text(
            text = stringResource(R.string.desc_settings_appearance_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        options.forEach { option ->
            val selected = option.id == currentThemeId
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { AppSettings.setThemeId(context, option.id) }
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = option.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RadioButton(
                        selected = selected,
                        onClick = { AppSettings.setThemeId(context, option.id) }
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemeColorSwatch(stringResource(R.string.theme_label_background), option.background)
                    ThemeColorSwatch(stringResource(R.string.theme_label_container), option.container)
                    ThemeColorSwatch(stringResource(R.string.theme_label_primary), option.primary)

                    Spacer(Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(option.primary)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.theme_preview_button),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = option.onPrimary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(option.container)
                            .border(1.dp, option.primary.copy(alpha = 0.4f), RoundedCornerShape(percent = 50))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.theme_preview_selected),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = option.onContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorSwatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(color)
                .border(0.8.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
