package sh.hnet.comfychair

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.H3Seed

/**
 * The bundled templates carry a literal seed, so two runs of one prompt produced one clip.
 * Rolling it is a single integer swap — these cases guard the two ways that swap can go wrong:
 * leaving a second seed behind, and touching something that is not a seed.
 */
class H3SeedTest {

    private val template = """
        {
          "3": {"class_type":"KSampler","inputs":{"seed":757358688076805,"steps":8}},
          "7": {"class_type":"RandomNoise","inputs":{"noise_seed":757358688076805}},
          "9": {"class_type":"EmptyLatent","inputs":{"width":1280,"height":720}}
        }
    """.trimIndent()

    @Test
    fun everySeedInTheGraphTakesANewValue() {
        val root = JSONObject(H3Seed.randomize(template)!!)
        val sampler = root.getJSONObject("3").getJSONObject("inputs")
        val noise = root.getJSONObject("7").getJSONObject("inputs")

        assertNotEquals(757358688076805L, sampler.getLong("seed"))
        assertNotEquals(757358688076805L, noise.getLong("noise_seed"))
        // A string would be rejected by the node definition; it has to stay a number.
        assertTrue(sampler.get("seed") is Number)
        assertTrue(noise.get("noise_seed") is Number)
    }

    @Test
    fun theRestOfTheGraphDoesNotMove() {
        val root = JSONObject(H3Seed.randomize(template)!!)
        assertEquals(8, root.getJSONObject("3").getJSONObject("inputs").getInt("steps"))
        assertEquals(1280, root.getJSONObject("9").getJSONObject("inputs").getInt("width"))
        assertEquals(720, root.getJSONObject("9").getJSONObject("inputs").getInt("height"))
    }

    @Test
    fun aGraphWithoutASeedIsNotRewritten() {
        assertNull(H3Seed.randomize("""{"1":{"class_type":"X","inputs":{"width":512}}}"""))
    }

    @Test
    fun aSeedFedByAWireIsLeftToItsSource() {
        // A linked seed comes from another node; replacing the wire with a literal would
        // silently cut that connection.
        assertNull(H3Seed.randomize("""{"3":{"class_type":"KSampler","inputs":{"seed":["5",0]}}}"""))
    }


    @Test
    fun aLiteGraphDocumentIsDeclined() {
        // Widget values live in positional arrays there, so the index is not knowable here.
        assertNull(H3Seed.randomize("""{"nodes":[{"id":3,"type":"KSampler"}]}"""))
    }

    @Test
    fun everyRollStaysUnderTheServerCap() {
        repeat(500) {
            val value = H3Seed.next()
            assertTrue("rolled $value", value in 0L..H3Seed.MAX)
        }
    }

    @Test
    fun aSeedFromTheWiderBuildIsRepaired() {
        // Recorded from a real run: 19 digits, and the server tossed the whole task over it.
        val fixed = H3Seed.fit("6591149278462143166")
        assertTrue("left the oversized value alone", fixed != null)
        assertTrue(fixed!!.toLong() in 0L..H3Seed.MAX)
    }

    @Test
    fun anInRangePinnedSeedIsLeftAlone() {
        assertNull(H3Seed.fit("123456789012345"))
    }

    @Test
    fun pinnedRewriteRepairsWithoutRerollingEverything() {
        val oversized = """{"1":{"class_type":"easy seed","inputs":{"seed":6591149278462143166}}}"""
        val fixed = H3Seed.randomize(oversized, auto = false)!!
        val value = JSONObject(fixed).getJSONObject("1").getJSONObject("inputs").getLong("seed")
        assertTrue(value in 0L..H3Seed.MAX)

        val fine = """{"1":{"class_type":"easy seed","inputs":{"seed":123456789012345}}}"""
        assertNull(H3Seed.randomize(fine, auto = false))
    }
}
