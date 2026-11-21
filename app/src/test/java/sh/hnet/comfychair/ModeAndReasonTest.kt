package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.workflow.RecentWords
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick

/**
 * 增强模式 vs 普通模式, plus the two diagnostics that make the choice usable.
 *
 * The point of 普通模式 is not prettier pictures: it is the escape hatch for a wrong 拦. That only
 * works if the panel can say which guard fired, so the reason and the switch ship together.
 */
class ModeAndReasonTest {

    /** One act and one hand action, each wanting the single hand the budget allows. */
    private fun squeezed(): TagLibrary = TagLibrary(
        categories = listOf(
            TagCategory(
                id = "sexAct",
                label = "性行为",
                tier = 9,
                slots = listOf(TagSlot(id = "act", label = "性行为", pool = listOf("动作甲")))
            ),
            TagCategory(
                id = "handAction",
                label = "手部动作",
                tier = 8,
                slots = listOf(TagSlot(id = "gesture", label = "动作", pool = listOf("用手甲")))
            )
        ),
        constraints = TagConstraints(handCap = 1, handCost = mapOf("动作甲" to 1, "用手甲" to 1))
    )

    private fun draw(strict: Boolean): Map<String, TagPick> {
        val picks = mutableMapOf<String, TagPick>()
        TagComposerEngine(squeezed(), emptySet(), strict = strict).randomize(picks)
        return picks
    }

    @Test
    fun strictModeHonoursTheHandBudget() {
        val filled = draw(strict = true).values.count { it.value.isNotEmpty() }
        assertEquals("一只手只够一个动作", 1, filled)
    }

    @Test
    fun plainModeLetsTheSameTwoWordsCoexist() {
        val filled = draw(strict = false).values.count { it.value.isNotEmpty() }
        assertEquals("普通模式不再算手够不够", 2, filled)
    }

    /** 普通模式 still refuses two postures: that is a contradiction between words, not physics. */
    @Test
    fun plainModeStillRefusesTwoPostures() {
        val lib = TagLibrary(
            categories = listOf(
                TagCategory(
                    id = "pose",
                    label = "姿势",
                    tier = 1,
                    slots = listOf(
                        TagSlot(id = "a", label = "姿势", pool = listOf("站立")),
                        TagSlot(id = "b", label = "姿势二", pool = listOf("躺下"))
                    )
                )
            ),
            constraints = TagConstraints(posture = mapOf("站立" to "直立", "躺下" to "平躺"))
        )
        val picks = mutableMapOf<String, TagPick>()
        TagComposerEngine(lib, emptySet(), strict = false).randomize(picks)
        val filled = picks.values.map { it.value }.filter { it.isNotEmpty() }
        assertEquals("两个姿势组仍然只能出一个", 1, filled.size)
    }

    @Test
    fun theReasonNamesTheGuardThatEmptiedTheSlot() {
        val lib = squeezed()
        val picks = mutableMapOf(
            "sexAct.act" to TagPick("动作甲", locked = true)
        )
        TagComposerEngine(lib, emptySet(), strict = true).randomize(picks)
        val reason = TagComposerEngine(lib, emptySet(), strict = true)
            .slotBlockReason("handAction", "gesture", picks)
        assertNotNull("手被占满时应该能说出原因", reason)
        assertTrue("原因应该指向手部预算: $reason", reason!!.contains("手"))
    }

    @Test
    fun anOpenSlotHasNothingToExplain() {
        val lib = squeezed()
        val engine = TagComposerEngine(lib, emptySet(), strict = true)
        assertNull(engine.slotBlockReason("handAction", "gesture", emptyMap()))
    }

    @Test
    fun plainModeSaysSoInsteadOfBlamingAGuard() {
        val lib = squeezed()
        val picks = mutableMapOf("sexAct.act" to TagPick("动作甲", locked = true))
        TagComposerEngine(lib, emptySet(), strict = false).randomize(picks)
        val reason = TagComposerEngine(lib, emptySet(), strict = false)
            .slotBlockReason("handAction", "gesture", picks)
        assertTrue("普通模式下不该报校验: $reason", reason == null || reason!!.contains("普通模式"))
    }

    @Test
    fun aJustUsedWordLosesWeightButStaysReachable() {
        val recent = RecentWords()
        repeat(3) { recent.remember("grade.feel", "暗调胶片") }
        assertTrue("刚用过的词要降权", recent.weightOf("grade.feel", "暗调胶片") < 1.0)
        assertEquals("没用过的词保持满权重", 1.0, recent.weightOf("grade.feel", "青橙电影调色"), 0.0001)
        assertEquals("别的槽不受影响", 1.0, recent.weightOf("grade.tone", "暗调胶片"), 0.0001)
    }

    /** A greyed word and a blocked draw must come from the same rule set, or the panel lies. */
    @Test
    fun blockedWordsMatchWhatTheDrawRefuses() {
        val lib = squeezed()
        val picks = mutableMapOf("sexAct.act" to TagPick("动作甲", locked = true))
        val blocked = TagComposerEngine(lib, emptySet(), strict = false)
            .blockedWords("handAction", "gesture", picks)
        // 用手甲 only collides on the hand budget, which is a feasibility check, not a contradiction.
        assertTrue("手部预算不该灰词: $blocked", blocked.isEmpty())
    }

    @Test
    fun aContradictingWordIsGreyedWithAReason() {
        val lib = TagLibrary(
            categories = listOf(
                TagCategory(
                    id = "gaze",
                    label = "视线",
                    tier = 1,
                    slots = listOf(TagSlot(id = "g", label = "视线", pool = listOf("看镜头")))
                ),
                TagCategory(
                    id = "pose",
                    label = "姿势",
                    tier = 2,
                    slots = listOf(TagSlot(id = "p", label = "姿势", pool = listOf("背面视角", "正面视角")))
                )
            ),
            constraints = TagConstraints(excludes = listOf(listOf("看镜头", "背面视角")))
        )
        val picks = mutableMapOf("gaze.g" to TagPick("看镜头", locked = true))
        val blocked = TagComposerEngine(lib, emptySet(), strict = true).blockedWords("pose", "p", picks)
        assertEquals("只有背面对撞镜头", 1, blocked.size)
        assertTrue("要说清为什么", blocked.getValue("背面视角").isNotEmpty())
    }
}
