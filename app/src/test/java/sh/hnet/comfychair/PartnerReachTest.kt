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
 * 整根没入时，根部在她体内，第三个人够不到。
 *
 * 曾经出过「蹲着坐下去，阴茎插入」和「另一名女性含着他的阴茎根部」同框的句子：
 * 前者写的是坐到最深处，后者要求根部还露在外面。
 */
class PartnerReachTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun engine(): TagComposerEngine = TagComposerEngine(library())

    /** 要碰到根部或入口，前提是根部还在外面。 */
    private val rootWords = setOf("含着他的阴茎根部", "扶着阴茎往里送")

    private fun draw(act: String, rounds: Int): List<String> {
        val e = engine()
        val seen = mutableListOf<String>()
        repeat(rounds) {
            val picks = mutableMapOf(
                "people.count" to TagPick("一男两女", locked = true),
                "sexAct.act" to TagPick(act, locked = true)
            )
            e.randomize(picks)
            seen += picks["partnerAct.act"]?.value.orEmpty()
        }
        return seen
    }

    @Test
    fun deepInsertionLeavesNoRootToHold() {
        val seen = draw("蹲着坐下去，阴茎插入", 200)
        val bad = seen.filter { it in rootWords }
        assertTrue("整根没入时还写了根部，${bad.size}/200：${bad.toSet()}", bad.isEmpty())
        assertTrue("第三人动作一次都没抽到，这条测试等于没测", seen.any { it.isNotEmpty() })
    }

    /** 锁定格不会被重抽：出口这一道兜底要把残留的那句吃掉。 */
    @Test
    fun aLockedLeftoverNeverReachesThePrompt() {
        val e = engine()
        val picks = mutableMapOf(
            "people.count" to TagPick("一男两女", locked = true),
            "sexAct.act" to TagPick("蹲着坐下去，阴茎插入", locked = true),
            "partnerAct.act" to TagPick("含着他的阴茎根部", locked = true)
        )
        val prompt = e.compose(picks)
        assertFalse("锁住的残缺和整根没入同时写进了提示词：$prompt", prompt.contains("含着他的阴茎根部"))
    }

    /** 词池里每一条都要真的写进句子，否则加了词等于没加。 */
    @Test
    fun everyPartnerWordReachesThePrompt() {
        val e = engine()
        val pool = library().category("partnerAct")!!.slots.first().pool
        val silent = pool.filter { word ->
            val picks = mutableMapOf(
                "people.count" to TagPick("一男两女", locked = true),
                "sexAct.act" to TagPick("正面进入", locked = true),
                "partnerAct.act" to TagPick(word, locked = true)
            )
            !e.compose(picks).contains(word)
        }
        assertTrue("这些词没进提示词：$silent", silent.isEmpty())
    }

    /** 一男三女：两位旁观女性各做一件事，且不能是同一句。 */
    @Test
    fun threeWomenEachGetAnAction() {
        val e = engine()
        repeat(20) {
            val picks = mutableMapOf(
                "people.count" to TagPick("一男三女", locked = true),
                "sexAct.act" to TagPick("正面进入", locked = true)
            )
            e.randomize(picks)
            val first = picks["partnerAct.act"]?.value.orEmpty()
            val second = picks["partnerAct.act2"]?.value.orEmpty()
            assertTrue("一男三女还有人在闲着：$first / $second", first.isNotEmpty() && second.isNotEmpty())
            assertTrue("两个人抽到了同一句：$first", first != second)
            val prompt = e.compose(picks)
            assertTrue("两句没都写进去：$prompt", prompt.contains(first) && prompt.contains(second))
        }
    }

    /** 一男两女只有一位旁观者，第二格必须留空。 */
    @Test
    fun twoWomenLeaveTheSecondSlotEmpty() {
        val e = engine()
        repeat(30) {
            val picks = mutableMapOf(
                "people.count" to TagPick("一男两女", locked = true),
                "sexAct.act" to TagPick("正面进入", locked = true)
            )
            e.randomize(picks)
            assertTrue(
                "两人场景多出了一个旁观者：${picks["partnerAct.act2"]?.value}",
                picks["partnerAct.act2"]?.value.orEmpty().isEmpty()
            )
        }
    }

    /** 口交没有交合处，舔交合处、扶着往里送都不成立。 */
    @Test
    fun oralLeavesNoJunctionToLick() {
        val seen = draw("口交", 200)
        val bad = seen.filter { it in setOf("扶着阴茎往里送", "凑过来舔着交合的地方") }
        assertTrue("口交时还写了交合处，${bad.size}/200：${bad.toSet()}", bad.isEmpty())
    }

    @Test
    fun shallowInsertionStillAllowsTheRoot() {
        val seen = draw("正面进入", 200)
        assertTrue("正面进入时根部还露着，应该抽得到「含着」", seen.any { it in rootWords })
    }
}
