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
 * 多人时第二位、第三位女性各自一句，不再三个人共用一张脸。
 */
class MultiWomanTest {

    private fun engine(): TagComposerEngine {
        val library = TagLibrary(
            categories = listOf(
                TagCategory(
                    "people", "人数", "",
                    listOf(TagSlot("count", "人数", listOf("单人女孩", "两个女孩", "三个女孩", "一男两女")))
                ),
                TagCategory(
                    "race", "种族", "list", who = "female",
                    slots = listOf(TagSlot("kind", "种类", listOf("人类", "妖精", "魅魔", "龙女")))
                ),
                TagCategory(
                    "person", "形象", "person", who = "female",
                    slots = listOf(
                        TagSlot("look", "气质", listOf("清纯", "冷艳", "甜美", "高冷")),
                        TagSlot("age", "年龄感", listOf("十八岁", "二十八岁", "三十五岁"))
                    )
                ),
            ),
            constraints = TagConstraints(
                peopleCount = mapOf(
                    "单人女孩" to 1, "两个女孩" to 2, "三个女孩" to 3, "一男两女" to 3
                ),
                raceLead = mapOf(
                    "妖精" to "娇小身躯，透明脉络薄翼，散发微光的",
                    "魅魔" to "弯曲黑色恶魔角，小巧蝙蝠翅膀，魅惑妖娆的",
                    "龙女" to "人类女性容颜，一对微弯龙角，修长龙尾的"
                ),
                raceName = mapOf("妖精" to "妖精", "魅魔" to "魅魔", "龙女" to "龙女")
            ),
            sectionMode = true
        )
        return TagComposerEngine(library)
    }

    private fun count(text: String) = Regex("另一位是").findAll(text).count()

    @Test
    fun twoWomenGetTwoDescriptions() {
        val picks = mutableMapOf(
            "people.count" to TagPick("一男两女", locked = true),
            "race.kind" to TagPick("人类", locked = true)
        )
        val text = engine().compose(picks)
        assertEquals("一男两女要有一句第二位，实际是\n$text", 1, count(text))
    }

    @Test
    fun threeWomenGetTwoExtraLines() {
        val picks = mutableMapOf(
            "people.count" to TagPick("三个女孩", locked = true),
            "race.kind" to TagPick("人类", locked = true)
        )
        val text = engine().compose(picks)
        assertEquals("三个女孩要有两句，实际是\n$text", 2, count(text))
    }

    @Test
    fun aSoloSceneGetsNoCompanion() {
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "race.kind" to TagPick("人类", locked = true)
        )
        val text = engine().compose(picks)
        assertEquals("单人场景不该有另一位，实际是\n$text", 0, count(text))
    }

    @Test
    fun theCompanionDoesNotCopyTheFirstWoman() {
        val picks = mutableMapOf(
            "people.count" to TagPick("两个女孩", locked = true),
            "race.kind" to TagPick("妖精", locked = true),
            "person.look" to TagPick("清纯", locked = true),
            "person.age" to TagPick("二十八岁", locked = true)
        )
        val e = engine()
        val text = e.compose(picks)
        val companion = text.lines().first { it.contains("另一位是") }
        assertTrue("第二位不该还是妖精，实际是 $companion", !companion.contains("妖精"))
        assertTrue("第二位不该还是清纯，实际是 $companion", !companion.contains("清纯"))
        assertTrue("第二位不该还是二十八岁，实际是 $companion", !companion.contains("二十八岁"))
    }

    @Test
    fun theCompanionIsStableAcrossRenders() {
        val picks = mutableMapOf(
            "people.count" to TagPick("两个女孩", locked = true),
            "race.kind" to TagPick("人类", locked = true)
        )
        val e = engine()
        assertEquals("同一次选中值重绘必须一致", e.compose(picks), e.compose(picks))
    }
}
