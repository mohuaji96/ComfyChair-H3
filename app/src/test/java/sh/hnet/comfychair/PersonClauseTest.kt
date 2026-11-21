package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick

/**
 * 人物句：妆容，年龄的国籍气质女名词。
 *
 * 碎片并列时「二十八岁」没有归属；这句要保证不管人类还是异族，都以一个名词收尾。
 * 身份删掉以后人类一律以 女孩 / 女性 收尾，异族仍以种族名收尾。
 */
class PersonClauseTest {

    private fun engine(): TagComposerEngine {
        val library = TagLibrary(
            categories = listOf(
                TagCategory("people", "人数", "", listOf(TagSlot("count", "人数", listOf("单人女孩")))),
                TagCategory(
                    "race", "种族", "list", who = "female",
                    slots = listOf(
                        TagSlot("kind", "种类", listOf("人类", "妖精", "美人鱼", "龙女")),
                        TagSlot("ethnicity", "地域", listOf("韩国"))
                    )
                ),
                TagCategory(
                    "person", "形象", "person", who = "female",
                    slots = listOf(
                        TagSlot("face", "妆容", listOf("烟熏妆")),
                        TagSlot("look", "气质", listOf("清纯")),
                        TagSlot("age", "年龄感", listOf("二十八岁", "二十岁出头"))
                    )
                ),
                TagCategory("top", "上衣", "list", slots = listOf(TagSlot("type", "款式", listOf("衬衫")))),
            ),
            constraints = TagConstraints(
                people = mapOf("单人女孩" to 1),
                raceLead = mapOf(
                    "妖精" to "娇小身躯，透明脉络薄翼，散发微光的",
                    "美人鱼" to "有着人类上半身，耳后半透明鱼鳍，修长泛着蓝绿鳞片鱼尾，"
                ),
                raceName = mapOf("妖精" to "妖精", "美人鱼" to "美人鱼", "龙女" to "龙女")
            ),
            sectionMode = true
        )
        return TagComposerEngine(library)
    }

    private fun prompt(kind: String, age: String = "二十八岁"): String {
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "race.kind" to TagPick(kind, locked = true),
            "race.ethnicity" to TagPick("韩国", locked = true),
            "person.age" to TagPick(age, locked = true),
            "person.look" to TagPick("清纯", locked = true)
        )
        return engine().compose(picks)
    }

    @Test
    fun humanEndsOnTheAgeNoun() {
        val text = prompt("人类")
        assertTrue("实际是 $text", text.contains("二十八岁的韩国清纯女孩"))
    }

    @Test
    fun fantasyInsertsThePersonWordsBeforeTheRaceName() {
        val text = prompt("妖精")
        assertTrue("实际是 $text", text.contains("娇小身躯，透明脉络薄翼，散发微光的二十八岁韩国清纯女妖精"))
    }

    @Test
    fun aRaceWhoseLeadEndsOnACommaTakesThePossessive() {
        val text = prompt("美人鱼")
        assertTrue("实际是 $text", text.contains("鱼尾，二十八岁的韩国清纯女美人鱼"))
    }

    @Test
    fun agePicksTheNoun() {
        val young = prompt("人类", age = "二十岁出头")
        assertTrue("实际是 $young", young.contains("二十岁出头的韩国清纯女孩"))
        val grown = prompt("人类", age = "三十岁")
        assertTrue("实际是 $grown", grown.contains("三十岁的韩国清纯女性"))
    }

    /** 「龙女」自带「女」，前面再加一个就成了「女龙女」。 */
    @Test
    fun aNounThatAlreadySaysFemaleDoesNotTakeAnother() {
        val text = prompt("龙女")
        assertFalse("不该出现 女龙女，实际是 $text", text.contains("女龙女"))
        assertTrue("实际是 $text", text.contains("二十八岁的韩国清纯龙女"))
    }

    /** 气质以「女」结尾时，接「女性」会叠成「熟女女性」。 */
    @Test
    fun aWomanEndingLookTakesOverTheNoun() {
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "race.kind" to TagPick("人类", locked = true),
            "person.age" to TagPick("三十岁", locked = true),
            "person.look" to TagPick("熟女", locked = true)
        )
        val text = engine().compose(picks)
        assertFalse("叠成了熟女女性：$text", text.contains("熟女女性"))
        assertTrue("实际是 $text", text.contains("三十岁的熟女"))
    }

    /** 接「龙女」这类带女的名词，用「般的」隔开。 */
    @Test
    fun aWomanEndingLookBridgesToAFemaleNoun() {
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "race.kind" to TagPick("龙女", locked = true),
            "person.age" to TagPick("三十岁", locked = true),
            "person.look" to TagPick("绝世美女", locked = true)
        )
        val text = engine().compose(picks)
        assertFalse("叠成了绝世美女龙女：$text", text.contains("美女龙女"))
        assertTrue("实际是 $text", text.contains("绝世美女般的龙女"))
    }

    /** 妆容并进形象以后仍在人物句最前面。 */
    @Test
    fun makeupLeadsTheSentence() {
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "race.kind" to TagPick("人类", locked = true),
            "person.age" to TagPick("二十八岁", locked = true),
            "person.face" to TagPick("烟熏妆", locked = true)
        )
        val text = engine().compose(picks)
        assertTrue("妆容要排在人物句最前，实际是 $text", text.contains("烟熏妆，二十八岁的女孩"))
        assertEquals(true, text.lines().any { it.trimStart().startsWith("烟熏妆") })
    }
}
