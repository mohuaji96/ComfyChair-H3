package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick

/**
 * A category the player switched off must survive a reroll. Drawing a hidden value behind the
 * ignore flag looks harmless until the flag is cleared and an unasked-for word appears.
 */
class IgnoredSlotTest {

    private fun engine(): TagComposerEngine = TagComposerEngine(
        TagLibrary(
            categories = listOf(
                TagCategory("people", "人数", "", listOf(TagSlot("count", "人数", listOf("单人")))),
                TagCategory("toys", "性玩具", "", listOf(TagSlot("props", "道具", listOf("皮鞭", "拘束椅")))),
                TagCategory("mood", "风格", "", listOf(TagSlot("medium", "媒介", listOf("写实照片", "水墨")))),
            ),
            constraints = TagConstraints(people = mapOf("单人" to 1))
        )
    )

    @Test
    fun ignoredSlotIsLeftAlone() {
        repeat(30) {
            val picks = mutableMapOf(
                "people.count" to TagPick("单人", locked = true),
                "toys.props" to TagPick("", ignored = true)
            )
            engine().randomize(picks)
            assertEquals("", picks["toys.props"]?.value.orEmpty())
            assertTrue(picks["toys.props"]?.ignored == true)
        }
    }

    @Test
    fun clearingTheFlagLetsItDrawAgain() {
        val picks = mutableMapOf(
            "people.count" to TagPick("单人", locked = true),
            "toys.props" to TagPick("", ignored = true)
        )
        engine().randomize(picks)
        assertEquals("", picks["toys.props"]?.value.orEmpty())

        picks["toys.props"] = TagPick("")
        engine().randomize(picks)
        assertTrue(picks["toys.props"]?.value.orEmpty().isNotEmpty())
    }

    @Test
    fun lockedSlotKeepsItsValueAcrossRerolls() {
        val picks = mutableMapOf(
            "people.count" to TagPick("单人", locked = true),
            "mood.medium" to TagPick("写实照片", locked = true)
        )
        repeat(30) {
            engine().randomize(picks)
            assertEquals("写实照片", picks["mood.medium"]?.value.orEmpty())
        }
    }
}
