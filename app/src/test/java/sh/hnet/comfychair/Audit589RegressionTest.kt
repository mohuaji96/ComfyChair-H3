package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.storage.LibraryMerge
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.StoryPick
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File
import kotlin.random.Random

/**
 * 589 全量审计回归测试：词库组合与剧情组合的数据持久化、语法拼接、双女档逻辑与四幕/五段式排版模板。
 */
class Audit589RegressionTest {

    private fun tagLib(name: String = "tag_catalog.json"): TagLibrary {
        val file = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .firstOrNull { it.exists() }
        assumeTrue("$name not found", file != null)
        return TagLibraryJson.parse(file!!.readText(Charsets.UTF_8))
    }

    private fun storyLib(name: String = "story_catalog.json"): StoryLibrary {
        val file = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .firstOrNull { it.exists() }
        assumeTrue("$name not found", file != null)
        return StoryLibraryJson.parse(file!!.readText(Charsets.UTF_8))
    }

    @Test
    fun storyJsonRoundTripsMutexAndPenetratingFlag() {
        val lib = storyLib()
        assertTrue("内置剧情库应有 mutex", lib.mutex.isNotEmpty())
        assertTrue("内置剧情库应有非插入动作", lib.pool("act").any { !it.penetrating })

        val roundTripped = StoryLibraryJson.parse(StoryLibraryJson.write(lib))
        assertEquals("写盘后 mutex 丢失", lib.mutex, roundTripped.mutex)
        val nonPenBefore = lib.pool("act").filter { !it.penetrating }.map { it.text }.toSet()
        val nonPenAfter = roundTripped.pool("act").filter { !it.penetrating }.map { it.text }.toSet()
        assertEquals("写盘后 penetrating=false 丢失", nonPenBefore, nonPenAfter)
    }

    @Test
    fun libraryMergePreservesCustomWeights() {
        val base = TagLibrary(constraints = TagConstraints(weight = mapOf("护士服" to 0.25)))
        val local = TagLibrary(constraints = TagConstraints(weight = mapOf("护士服" to 0.25, "自定义服" to 0.5)))
        val incoming = TagLibrary(constraints = TagConstraints(weight = mapOf("护士服" to 0.2, "兔女郎装" to 0.25)))
        val merged = LibraryMerge.fold(base, local, incoming)
        assertEquals(0.2, merged.constraints.weightOf("护士服"), 1e-6)
        assertEquals(0.25, merged.constraints.weightOf("兔女郎装"), 1e-6)
        assertEquals(0.5, merged.constraints.weightOf("自定义服"), 1e-6)
    }

    @Test
    fun tagComposerGrammarAndFiveSectionTemplate() {
        val lib = tagLib()
        val engine = TagComposerEngine(lib)

        // 1. 自带图案款式（豹纹内裤）即使锁了颜色也不拼出「白色的豹纹内裤」
        val patternPicks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "panties.type" to TagPick("豹纹内裤", locked = true),
            "panties.color" to TagPick("白色", locked = true)
        )
        val patternText = engine.compose(patternPicks)
        assertFalse("自带图案仍拼出颜色：$patternText", patternText.contains("白色的豹纹内裤"))
        assertTrue(patternText.contains("豹纹内裤"))

        // 2. 衣物细节与状态词不挂「穿」前缀
        listOf(
            "garment.detail" to "双手在背后解胸罩",
            "garment.detail" to "露出一边乳房",
            "shoes.type" to "赤足",
            "socks.type" to "光腿"
        ).forEach { (k, v) ->
            val text = engine.compose(mutableMapOf("people.count" to TagPick("一男一女"), k to TagPick(v)))
            assertFalse("出现了「穿$v」：$text", text.contains("穿$v"))
            assertTrue("丢失了「$v」：$text", text.contains(v))
        }

        // 3. 皮肤特征与乳房形态不出现病句拼接
        val skinText = engine.compose(
            mutableMapOf(
                "skin.tone" to TagPick("有雀斑"),
                "skin.state" to TagPick("皮肤出汗发亮")
            )
        )
        assertFalse("有雀斑挂了的：$skinText", skinText.contains("有雀斑的皮肤"))
        assertTrue(skinText.contains("有雀斑，皮肤出汗发亮"))

        val breastText = engine.compose(
            mutableMapOf(
                "breasts.size" to TagPick("巨乳"),
                "breasts.shape" to TagPick("用手挡住却没挡住")
            )
        )
        assertFalse("动作形态成了定语：$breastText", breastText.contains("用手挡住却没挡住的巨乳"))
        assertTrue(breastText.contains("巨乳，用手挡住却没挡住"))

        val breastNounText = engine.compose(
            mutableMapOf(
                "breasts.size" to TagPick("巨乳"),
                "breasts.shape" to TagPick("并拢的胸部")
            )
        )
        assertFalse("重复了胸部与巨乳：$breastNounText", breastNounText.contains("并拢的胸部的巨乳"))
        assertTrue(breastNounText.contains("并拢的巨乳"))

        // 4. 环境痕迹排在镜头场景段，不再掉到全文最末；且男方句号后不跟英文逗号
        val tracePicks = mutableMapOf(
            "people.count" to TagPick("一男一女", locked = true),
            "scene.place" to TagPick("浴室", locked = true),
            "sceneTrace.trace" to TagPick("浴室里还弥漫着水汽", locked = true),
            "sexAct.act" to TagPick("后入", locked = true)
        )
        val tracePrompt = engine.compose(tracePicks)
        assertFalse("出现了句号加英文逗号：$tracePrompt", tracePrompt.contains("。, "))
        val lines = tracePrompt.lines().filter { it.isNotBlank() }
        assertTrue("环境痕迹掉到了最后一行：$tracePrompt", !lines.last().contains("浴室里还弥漫着水汽"))

