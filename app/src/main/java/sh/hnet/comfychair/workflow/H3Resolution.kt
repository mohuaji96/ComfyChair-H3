package sh.hnet.comfychair.workflow

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Aspect ratio + megapixel budget -> the exact width/height that will be submitted.
 *
 * The H3 core node declares `width`/`height` as INT with min 32, max 16384, step 32
 * (verified from `/object_info/MiniMaxH3ImageToVideo`), so every result is snapped to the
 * node's own grid. The displayed size and the submitted size come from this single function.
 */
object H3Resolution {

    data class Size(val width: Int, val height: Int) {
        val megapixels: Double get() = width.toDouble() * height.toDouble() / 1_000_000.0
        override fun toString(): String = "$width × $height"
    }

    /**
     * Ratios offered by the UI, ordered widest to tallest so neighbouring chips share an
     * orientation instead of alternating wide/tall/square. The workflow still decides which are
     * actually usable — [sh.hnet.comfychair.workflow.H3Plan.matchRatio] filters against the
     * server's own `aspect_ratio` list.
     */
    val ratios: List<Pair<String, Pair<Int, Int>>> = listOf(
        "21:9" to (21 to 9),
        "16:9" to (16 to 9),
        "3:2" to (3 to 2),
        "4:3" to (4 to 3),
        "1:1" to (1 to 1),
        "3:4" to (3 to 4),
        "2:3" to (2 to 3),
        "9:16" to (9 to 16)
    )

    /**
     * The order the chips are shown in: the six ratios that get used, wide / tall / square first.
     * Separate from [ratios] on purpose — that list is the whitelist and stays sorted by shape, so
     * a later server addition can pick a chip position without disturbing the filter.
     */
    val commonRatios: List<String> = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "3:2")

    /** Everything else, behind「更多比例」. */
    val extraRatios: List<String> = listOf("21:9", "2:3")

    /** A ratio the page can show; the value is what gets written into the workflow. */
    data class Ratio(val value: String, val w: Int, val h: Int)

    private val ratioPattern = Regex("""(\d+)\s*:\s*(\d+)""")

    /** Reads `16:9 (Widescreen)` or a bare `16:9`; anything else is not a ratio. */
    fun parseRatio(option: String): Ratio? {
        val match = ratioPattern.find(option) ?: return null
        val w = match.groupValues[1].toIntOrNull() ?: return null
        val h = match.groupValues[2].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return Ratio("$w:$h", w, h)
    }

    /**
     * Ratios to offer, taken from the server's own `aspect_ratio` list when it has one, so a server
     * that adds 5:4 gets a chip without a code change. The built-in table is only the fallback for
     * an offline first launch. Ordered widest to tallest so neighbouring chips share an orientation.
     */
    fun fromServer(options: List<String>): List<Ratio> {
        val parsed = options.mapNotNull { parseRatio(it) }.distinctBy { it.value }
        val list = parsed.ifEmpty { ratios.map { (value, size) -> Ratio(value, size.first, size.second) } }
        return list.sortedByDescending { it.w.toDouble() / it.h.toDouble() }
    }

    /** Short Chinese gloss for the server's English ratio names; unknown ratios fall back to bare. */
    private val labels = mapOf(
        "21:9" to "超宽", "16:9" to "宽屏", "3:2" to "横版", "4:3" to "标准横",
        "1:1" to "方形", "3:4" to "标准竖", "2:3" to "竖版", "9:16" to "竖屏"
    )

    fun label(ratio: String): String = labels[ratio]?.let { "$ratio $it" } ?: ratio

    val megapixelPresets: List<Double> = listOf(0.4, 0.5, 0.6)

    fun snap(value: Double, step: Int, min: Int, max: Int): Int {
        val s = step.coerceAtLeast(1)
        val snapped = (value / s).roundToInt() * s
        return snapped.coerceIn(min, max)
    }

    fun size(
        aspectW: Int,
        aspectH: Int,
        megapixels: Double,
        step: Int = 32,
        min: Int = 32,
        max: Int = 16384
    ): Size {
        require(aspectW > 0 && aspectH > 0) { "宽高比必须为正数" }
        require(megapixels > 0) { "像素量必须为正数" }
        val pixels = megapixels * 1_000_000.0
        val ratio = aspectW.toDouble() / aspectH.toDouble()
        val height = sqrt(pixels / ratio)
        val width = height * ratio
        return Size(
            width = snap(width, step, min, max),
            height = snap(height, step, min, max)
        )
    }

    /** Closest preset to the size the user currently has, or null for a custom size. */
    fun matchingPreset(size: Size): Double? =
        megapixelPresets.minByOrNull { abs(it - size.megapixels) }
            ?.takeIf { abs(it - size.megapixels) <= 0.02 }

    /** True when the workflow's own grid cannot express this ratio at this budget. */
    fun isExpressible(aspectW: Int, aspectH: Int, megapixels: Double, step: Int, min: Int, max: Int): Boolean {
        val s = size(aspectW, aspectH, megapixels, step, min, max)
        val ratio = aspectW.toDouble() / aspectH.toDouble()
        val actual = s.width.toDouble() / s.height.toDouble()
        return abs(actual - ratio) / ratio <= 0.05 &&
            abs(s.megapixels - megapixels) / megapixels <= 0.15
    }
}
