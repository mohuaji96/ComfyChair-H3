package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.StoryPick
import java.io.File
import kotlin.random.Random

/**
 * 固定种子的回归样本：同一份选择必须给出同一段文字，被眼睛关掉的词不许出现在正文里。
 *
 * 这两条以前都靠人眼看，改一处引擎就可能悄悄退回旧行为；有了这两条，重抽与排除的
 * 契约一旦破了会当场失败。
 */
class StoryStabilityTest {

    private fun library(): StoryLibrary {
        val file = listOf(
            File("src/main/assets/story_catalog.json"),
            File("app/src/main/assets/story_catalog.json")
        ).firstOrNull { it.exists() }
        assumeTrue("story_catalog.json not found", file != null)
        return StoryLibraryJson.parse(file!!.readText(Charsets.UTF_8))
    }

    /** 一份普通的一男一女选择，不依赖随机抽取。 */
    private fun samplePicks(lib: StoryLibrary): Map<String, StoryPick> {
        fun first(pool: String): String = lib.pool(pool).firstOrNull()?.text.orEmpty()
        return mapOf(
            StoryGenerator.CAST to StoryPick("mf"),
            StoryGenerator.PLACE to StoryPick(first("place")),
            StoryGenerator.OUTFIT to StoryPick(first("outfit")),
            StoryGenerator.LIGHT to StoryPick(first("light")),
            StoryGenerator.POSE to StoryPick(first("pose")),
            StoryGenerator.ACT to StoryPick(first("act")),
        )
    }

    @Test
    fun `the same picks and seed compose the same text`() {
        val lib = library()
        val picks = samplePicks(lib)
        val first = StoryGenerator(lib, Random(4141)).compose(picks, detail = true, dialogue = false)
        val second = StoryGenerator(lib, Random(4141)).compose(picks, detail = true, dialogue = false)
        assertTrue("样本没拼出正文", first.isNotBlank())
        assertEquals("同种子同选择必须逐字相同", first, second)
    }

    /**
     * 四种人数的固定种子回归。以前只验一男一女，单人档的「她」、双女档的女一女二、
     * 一男两女档的主位轮换都是各自一套人称逻辑，坏掉时不会在 mf 上看出来。
     */
    @Test
    fun `every cast composes a clean paragraph`() {
        val lib = library()
        val base = samplePicks(lib)
        lib.casts.keys.sorted().forEach { cast ->
            val picks = base + (StoryGenerator.CAST to StoryPick(cast))
            val text = StoryGenerator(lib, Random(4143)).compose(picks, detail = true, dialogue = false)
            assertTrue("$cast 没拼出正文", text.isNotBlank())
            assertFalse("$cast 还有没替换的占位符：$text", text.contains("{"))
            assertFalse("$cast 出现双句号：$text", text.contains("。。"))
            assertFalse("$cast 出现句首逗号：$text", text.contains("。，"))
            assertFalse("$cast 出现空一段：$text", text.contains("，，"))
        }
    }

    /**
     * 重抽之后每一格都得经得起复验：把抽出来的整套选择再判一遍，全都还成立。
     *
     * 以前收尾只捋一轮，两层以上的依赖（换场景→姿势失效→动作失效）捋不干净，
     * 界面看着都选好了，正文却有一格是照着上一份设定写出来的。多个种子各抽一次，
     * 抓这种偶发的层级问题。
     */
    @Test
    fun `a redraw leaves every field still valid`() {
        val lib = library()
        (1..12).forEach { seed ->
            val gen = StoryGenerator(lib, Random(seed))
            val (next, _) = gen.randomize(gen.emptyPicks())
            next.forEach { (key, pick) ->
                if (pick.ignored || pick.locked || pick.value.isEmpty()) return@forEach
                if (key == StoryGenerator.ACT) return@forEach
                val options = gen.allowed(key, next, forPick = true)
                if (options.isEmpty()) return@forEach
                // 姿势是一串，逐个都得还在候选里，不能拿整串去比。
                val parts = if (key == StoryGenerator.POSE) {
                    pick.value.split(StoryGenerator.SEP).filter { it.isNotBlank() }
                } else {
                    listOf(pick.value)
                }
                parts.forEach { part ->
                    assertTrue(
                        "种子 $seed 抽完「$key」里的「$part」已不成立，可选 ${options.size} 条",
                        part in options
                    )
                }
            }
        }
    }

    /**
     * 日常档的「accent」装的是语速、音色这类说话神态，不是耳环项链。
     * 格子名和成句都要跟着内容走，否则点开「配饰」抽到「声音偏低」，或者正文写「戴着声音偏低」。
     */
    @Test
    fun `an everyday accent is never worn like jewellery`() {
        val file = listOf(
            File("src/main/assets/story_catalog_sfw.json"),
            File("app/src/main/assets/story_catalog_sfw.json")
        ).firstOrNull { it.exists() }
        assumeTrue("story_catalog_sfw.json not found", file != null)
        val lib = StoryLibraryJson.parse(file!!.readText(Charsets.UTF_8))
        val gen = StoryGenerator(lib, Random(4144))
        assertEquals("日常档这一格应叫说话神态", "说话神态", gen.field(StoryGenerator.ACCENT)?.label)

        val voice = lib.pool("accent").firstOrNull { it.text.contains("声音") }?.text.orEmpty()
        assumeTrue("日常档没有声音类神态词", voice.isNotEmpty())
        val picks = gen.randomize(gen.emptyPicks()).first +
            (StoryGenerator.ACCENT to StoryPick(voice))
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertFalse("说话神态被当成配饰戴上：$text", text.contains("戴着$voice"))
        assertTrue("说话神态没进正文：$text", text.contains(voice))
    }

    @Test
    fun `a word switched off never reaches the text`() {
        val lib = library()
        val detail = lib.pool("detail").firstOrNull()?.text.orEmpty()
        assumeTrue("没有 detail 池", detail.isNotEmpty())
        val picks = samplePicks(lib) + (StoryGenerator.DETAIL to StoryPick(detail))
        val gated = lib.copy(excluded = lib.excluded + ("detail" to setOf(detail)))
        val text = StoryGenerator(gated, Random(4142)).compose(picks, detail = true, dialogue = false)
        assertFalse("被排除的词还是出现了：$text", text.contains(detail))
    }
}
