package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.RequirementFiller
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * Tapping 只穿裤子 without a pair of trousers used to cancel silently, which reads as a dead
 * chip. The filler puts the missing half in place instead.
 */
class RequirementFillerTest {

    private fun library() = TagLibraryJson.parse(
        listOf(
            File("src/main/assets/tag_catalog.json"),
            File("app/src/main/assets/tag_catalog.json")
        ).first { it.exists() }.readText(Charsets.UTF_8)
    )

    private fun nothingBlocked(categoryId: String, slotId: String) = emptyMap<String, String>()

    @Test
    fun trousersOnlyWordPullsInATrouser() {
        val lib = library() ?: return
        val needs = lib.constraints.requires["只穿裤子"].orEmpty()
        assertTrue(needs.isNotEmpty())
        val fill = RequirementFiller.find(lib, needs, "outfitState", emptyMap()) { c, s -> nothingBlocked(c, s) }
        assertTrue("expected a fill, got none", fill != null)
        assertEquals("bottom", fill!!.slotKey.substringBefore('.'))
        assertTrue(fill.word, fill.word.contains("裤"))
    }

    @Test
    fun hoodieWordReachesTheTopSlotItNames() {
        val lib = library() ?: return
        val needs = lib.constraints.requires["卫衣底下没穿内衣"].orEmpty()
        val fill = RequirementFiller.find(lib, needs, "outfitState", emptyMap()) { c, s -> nothingBlocked(c, s) }
        assertTrue("expected a fill for 卫衣", fill != null)
        assertEquals("top", fill!!.slotKey.substringBefore('.'))
        assertTrue(fill.word, fill.word.contains("卫衣"))
    }

    @Test
    fun anOccupiedSlotIsNotOverwritten() {
        val lib = library() ?: return
        val needs = lib.constraints.requires["只穿裤子"].orEmpty()
        val picks = mapOf("bottom.type" to TagPick("紧身牛仔裤"))
        val fill = RequirementFiller.find(lib, needs, "outfitState", picks) { c, s -> nothingBlocked(c, s) }
        assertNull("already satisfied, nothing to fill", fill)
    }

    @Test
    fun aLockedSlotIsLeftAlone() {
        val lib = library() ?: return
        val needs = lib.constraints.requires["只穿裤子"].orEmpty()
        val picks = mapOf("bottom.type" to TagPick("短裙", locked = true))
        val fill = RequirementFiller.find(lib, needs, "outfitState", picks) { c, s -> nothingBlocked(c, s) }
        assertNull(fill)
    }

    @Test
    fun aNeedNothingCanSupplyReturnsNull() {
        val lib = library() ?: return
        val fill = RequirementFiller.find(lib, listOf("这个词库里没有"), "outfitState", emptyMap()) { c, s ->
            nothingBlocked(c, s)
        }
        assertNull(fill)
    }

    @Test
    fun blockedCandidatesAreSkipped() {
        val lib = library() ?: return
        val needs = lib.constraints.requires["只穿裤子"].orEmpty()
        val fill = RequirementFiller.find(lib, needs, "outfitState", emptyMap()) { categoryId, slotId ->
            val slot = lib.category(categoryId)?.slots?.firstOrNull { it.id == slotId }
            if (categoryId != "bottom" || slot == null) emptyMap() else slot.pool.associateWith { "不许" }
        }
        assertNull("every candidate is blocked, so nothing can be filled", fill)
    }
}
