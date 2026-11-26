package sh.hnet.comfychair.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 跨主题语义颜色 Token。
 * 按照《全局设计规范与五套主题开发任务书 V1.0》第 4 节定义，
 * 用于状态灯、队列角标、连接状态、词库锁定/忽略等，不随品牌主色漂移。
 */
@Immutable
data class SemanticColors(
    val success: Color = Color(0xFF166534),
    val successContainer: Color = Color(0xFFDCFCE7),
    val warning: Color = Color(0xFF854D0E),
    val warningContainer: Color = Color(0xFFFEF3C7),
    val error: Color = Color(0xFFB42318),
    val errorContainer: Color = Color(0xFFFEE4E2),
    val info: Color = Color(0xFF175CD3),
    val infoContainer: Color = Color(0xFFE6F0FF),
    val locked: Color = Color(0xFF6B5715),
    val lockedContainer: Color = Color(0xFFF5EDB8),
    val ignored: Color = Color(0xFF60656E),
    val ignoredContainer: Color = Color(0xFFEEF0F2)
)

val LocalSemanticColors = staticCompositionLocalOf { SemanticColors() }
