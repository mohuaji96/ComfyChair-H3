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
 * 三处只在真句里才看得出来的毛病：发型状态词挂「的」、取景裁掉还写器官、
 * 脸埋住了还写表情。
 */
class WordingAndFrameTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun engine() = TagComposerEngine(library())

    /** 「无刘海」不是发型名，不能写成「亚麻色的无刘海」。 */
    @Test
    fun hairStateWordsStandOnTheirOwn() {
        val e = engine()
        val states = library().constraints.hairState
        assertTrue("发型状态词表空了，这条测试等于没测", states.isNotEmpty())
        val color = library().category("hair")!!.slots.first { it.id == "color" }.pool.first()
        states.forEach { state ->
            val picks = mutableMapOf(
                "people.count" to TagPick("单人女孩", locked = true),
                "hair.color" to TagPick(color, locked = true),
                "hair.style" to TagPick(state, locked = true)
            )
            val prompt = e.compose(picks)
            assertFalse("写成了「${color}的$state」：$prompt", prompt.contains("${color}的$state"))
            assertTrue("${state} 没另起一段：$prompt", prompt.contains("，$state"))
        }
    }

    /** 取景写明胸部在画外，乳头、乳房这类词就不该再出现。 */
    @Test
    fun croppedChestDropsChestWords() {
        val e = engine()
        listOf("遮盖乳头", "乳头隔衣透出", "露出一边乳房").forEach { word ->
            val picks = mutableMapOf(
                "people.count" to TagPick("单人女孩", locked = true),
                "camera.crop" to TagPick("胸部在画外", locked = true),
                "garment.detail" to TagPick(word, locked = true)
            )
            val prompt = e.compose(picks)
            assertFalse("胸部在画外还写了「$word」：$prompt", prompt.contains(word))
        }
    }

    /** 脸埋进枕头，表情和妆容都看不见。 */
    @Test
    fun buriedFaceDropsExpressionAndMakeup() {
        val e = engine()
        listOf("脸埋进枕头", "把脸埋进手掌", "额头抵着床单").forEach { head ->
            val picks = mutableMapOf(
                "people.count" to TagPick("单人女孩", locked = true),
                "headPose.pose" to TagPick(head, locked = true),
                "expression.face" to TagPick("享受", locked = true)
            )
            val prompt = e.compose(picks)
            assertFalse("「$head」还写了表情：$prompt", prompt.contains("享受"))
        }
    }

    /** 一男两女时第二格空着，面板要说得清为什么。 */
    @Test
    fun theSecondPartnerSlotExplainsItself() {
        val e = engine()
        val picks = mutableMapOf("people.count" to TagPick("一男两女", locked = true))
        val blocked = e.blockedWords("partnerAct", "act2", picks)
        assertTrue(
            "第二格没有任何说明：$blocked",
            blocked.values.any { it == "一男三女才有第三位" }
        )
    }
}
