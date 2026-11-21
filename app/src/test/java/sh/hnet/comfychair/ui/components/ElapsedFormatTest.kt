package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Long videos on a slow card cross the hour, and `62:33` reads like a different unit.
 */
class ElapsedFormatTest {

    @Test
    fun `seconds and minutes stay on one clock face`() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:07", formatElapsed(7_400))
        assertEquals("1:23", formatElapsed(83_000))
        assertEquals("9:59", formatElapsed(599_000))
    }

    @Test
    fun `past an hour the hour part appears`() {
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("1:02:33", formatElapsed(3_753_000))
        assertEquals("2:00:05", formatElapsed(7_205_000))
    }

    @Test
    fun `a clock that ran backwards never shows a minus`() {
        assertEquals("0:00", formatElapsed(-5_000))
    }
}
