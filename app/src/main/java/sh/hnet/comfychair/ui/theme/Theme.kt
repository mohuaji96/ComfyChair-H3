package sh.hnet.comfychair.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import sh.hnet.comfychair.storage.AppSettings

private val DarkColorScheme = darkColorScheme()

/**
 * 全局统一设计系统主题入口。
 *
 * 浅色：五套固定主题按 [ThemeRegistry] 取完整 ColorScheme；主题 ID 为 `dynamic` 时
 * 走系统壁纸取色（任务书 §7.2 的兼容选项，不计入第六套新主题）。
 * 深色：沿用升级前的动态深色方案，不自行发明五套深色配色（任务书 §7.2）。
 *
 * 主题切换通过 [AppSettings.themeFlow] 触发重组，不重建 Activity，草稿与运行中任务不受影响。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComfyChairTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    forceDarkStatusBar: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val liveThemeId by AppSettings.themeFlow.collectAsState()
    val themeId = liveThemeId ?: remember { AppSettings.getThemeId(context) }
    val isDynamic = AppTheme.isDynamic(themeId)
    val currentAppTheme = remember(themeId) { AppTheme.fromId(themeId) }

    val colorScheme = when {
        isDynamic -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context)
        } else {
            DarkColorScheme
        }
        else -> ThemeRegistry.getColorScheme(currentAppTheme)
    }

    // 状态栏与系统导航栏图标外观：按实际背景亮度决定，媒体页可用 forceDarkStatusBar 强制覆盖。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            val lightSurface = !darkTheme
            insetsController.isAppearanceLightStatusBars = when (forceDarkStatusBar) {
                true -> false
                false -> true
                null -> lightSurface
            }
            insetsController.isAppearanceLightNavigationBars = lightSurface
        }
    }

    CompositionLocalProvider(
        LocalSemanticColors provides SemanticColors(),
        LocalAppShapes provides AppShapes(),
        LocalAppElevations provides AppElevations()
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.standard(),
            shapes = AppMaterialShapes,
            typography = Typography,
            content = content
        )
    }
}
