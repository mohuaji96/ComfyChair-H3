package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.ui.components.generation.SizeRatios
import sh.hnet.comfychair.ui.components.generation.sizeFromAnchor

/**
 * The size popup's ratio chips: picking a shape keeps the side that was typed and fills in the other
 * one, so typing 1080 and picking 9:16 gives 1080×1920 rather than rewriting both numbers.
 */
class SizeRatioTest {

    private fun ratio(label: String) = SizeRatios.first { it.label == label }

    @Test
    fun `the typed width is kept and the height filled in`() {
        val (width, height) = sizeFromAnchor(1080, ratio("9:16"), keepWidth = true)
        assertEquals(1080, width)
        assertEquals(1920, height)
    }

    @Test
    fun `the typed height is kept and the width filled in`() {
        val (width, height) = sizeFromAnchor(1920, ratio("9:16"), keepWidth = false)
        assertEquals(1080, width)
        assertEquals(1920, height)
    }

    @Test
    fun `a typed side is never rewritten, not even to the grid`() {
        // 1081 is off the 8 px grid; the app fills the other side instead of tidying this one
        val (width, height) = sizeFromAnchor(1081, ratio("16:9"), keepWidth = true)
        assertEquals(1081, width)
        assertEquals(0, height % 8)
    }

    @Test
    fun `landscape and portrait shapes come out the right way round`() {
        val (tallWidth, tallHeight) = sizeFromAnchor(1024, ratio("9:16"), keepWidth = true)
        assertTrue(tallWidth < tallHeight)
        val (wideWidth, wideHeight) = sizeFromAnchor(1024, ratio("16:9"), keepWidth = true)
        assertTrue(wideWidth > wideHeight)
        // 3:4 and 4:3 are the same shape turned around: the same 1024 on the kept side gives the
        // two pairs transposed
        val (portraitW, portraitH) = sizeFromAnchor(1024, ratio("3:4"), keepWidth = true)
        val (landscapeW, landscapeH) = sizeFromAnchor(1024, ratio("4:3"), keepWidth = false)
        assertEquals(portraitW, landscapeH)
        assertEquals(portraitH, landscapeW)
    }

    @Test
    fun `the filled side is snapped to eight and stays inside the field range`() {
        SizeRatios.forEach { current ->
            val (filledWidth, filledHeight) = sizeFromAnchor(1080, current, keepWidth = true)
            assertEquals("${current.label} width", 1080, filledWidth)
            assertEquals("${current.label} height", 0, filledHeight % 8)
            assertTrue("${current.label} height $filledHeight", filledHeight in 16..16384)
            val (otherWidth, otherHeight) = sizeFromAnchor(1080, current, keepWidth = false)
            assertEquals("${current.label} height", 1080, otherHeight)
            assertTrue("${current.label} width $otherWidth", otherWidth in 16..16384)
        }
    }

    @Test
    fun `an empty side falls back to a one megapixel square`() {
        val (width, height) = sizeFromAnchor(0, ratio("1:1"), keepWidth = true)
        assertEquals(1024, width)
        assertEquals(1024, height)
    }
}