        // 5. 全量 sexAct 均已登记入 anchors
        val sexActs = lib.category("sexAct")!!.slots.first().pool
        val unmapped = sexActs.filter { it !in lib.anchors.of && it !in lib.anchors.addOn }
        assertTrue("仍有未登记锚点的性行为：$unmapped", unmapped.isEmpty())
    }

    @Test
    fun sfwTagCatalogUsesSectionModeAndIgnoresPropsByDefault() {
        val sfw = tagLib("tag_catalog_sfw.json")
        assertTrue("SFW 词库应启用分段模式", sfw.sectionMode)
        assertTrue("SFW 默认忽略应含 pet", "pet" in sfw.defaultIgnored)
        assertTrue("SFW 默认忽略应含 propObject", "propObject" in sfw.defaultIgnored)
    }

    @Test
    fun storyComposerFixesAndFourActTemplate() {
        val lib = storyLib()
        val gen = StoryGenerator(lib, Random(42))

        // 1. 全部姿势 key 在动作池均有认领
        val poseKeys = lib.pool("pose").mapNotNull { it.key.takeIf(String::isNotBlank) }.toSet()
        val actPoseKeys = lib.pool("act").flatMap { it.poses }.toSet()
        val orphans = poseKeys - actPoseKeys
        assertTrue("仍有无动作认领的孤儿姿势 key：$orphans", orphans.isEmpty())

        // 2. 双女百合档（ff）绝不出现男方/阴茎，且开始动作有明确主语
        repeat(30) { seed ->
            val g = StoryGenerator(lib, Random(seed + 1))
            val picks = g.randomize(g.emptyPicks() + (StoryGenerator.CAST to StoryPick("ff", locked = true))).first
            val text = g.compose(picks, detail = true, dialogue = false)
            assertFalse("ff 档出现了他：$text", text.contains("他"))
            assertFalse("ff 档出现了阴茎：$text", text.contains("阴茎"))
        }

        // 3. 女二穿整身衣服时不自动抽乳晕/乳头，全裸时不穿高跟鞋
        val w2Dressed = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mff", locked = true),
            "w2_outfit" to StoryPick("穿着职业套装", locked = true)
        )
        assertTrue("女二穿整身衣服不应自动抽乳晕", gen.allowed("w2_areola", w2Dressed).isEmpty())
        assertTrue("女二穿整身衣服不应自动抽乳头", gen.allowed("w2_nipple", w2Dressed).isEmpty())

        val w2Naked = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mff", locked = true),
            "w2_outfit" to StoryPick("全裸", locked = true)
        )
        assertFalse("女二全裸不应穿高跟鞋", gen.allowed("w2_shoes", w2Naked).any { "高跟" in it })

        // 4. 人物与男主成句不再出现「女女武神」「留着一头刘海遮住眼睛」「身穿下半身只裹着浴巾」
        val markWord = lib.pool("mark").first().text
        val wordingPicks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.ETHNICITY to StoryPick("女武神", locked = true),
            StoryGenerator.HAIR to StoryPick("刘海遮住眼睛", locked = true),
            StoryGenerator.BUST_SHAPE to StoryPick("晃动", locked = true),
            StoryGenerator.NIPPLE to StoryPick("自然放松", locked = true),
            StoryGenerator.MAN_LOOK to StoryPick("少年感", locked = true),
            StoryGenerator.MAN_OUTFIT to StoryPick("下半身只裹着浴巾", locked = true),
            StoryGenerator.MARK to StoryPick(markWord, locked = true)
        )
        val wordingText = gen.compose(wordingPicks, detail = false, dialogue = false)
        assertFalse("出现了女女武神：$wordingText", wordingText.contains("女女武神"))
        assertFalse("出现了留着一头刘海遮住眼睛：$wordingText", wordingText.contains("留着一头刘海遮住眼睛"))
        assertFalse("出现了胸型晃动：$wordingText", wordingText.contains("胸型晃动"))
        assertTrue("乳头自然放松补齐主语：$wordingText", wordingText.contains("乳头自然放松"))
        assertFalse("出现了身穿下半身只裹着浴巾：$wordingText", wordingText.contains("身穿下半身只裹着浴巾"))
        assertTrue("痕迹补齐主谓：$wordingText", wordingText.contains("她身上留着$markWord"))

        // 5. SFW 剧情库：说话习惯不加「戴着」，多人档女二进入句子
        val sfwLib = storyLib("story_catalog_sfw.json")
        val sfwGen = StoryGenerator(sfwLib, Random(7))
        val sfwPicks = sfwGen.randomize(
            sfwGen.emptyPicks() + mapOf(
                StoryGenerator.CAST to StoryPick("ff", locked = true),
                StoryGenerator.ACCENT to StoryPick("说话时语速偏慢", locked = true)
            )
        ).first
        val sfwText = sfwGen.compose(sfwPicks, detail = true, dialogue = false)
        assertFalse("SFW 说话习惯被加了戴着：$sfwText", sfwText.contains("戴着说话"))
        assertTrue("SFW 双女档应包含女二描写：$sfwText", sfwText.contains("女二是一名"))
    }
}
