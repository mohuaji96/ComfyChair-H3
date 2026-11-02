package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H3ResolutionTest {

    // min/max/step come from /object_info/MiniMaxH3ImageToVideo.
    private fun h3(w: Int, h: Int, mp: Double) = H3Resolution.size(w, h, mp, step = 32, min = 32, max = 16384)

    @Test fun everyEdgeLandsOnTheNodeGrid() {
        for ((_, r) in H3Resolution.ratios) {
            for (mp in H3Resolution.megapixelPresets) {
                val s = h3(r.first, r.second, mp)
                assertEquals("width $s", 0, s.width % 32)
                assertEquals("height $s", 0, s.height % 32)
                assertTrue("$s in range", s.width in 32..16384 && s.height in 32..16384)
            }
        }
    }

    @Test fun megapixelBudgetIsRespectedWithinTenPercent() {
        for (mp in H3Resolution.megapixelPresets) {
            val s = h3(16, 9, mp)
            val drift = kotlin.math.abs(s.megapixels - mp) / mp
            assertTrue("$mp MP -> $s drift=$drift", drift <= 0.10)
        }
    }

    @Test fun ratioSurvivesSnapping() {
        val s = h3(9, 16, 0.5)
        val ratio = s.width.toDouble() / s.height.toDouble()
        assertEquals(9.0 / 16.0, ratio, 0.05)
    }

    @Test fun customSizeCanSkipPresetsAndBeDetectedAsCustom() {
        assertEquals(0.5, H3Resolution.matchingPreset(H3Resolution.Size(928, 544))!!, 0.001)
        assertNull(H3Resolution.matchingPreset(H3Resolution.Size(1024, 1024)))
    }

    @Test fun extremaAreClampedToServerRange() {
        val tiny = H3Resolution.size(16, 9, 0.0001, step = 32, min = 32, max = 16384)
        assertEquals(32, tiny.width)
        assertEquals(32, tiny.height)
        val huge = H3Resolution.size(16, 9, 500.0, step = 32, min = 32, max = 16384)
        assertEquals(16384, huge.width)
    }

    // The chip row is built from the server's own aspect_ratio strings, so a list that grows must not need a code change.
    @Test fun ratiosComeFromTheServerAndUnknownOnesGetAChip() {
        val local = H3Resolution.fromServer(emptyList()).map { it.value }
        assertEquals(H3Resolution.ratios.map { it.first }.toSet(), local.toSet())

        val server = H3Resolution.fromServer(
            listOf("21:9 (Ultrawide)", "16:9 (Widescreen)", "5:4 (Print)", "9:16 (Portrait)")
        ).map { it.value }
        assertEquals(listOf("21:9", "16:9", "5:4", "9:16"), server)

        assertNull(H3Resolution.parseRatio("Widescreen"))
    }
}
