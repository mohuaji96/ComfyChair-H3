package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.workflow.TagDefaults
import sh.hnet.comfychair.workflow.TagPick

/** A default that only applies to a virgin install is not a default. */
class TagDefaultsTest {

    private val library = TagLibrary(
        categories = listOf(
            TagCategory(
                id = "toys",
                label = "性玩具",
                slots = listOf(TagSlot(id = "item", label = "", pool = listOf("束带口塞", "肛塞")))
            )
        ),
        defaultIgnored = listOf("toys"),
        version = "v2"
    )

    @Test
    fun aFreshSelectionStartsWithTheCategoryOff() {
        val out = TagDefaults.ignored(library, emptyMap())
        assertTrue(out.getValue("toys.item").ignored)
        assertEquals("", out.getValue("toys.item").value)
    }

    @Test
    fun aSavedValueDoesNotSurviveARestart() {
        val saved = mapOf("toys.item" to TagPick(value = "束带口塞"))
        val out = TagDefaults.ignored(library, saved)
        assertTrue(out.getValue("toys.item").ignored)
        assertEquals("", out.getValue("toys.item").value)
    }

    @Test
    fun aLockedChoiceOutranksTheDefault() {
        val saved = mapOf("toys.item" to TagPick(value = "肛塞", locked = true))
        val out = TagDefaults.ignored(library, saved)
        assertFalse(out.getValue("toys.item").ignored)
        assertEquals("肛塞", out.getValue("toys.item").value)
    }

    @Test
    fun otherCategoriesAreUntouched() {
        val saved = mapOf("hips.detail" to TagPick(value = "翘臀", locked = true))
        val out = TagDefaults.ignored(library, saved)
        assertEquals("翘臀", out.getValue("hips.detail").value)
    }

    /** 风格 carries a default-ignored slot of its own and must still be drawn and pinned. */
    private val mediumLibrary = TagLibrary(
        categories = listOf(
            TagCategory(
                id = "mood",
                label = "风格",
                slots = listOf(
                    TagSlot(id = "palette", label = "配色", pool = listOf("粉灰", "墨黑")),
                    TagSlot(id = "type", label = "媒介", pool = listOf("厚涂", "水彩"))
                )
            )
        ),
        defaultIgnored = listOf("mood.palette"),
        defaultLocked = listOf("mood"),
        version = "v2"
    )

    @Test
    fun aPinnedCategoryIsSeededEvenThoughOneOfItsSlotsStartsOff() {
        // The default-ignored slot is written into the map before the pin runs; judging
        // "already touched" against that map is what kept the look from ever being pinned.
        assertTrue(TagDefaults.ignored(mediumLibrary, emptyMap()).containsKey("mood.palette"))
        assertEquals(listOf("mood"), TagDefaults.lockedToSeed(mediumLibrary, emptyMap()))
    }

    @Test
    fun aSavedLookIsNotReseeded() {
        val saved = mapOf("mood.type" to TagPick(value = "厚涂", locked = true))
        assertTrue(TagDefaults.lockedToSeed(mediumLibrary, saved).isEmpty())
    }
}
