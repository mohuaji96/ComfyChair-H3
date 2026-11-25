package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.ui.components.generation.SizeRatios
import sh.hnet.comfychair.ui.components.generation.sizeForRatio

/**
 * The size popup's ratio chips: a shape change must keep the pixel count the model was set up for,
 * and every side must land on the 8 px grid the two fields step on.
 */
class SizeRatioTest {

    private fun ratio(label: String) = SizeRatios.first { it.label == label }

    @Test
    fun `keeps the pixel count when the shape changes`() {
        val (width, height) = sizeForRatio(980, 1680, ratio("1:1"))
        val before = 980.0 * 1680.0
        val after = width.toDouble() * height.toDouble()
        // Snapping each side to 8 px moves the area by at most a few per mille
        assertTrue("area $after should stay near $before", after in before * 0.97..before * 1.03)
        assertEquals(width, height)
    }

    @Test
    fun `portrait and landscape ratios come out the right way round`() {
        val (portraitWidth, portraitHeight) = sizeForRatio(1024, 1024, ratio("9:16"))
        assertTrue(portraitWidth < portraitHeight)
        val (landscapeWidth, landscapeHeight) = sizeForRatio(1024, 1024, ratio("16:9"))
        assertTrue(landscapeWidth > landscapeHeight)
        // 3:4 and 4:3 are the same shape turned around
        val (threeFourW, threeFourH) = sizeForRatio(1024, 1024, ratio("3:4"))
        val (fourThreeW, fourThreeH) = sizeForRatio(1024, 1024, ratio("4:3"))
        assertEquals(threeFourW, fourThreeH)
        assertEquals(threeFourH, fourThreeW)
    }

    @Test
    fun `sides are snapped to eight and stay inside the field range`() {
        SizeRatios.forEach { current ->
            val (width, height) = sizeForRatio(980, 1680, current)
            assertEquals("${current.label} width", 0, width % 8)
            assertEquals("${current.label} height", 0, height % 8)
            assertTrue("${current.label} width $width", width in 16..16384)
            assertTrue("${current.label} height $height", height in 16..16384)
        }
    }

    @Test
    fun `an unreadable size falls back to a one megapixel square`() {
        val (width, height) = sizeForRatio(0, 0, ratio("1:1"))
        assertEquals(1024, width)
        assertEquals(1024, height)
    }
}
