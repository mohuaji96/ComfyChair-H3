package sh.hnet.comfychair.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局统一 Shape Token。
 * 按照《全局设计规范与五套主题开发任务书 V1.0》第 5.1 节定义。
 */
@Immutable
data class AppShapes(
    val surfaceLarge: Shape = RoundedCornerShape(24.dp),
    val surfaceMedium: Shape = RoundedCornerShape(20.dp),
    val control: Shape = RoundedCornerShape(12.dp),
    val chip: Shape = RoundedCornerShape(10.dp),
    val dialog: Shape = RoundedCornerShape(24.dp),
    val sheetTop: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    val actionCapsule: Shape = RoundedCornerShape(percent = 50),
    val thumbnail: Shape = RoundedCornerShape(12.dp)
)

/**
 * 全局统一 Elevation Token（Soft UI 轻拟态轻微浮层）。
 * 按照《全局设计规范与五套主题开发任务书 V1.0》第 5.2 节定义。
 */
@Immutable
data class AppElevations(
    val flat: Dp = 0.dp,
    val card: Dp = 1.dp,
    val floating: Dp = 2.5.dp,
    val overlay: Dp = 6.dp
)

val LocalAppShapes = staticCompositionLocalOf { AppShapes() }
val LocalAppElevations = staticCompositionLocalOf { AppElevations() }

/**
 * 挂到 MaterialTheme.shapes 上的那一份，让系统组件默认拿到同一套圆角。
 * 小/中/大分别对上 control / surfaceMedium / surfaceLarge。
 */
val AppMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
