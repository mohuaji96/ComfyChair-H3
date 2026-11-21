package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 风格 is one sentence per medium, and the sentence stays out of the colour and light business.
 *
 * The old shape was four fragments ("赛璐璐上色", "高光色相偏移") stitched with commas; they read
 * as a checklist and the encoder answered them loosely. The four still exist as ideas, but as
 * three separate layers: 风格 says how it is drawn, 调色 / 主色 say what colours, 光质 / 光位 say
 * where the light is. Repeating all three inside the sentence is what produced "黑白胶片" plus
 * "主色只用朱红与暗金，其余压成灰阶".
 */
class StylePromptTest {

    private fun library(): TagLibrary {
        val file = listOf(
            File("src/main/assets/tag_catalog.json"),
            File("app/src/main/assets/tag_catalog.json")
        ).first { it.exists() }
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun compose(lib: TagLibrary, vararg locked: Pair<String, String>): String =
        composeStrict(lib, true, *locked)

    private fun composeStrict(lib: TagLibrary, strict: Boolean, vararg locked: Pair<String, String>): String {
        val engine = TagComposerEngine(lib, strict = strict)
        val picks = mutableMapOf<String, TagPick>()
        locked.forEach { (key, value) -> picks[key] = TagPick(value, locked = true) }
        engine.randomize(picks)
        return engine.compose(picks)
    }

    /** Colour words and named light sources. Neither may come back inside a style sentence. */
    private val colourWords = listOf(
        "暖黄", "青绿", "蓝紫", "洋红", "深黑", "酒红", "古金", "墨绿",
        "橙", "棕", "奶油", "紫", "灰阶", "黑白", "暖调", "冷调", "粉"
    )
    private val lightWords = listOf(
        "烛光", "窗侧光", "直闪", "侧光", "逆光", "顶灯", "台灯",
        "闪光灯", "聚光", "黄金时刻", "夕阳", "月光", "车灯", "屏幕光", "光斑"
    )

    @Test
    fun everyMediumShipsAFullSentence() {
        val lib = library()
        val mediums = lib.category("mood")!!.slots.first { it.id == "medium" }.pool
        assertEquals("风格数量变了，确认过才改这个数", 33, mediums.size)
        mediums.forEach { medium ->
            val sentence = lib.stylePrompts[medium]
            assertTrue("$medium 没有整句", sentence != null)
            assertTrue("$medium 的整句太短: $sentence", sentence!!.length >= 60)
            assertTrue("$medium 的整句没提到自己: $sentence", sentence.contains(medium))
        }
        assertEquals("stylePrompts 里有池子里没有的词", mediums.toSet(), lib.stylePrompts.keys)
    }

    @Test
    fun noSentenceNamesAColourOrALightSource() {
        val lib = library()
        val offenders = mutableListOf<String>()
        lib.stylePrompts.forEach { (medium, sentence) ->
            // 霓虹夜色赛博人像 carries 霓虹 in its own name; that is the name, not a cue.
            val body = sentence.replace(medium, "")
            (colourWords + lightWords).forEach { token ->
                if (token in body) offenders += "$medium -> $token"
            }
        }
        assertTrue("风格句里又出现了颜色或光源: $offenders", offenders.isEmpty())
    }

    @Test
    fun theGroupsCoverThePoolExactly() {
        val lib = library()
        val pool = lib.category("mood")!!.slots.first { it.id == "medium" }.pool
        val grouped = lib.styleGroups.flatMap { it.words }
        assertEquals("分组有重复", grouped.size, grouped.toSet().size)
        assertEquals("分组和候选池对不上", pool.toSet(), grouped.toSet())
        assertEquals("应该是三组", 3, lib.styleGroups.size)
    }

    @Test
    fun theSentenceLeadsThePrompt() {
        val lib = library()
        val sentence = lib.stylePrompts.getValue("日式动漫")
        val text = compose(lib, "mood.medium" to "日式动漫")
        val head = text.lineSequence().first()
        assertTrue("第一行没有整句: $head", head.contains(sentence))
        // 粗糙向的风格（私房业余、低保真DV）故意不写「高品质」，所以只看「一幅」。
        assertTrue("第一行应该以整句开头: $head", head.startsWith("一幅"))
    }

    @Test
    fun colourAndLightFollowTheSentenceOnTheSameLine() {
        val lib = library()
        val text = compose(
            lib,
            "mood.medium" to "日式动漫",
            "grade.feel" to "日系淡色低饱和",
            "grade.tone" to "窗边柔光",
            "grade.pos" to "侧逆光"
        )
        val head = text.lineSequence().first()
        assertTrue("调色没跟在整句后面: $head", head.contains("日系淡色低饱和"))
        assertTrue("光质没跟在整句后面: $head", head.contains("窗边柔光"))
        assertTrue("光位没跟在整句后面: $head", head.contains("侧逆光"))
        assertTrue("整句和调色之间不该出现「。，」: $head", !head.contains("。，"))
    }

    /** 霓虹夜色 without neon is just a night portrait, so the style pins its own 调色 word. */
    @Test
    fun aStyleThatNamesAColourPinsItsGrading() {
        val lib = library()
        lib.styleFeel.forEach { (medium, feel) ->
            val text = compose(lib, "mood.medium" to medium)
            assertTrue("$medium 应该带出 $feel: $text", text.contains(feel))
        }
        assertTrue("至少要有两条", lib.styleFeel.size >= 2)
    }

    /** A rename must not strip the sentence out of an archive that still holds the old word. */
    @Test
    fun anOldNameStillResolvesToItsSentence() {
        val lib = library()
        assertTrue("别名表没了", lib.styleAliases.isNotEmpty())
        lib.styleAliases.forEach { (old, new) ->
            val sentence = lib.stylePrompts[new]
            assertTrue("$new 没有整句", sentence != null)
            val text = compose(lib, "mood.medium" to old)
            assertTrue("旧名 $old 没拿到整句: $text", text.contains(sentence!!))
        }
    }

    @Test
    fun thePaletteIsStillARule() {
        val lib = library()
        val palette = lib.category("grade")!!.slots.first { it.id == "palette" }.pool.first()
        val text = compose(lib, "mood.medium" to "日式动漫", "grade.palette" to palette)
        assertTrue("主色规则没了: $text", text.contains("主色只用$palette"))
    }

    /** 普通模式 joins words only: the style ships its name, not the sentence behind it. */
    @Test
    fun plainModeShipsTheStyleNameInsteadOfItsSentence() {
        val lib = library()
        val text = composeStrict(lib, false, "mood.medium" to "日式动漫")
        val sentence = lib.stylePrompts.getValue("日式动漫")
        assertTrue("普通模式不该出现整句: $text", !text.contains(sentence))
        assertTrue("普通模式要留下名字: $text", text.contains("日式动漫"))
        assertTrue("普通模式别带句式前缀: $text", !text.contains("一幅高品质"))
    }

    /** Same for 主色: the rule sentence is a 增强模式 privilege. */
    @Test
    fun plainModeShipsThePaletteNameInsteadOfTheRule() {
        val lib = library()
        val text = composeStrict(lib, false, "grade.palette" to "朱红与暗金")
        assertTrue("普通模式不该出现规则句: $text", !text.contains("主色只用"))
        assertTrue("普通模式要留下颜色名: $text", text.contains("朱红与暗金"))
    }
}
