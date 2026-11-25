package sh.hnet.comfychair.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The H3 tab's mark: a director's chair.
 *
 * The tab used to borrow the sparkle glyph, which on a bar that already has a video tab read as
 * "rewrite something" rather than "the room where a burst is directed". A chair keeps the family
 * name, and nothing else in the five tabs is furniture, so it stands apart at a glance.
 */
object H3TabIcons {

    /** Solid: the rail, the two posts, the seat and the crossed legs of a folding frame. */
    private const val FILLED_PATH =
        "M3.6,4.6 H20.4 V6.9 H3.6 Z " +
            "M5.2,6.9 H7.2 V12.4 H5.2 Z M16.8,6.9 H18.8 V12.4 H16.8 Z " +
            "M2.6,11.9 H21.4 V14.4 H2.6 Z " +
            "M6.6,14.9 L7.6,13.5 L17.4,20.5 L16.4,21.9 Z " +
            "M17.4,14.9 L16.4,13.5 L6.6,20.5 L7.6,21.9 Z"

    /** Wireframe: the same chair in four strokes, for the unselected tab. */
    private const val OUTLINE_PATH =
        "M4.2,5.8 H19.8 M6.2,5.8 V12.8 M17.8,5.8 V12.8 " +
            "M3,13.2 H21 M6.8,15 L17.2,21.2 M17.2,15 L6.8,21.2"

    private fun chair(filled: Boolean): ImageVector {
        val builder = ImageVector.Builder(
            name = if (filled) "H3ChairFilled" else "H3ChairOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        if (filled) {
            builder.addPath(
                pathData = PathParser().parsePathString(FILLED_PATH).toNodes(),
                fill = SolidColor(Color.Black)
            )
        } else {
            builder.addPath(
                pathData = PathParser().parsePathString(OUTLINE_PATH).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return builder.build()
    }

    val Filled: ImageVector by lazy { chair(true) }
    val Outlined: ImageVector by lazy { chair(false) }
}
