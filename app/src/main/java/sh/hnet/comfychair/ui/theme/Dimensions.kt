package sh.hnet.comfychair.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Centralized dimension constants for the app.
 */
object Dimensions {
    /** Size for placeholder logo shown in empty preview areas */
    val PlaceholderLogoSize = 300.dp

    // 间距基准 4 / 8 / 12 / 16 / 24（任务书 §5.3），供新组件取用；
    // 既有页面保留各自的实际外边距，不按此表重排。
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space6 = 24.dp

    /** 触控热区下限（§5.3） */
    val MinTouchTarget = 48.dp

    /** 图标常用尺寸 20–24dp（§5.3） */
    val IconSmall = 20.dp
    val IconMedium = 24.dp
}
