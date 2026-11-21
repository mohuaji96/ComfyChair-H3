package sh.hnet.comfychair

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
 * The board used to collapse to a dozen slots after a few rerolls, most often when the act
 * was a restraint. `fitsHandBudget` added up the cost of every hand-using tag and then made
 * even a zero-cost candidate pay for the overflow, so one over-budget pair rejected every
 * word in the library — 巨乳, 黑色眼睛, 丁字裤 included — and the sweep drew nothing.
 */
class DrawStabilityTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun solo(library: TagLibrary): TagComposerEngine =
        TagComposerEngine(library, library.constraints.peopleCount.filterValues { it <= 1 }.keys)

    private fun filled(picks: Map<String, TagPick>) =
        picks.count { it.value.value.isNotEmpty() && !it.value.ignored }

    /** Same seed a fresh install goes through: 风格 is default-locked and drawn on its own. */
    private fun seeded(library: TagLibrary): MutableMap<String, TagPick> {
        val engine = solo(library)
        val picks = mutableMapOf<String, TagPick>()
        library.defaultLocked.forEach { categoryId ->
            engine.randomize(picks, categoryId)
            library.category(categoryId)?.slots?.forEach { slot ->
                val key = "$categoryId.${slot.id}"
                picks[key] = (picks[key] ?: TagPick()).copy(locked = true, ignored = false)
            }
        }
        engine.randomize(picks)
        return picks
    }

    @Test
    fun fortyRerollsNeverEmptyTheBoard() {
        val library = library()
        val picks = seeded(library)
        val engine = solo(library)
        repeat(40) { round ->
            engine.randomize(picks)
            val now = filled(picks)
            assertTrue("round ${round + 1} left only $now filled: ${engine.drawNote()}", now >= 20)
        }
    }

    /**
     * A restraint costs both hands, and a hand action on top pushes the total past the cap.
     * That combination must not make the rest of the picture undescribable.
     */
    @Test
    fun aBoundActWithAFullHandBudgetStillFillsTheBody() {
        val library = library()
        val picks = seeded(library)
        picks["sexAct.act"] = TagPick("绳缚", locked = true)
        picks["handAction.hand"] = TagPick("咬手指", locked = true)

        val engine = solo(library)
        engine.randomize(picks)

        // No per-category assertion here on purpose: a crop word such as 只露出肩部 removes the
        // chest by design, and 全裸 switches the outfit categories off. The wipe this guards
        // against left 9-13 slots, so the board as a whole is the honest signal.
        val now = filled(picks)
        assertTrue("only $now filled: ${engine.drawNote()}", now >= 20)
        assertTrue("拘束行为被换掉了", picks["sexAct.act"]?.value == "绳缚")
    }

    /** 人数 blank used to switch the head-count gate off entirely, so 单人 could show a group pose. */
    @Test
    fun aBlankHeadCountStillGatesGroupPosesOnTheSoloTab() {
        val library = library()
        val picks = seeded(library)
        // Locked and empty: the sweep leaves it alone, so the tally stays blank for the draw.
        picks["people.count"] = TagPick("", locked = true)

        solo(library).randomize(picks)

        val pose = picks["pose.body"]?.value.orEmpty()
        val needed = library.constraints.people[pose] ?: 1
        assertTrue("单人页抽到了「$pose」（需要 $needed 人）", needed <= 1)
    }
}
