package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagPick
import sh.hnet.comfychair.workflow.TagComposerEngine
import java.io.File

/**
 * Accessories share one head, so two of them may not take the same spot.
 *
 * 面纱, 眼镜 and 口塞 sit in three different categories; before this, a draw could put all three
 * on one face.
 */
class FaceZoneTest {

    private fun engine(json: String): TagComposerEngine {
        val lib = TagLibraryJson.parse(json)
        val solo = lib.constraints.peopleCount.filterValues { it <= 1 }.keys
        return TagComposerEngine(lib, if (solo.isEmpty()) setOf("单人") else solo)
    }

    private fun library(vararg categories: String, zones: String) = engine(
        """{"schemaVersion":1,
            "categories":[${categories.joinToString(",")}],
            "constraints":{
              "peopleCount":{"单人":1},
              "faceZone":{$zones}
            }}"""
    )

    private fun category(id: String, word: String) =
        """{"id":"$id","label":"$id","slots":[{"id":"item","label":"item","pool":["$word"]}]}"""

    @Test
    fun twoSpotsTwoAccessories() {
        val engine = library(
            category("eyewear", "黑框眼镜"),
            category("toys", "口塞球"),
            zones = """"黑框眼镜":"eyes","口塞球":"mouth""""
        )
        repeat(20) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            val values = picks.values.map { it.value }.filter { it.isNotEmpty() }
            // Different spots, so both belong on the face.
            assertTrue("only got $values", values.contains("黑框眼镜"))
            assertTrue("only got $values", values.contains("口塞球"))
        }
    }

    @Test
    fun aYashmakPushesTheGlassesOff() {
        val engine = library(
            category("headwear", "面纱"),
            category("eyewear", "黑框眼镜"),
            zones = """"面纱":"all","黑框眼镜":"eyes""""
        )
        repeat(20) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            val values = picks.values.map { it.value }.filter { it.isNotEmpty() }
            assertFalse("both on one face: $values", values.contains("面纱") && values.contains("黑框眼镜"))
        }
    }

    @Test
    fun twoPairsOfGlassesCannotShareTheEyes() {
        val engine = library(
            category("eyewear", "圆框眼镜"),
            category("toys", "护目镜"),
            zones = """"圆框眼镜":"eyes","护目镜":"eyes""""
        )
        repeat(20) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            val values = picks.values.map { it.value }.filter { it.isNotEmpty() }
            assertFalse("two pairs at once: $values", values.contains("圆框眼镜") && values.contains("护目镜"))
        }
    }

    @Test
    fun theShippedLibraryTagsItsAccessories() {
        val file = File("src/main/assets/tag_catalog.json")
        val lib = TagLibraryJson.parse(file.readText(Charsets.UTF_8))
        val zones = lib.constraints.faceZone
        assertTrue(zones.isNotEmpty())
        assertEqualsZone(zones, "面纱", "all")
        assertEqualsZone(zones, "黑框眼镜", "eyes")
        assertEqualsZone(zones, "口塞球", "mouth")
        // Words that mean "wearing none" must never take a spot.
        assertFalse(zones.containsKey("无头饰"))
        assertFalse(zones.containsKey("无帽"))
        // 推到头顶的眼镜不再盖住眼睛（写法已改成名词短语）。
        assertEquals("head", zones["架在头顶的眼镜"])
    }

    private fun assertEqualsZone(zones: Map<String, String>, word: String, expected: String) {
        org.junit.Assert.assertEquals(word, expected, zones[word])
    }
}
