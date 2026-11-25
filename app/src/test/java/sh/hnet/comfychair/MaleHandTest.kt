package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * His hands were the one thing the library never spoke about, so every scene with a man in it
 * left them to the model. Two slots now describe them, and all four gates have to hold: a man in
 * frame, the region still visible, her own hand not already there, and the act not already
 * saying the same thing.
 */
class MaleHandTest {

    private fun libraryText(): String? = listOf(
        File("src/main/assets/tag_catalog.json"),
        File("app/src/main/assets/tag_catalog.json")
    ).firstOrNull { it.exists() }?.readText(Charsets.UTF_8)

    private fun library(): TagLibrary {
        val text = libraryText()
        assumeTrue("catalog asset not reachable", text != null)
        return TagLibraryJson.parse(text!!)
    }

    private fun engine(): TagComposerEngine = TagComposerEngine(library())

    private fun handCategory(lib: TagLibrary) = lib.categories.first { it.id == "maleHand" }

    @Test
    fun everyHandWordNeedsAManAndDeclaresWhereItLives() {
        val lib = library()
        val category = handCategory(lib)
        assertEquals(listOf("chest", "below"), category.slots.map { it.id })
        val words = category.slots.flatMap { it.pool }
        assertEquals(32, words.size)

        val unguarded = words.filter { it !in lib.constraints.maleOnly }
        assertTrue("an all-female cast could still draw: $unguarded", unguarded.isEmpty())

        // Without a region a hand can reach into a part the framing already cut away.
        val placeless = words.filter { lib.constraints.regions[it].isNullOrEmpty() }
        assertTrue("no region declared: $placeless", placeless.isEmpty())

        assertEquals("male", category.who)
    }

    @Test
    fun anAllFemaleCastNeverDrawsHisHands() {
        val engine = engine()
        repeat(30) {
            val picks = mutableMapOf("people.count" to TagPick("单人女孩", locked = true))
            engine.randomize(picks)
            listOf("chest", "below").forEach { slot ->
                val value = picks["maleHand.$slot"]?.value.orEmpty()
                assertTrue("all-female cast drew 他的手.$slot = $value", value.isEmpty())
            }
        }
    }

    @Test
    fun aFramingThatCutsTheBodyAlsoCutsTheHand() {
        val engine = engine()
        listOf(
            "胸部在画外" to "chest",
            "下半身在画外" to "below"
        ).forEach { (crop, slot) ->
            repeat(20) {
                val picks = mutableMapOf(
                    "people.count" to TagPick("一男一女", locked = true),
                    "camera.shot" to TagPick(crop, locked = true)
                )
                engine.randomize(picks, onlyCategory = "maleHand")
                val value = picks["maleHand.$slot"]?.value.orEmpty()
                assertTrue("$crop still drew $value", value.isEmpty())
            }
        }
    }

    @Test
    fun herOwnHandOnHerChestKeepsHisOff() {
        val engine = engine()
        repeat(20) {
            val picks = mutableMapOf(
                "people.count" to TagPick("一男一女", locked = true),
                "handAction.hand" to TagPick("手按胸口", locked = true)
            )
            engine.randomize(picks, onlyCategory = "maleHand")
            val value = picks["maleHand.chest"]?.value.orEmpty()
            assertTrue("both hands landed on her chest: $value", value.isEmpty())
        }
    }

    @Test
    fun theActIsNotSaidTwiceButTheOtherHandStillSpeaks() {
        val engine = engine()
        val picks = mutableMapOf(
            "people.count" to TagPick("一男一女", locked = true),
            "sexAct.act" to TagPick("手指进入", locked = true),
            "maleHand.chest" to TagPick("揉着她的乳房", locked = true),
            "maleHand.below" to TagPick("探进她体内", locked = true)
        )
        val text = engine.compose(picks)
        assertFalse("the act was repeated by his hand: $text", text.contains("探进她体内"))
        assertTrue("his other hand was dropped too: $text", text.contains("揉着她的乳房"))
    }

    @Test
    fun twoSlotsReadAsTwoHands() {
        val engine = engine()
        val picks = mutableMapOf(
            "people.count" to TagPick("一男一女", locked = true),
            "maleHand.chest" to TagPick("揉着她的乳房", locked = true),
            "maleHand.below" to TagPick("按着她的阴部", locked = true)
        )
        val text = engine.compose(picks)
        assertTrue(
            "expected 一只手…另一只手…: $text",
            text.contains("一只手揉着她的乳房，另一只手按着她的阴部")
        )
    }
}
