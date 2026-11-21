package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.AnchorSet
import sh.hnet.comfychair.model.SoftProfile
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick

/**
 * The act now leads: it is drawn first and the rest of the scene has to agree with it.
 * These tests pin the three ways that shows up.
 */
class AnchorTest {

    private fun library(
        acts: List<String> = listOf("后入"),
        people: List<String> = listOf("两人"),
        posture: Map<String, String> = mapOf(
            "跪趴" to "kneel",
            "站立" to "stand",
            "仰躺" to "lie",
            "两人面对面站立" to "kneel"
        ),
        allowed: Map<String, List<String>> = mapOf(
            "后入" to listOf("kneel", "lie"),
            "躺着口交" to listOf("lie")
        ),
        anchors: AnchorSet = AnchorSet(
            families = mapOf(
                "penetrate" to SoftProfile(express = listOf("忍耐")),
                "oral" to SoftProfile(express = listOf("微张嘴"))
            ),
            of = mapOf("后入" to "penetrate", "躺着口交" to "oral"),
            addOn = listOf("外射在脸上")
        )
    ) = TagLibrary(
        categories = listOf(
            TagCategory("people", "人数", "", listOf(TagSlot("count", "人数", people))),
            TagCategory(
                "pose", "姿态", "",
                listOf(TagSlot("body", "姿势", listOf("跪趴", "站立", "仰躺", "两人面对面站立")))
            ),
            TagCategory("sexAct", "性行为", "", listOf(TagSlot("act", "行为", acts))),
            TagCategory(
                "expression", "表情", "",
                listOf(TagSlot("face", "表情", listOf("忍耐", "微张嘴", "大笑")))
            ),
        ),
        constraints = TagConstraints(
            posture = posture,
            people = mapOf(
                "跪趴" to 1, "站立" to 1, "仰躺" to 1, "两人面对面站立" to 2,
                "后入" to 2, "躺着口交" to 2, "外射在脸上" to 2
            ),
            peopleCount = mapOf("两人" to 2, "三人" to 3, "单人" to 1),
            allowedPostures = allowed,
            exactPeople = listOf("两人面对面站立")
        ),
        anchors = anchors
    )

    @Test
    fun poseStaysInsideWhatTheActAllows() {
        val engine = TagComposerEngine(library())
        repeat(80) {
            val state = mutableMapOf<String, TagPick>("people.count" to TagPick("两人"))
            engine.randomize(state)
            // 站立 is outside 后入's whitelist; 仰躺 and 跪趴 are inside it.
            assertFalse(state["pose.body"]?.value == "站立")
        }
    }

    @Test
    fun aPoseThatNamesItsHeadcountNeedsThatExactCount() {
        val engine = TagComposerEngine(library(people = listOf("三人")))
        repeat(80) {
            val state = mutableMapOf<String, TagPick>("people.count" to TagPick("三人"))
            engine.randomize(state)
            assertFalse(state["pose.body"]?.value == "两人面对面站立")
        }
    }

    @Test
    fun aPoseThatNamesTwoPeopleIsStillDrawnAtTwo() {
        var seen = false
        val engine = TagComposerEngine(library())
        repeat(80) {
            val state = mutableMapOf<String, TagPick>("people.count" to TagPick("两人"))
            engine.randomize(state)
            if (state["pose.body"]?.value == "两人面对面站立") seen = true
        }
        assertTrue("exact-count poses must remain reachable", seen)
    }

    @Test
    fun expressionFollowsTheAnchorFamily() {
        val engine = TagComposerEngine(library(acts = listOf("后入")))
        repeat(60) {
            val state = mutableMapOf<String, TagPick>("people.count" to TagPick("两人"))
            engine.randomize(state)
            assertEquals("忍耐", state["expression.face"]?.value)
        }
    }

    @Test
    fun aDifferentAnchorDragsADifferentExpression() {
        val engine = TagComposerEngine(library(acts = listOf("躺着口交")))
        repeat(60) {
            val state = mutableMapOf<String, TagPick>("people.count" to TagPick("两人"))
            engine.randomize(state)
            // 微张嘴 is oral's only expression; 忍耐 belongs to the other family.
            assertEquals("微张嘴", state["expression.face"]?.value)
            // 「躺着口交」自己就写着体位，姿势格整格让位；再补一个「仰躺」会被模型读成两个体位。
            assertTrue("动作已写明体位，姿势格该为空", state["pose.body"]?.value.isNullOrEmpty())
        }
    }
}
