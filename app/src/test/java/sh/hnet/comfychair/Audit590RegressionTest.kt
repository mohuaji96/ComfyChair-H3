package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.StoryPick
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File
import kotlin.random.Random

/**
 * 590：把「单帧生图画不出来」的词从池子里拿掉，补上剧情组合缺的双女戏份，
 * 并让日常档的五段式排版真正认得它自己那套分类。
 */
class Audit590RegressionTest {

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

    private fun actsOf(lib: TagLibrary): List<String> =
        lib.category("sexAct")!!.slots.first().pool

    @Test
    fun theWordsThatCannotBeDrawnAreGone() {
        val lib = tagLib()
        val acts = actsOf(lib)
        listOf("失禁", "双穴同时").forEach {
            assertFalse("「$it」画不出对应的画面，应已删除", it in acts)
        }
        assertEquals("深度插入与顶到底都不该再单独存在", 0, acts.count { it == "深度插入" || it == "顶到底" })
        assertTrue("合并后应保留胯部撞到底", "胯部撞到底" in acts)
        assertEquals("sexAct 应为 188 条（新增 6 条单人展示动作）", 188, acts.size)
        val unmapped = acts.filter { it !in lib.anchors.of && it !in lib.anchors.addOn }
        assertTrue("改词后有动作没登记锚点：$unmapped", unmapped.isEmpty())
    }

    @Test
    fun noDrawableWordStillDescribesTwoFramesOrAnInside() {
        val acts = actsOf(tagLib())
        // 一条词写「先抽出再顶回」，模型只能挑一个动作画，或者把两帧叠成畸形。
        listOf(
            "整根抽出再整根顶回去，撞到宫口",
            "抽出来只留龟头卡在穴口，又整根推回去",
            "阴茎退到穴口又狠狠顶回去",
            "抽出来，用龟头拍她的阴蒂",
            "拔出来，穴口还开着慢慢合上",
            "射完还插着不动，等它慢慢软下来",
            "高潮后还在轻轻抽送"
        ).forEach { assertFalse("两个先后动作画不出单帧：「$it」", it in acts) }
        // 宫口、隔着肚皮顶进去这类体内深度，画面里没有落点。
        val insideBody = acts.filter { "宫口" in it || "隔着肚皮" in it }
        assertTrue("还在写体内深度：$insideBody", insideBody.isEmpty())
        // 声音与触感无从表现。
        val nonVisual = acts.filter { "水声" in it || "被裹住" in it }
        assertTrue("还在写听觉/触觉：$nonVisual", nonVisual.isEmpty())
    }

    @Test
    fun thePlotLibraryGainedTheTwoWomanActs() {
        val lib = storyLib()
        val texts = lib.pool("act").map { it.text }
        listOf(
            "两女并排跪着，一左一右一起舔着阴茎",
            "一人含着阴茎根部，另一人舔着龟头",
            "两女一前一后轮流含着阴茎吞吐",
            "把她抱到洗手台上，从正面顶进去",
            "把她按在镜子前，让她看着镜子里的自己被插",
            "双手托起她的臀部，把她抱在腰间抽送",
            "把她的双腿并拢压在胸前，一口气顶到底"
        ).forEach { assertTrue("剧情库缺「$it」", it in texts) }

        // 姿势绑定必须落在真实存在的 key 上，否则这条动作永远抽不到。
        val poseKeys = lib.pool("pose").mapNotNull { it.key.takeIf(String::isNotBlank) }.toSet()
        val badBind = lib.pool("act")
            .flatMap { w -> w.poses.map { it to w.text } }
            .filter { it.first !in poseKeys }
        assertTrue("绑到了不存在的姿势 key：$badBind", badBind.isEmpty())
    }

    @Test
    fun pullingOutActsCarryNoThrustingVerb() {
        val lib = storyLib()
        val gen = StoryGenerator(lib, Random(3))
        listOf(
            "抽出来只留龟头卡在穴口，又整根推回去",
            "整根拔出来，穴口还开着慢慢合上",
            "她夹得很紧，抽出来时被裹住",
            "让她跪好，从后面插进去",
            "抹了润滑，龟头抵着后穴慢慢推进去"
        ).forEach { act ->
            val chain = gen.mergeChain(
                mapOf(
                    StoryGenerator.ACT to StoryPick(act),
                    StoryGenerator.POSE to StoryPick("跪趴")
                )
            )
            assertFalse("「$act」不该接顺势挺腰：$chain", chain.contains("顺势挺腰"))
        }
    }

    @Test
    fun dailyCastHasNoSecondMan() {
        val sfw = tagLib("tag_catalog_sfw.json")
        val people = sfw.category("people")!!.slots.first().pool
        assertFalse("日常档不该有两位男性", "两位男性" in people)
        assertFalse("日常档不该有单人男性", "单人男性" in people)
        assertTrue("日常档仍要留一男一女", "一男一女" in people)
        assertTrue("日常档不该出魅魔", "魅魔" !in sfw.category("race")!!.slots.first().pool)
    }

    @Test
    fun dailySectionsAndNounsReadNaturally() {
        val sfw = tagLib("tag_catalog_sfw.json")
        assertTrue("日常档应启用分段", sfw.sectionMode)
        val engine = TagComposerEngine(sfw)

        // 地域词不能当名词用，要收成「东亚女性」。
        val racePrompt = engine.compose(
            mutableMapOf(
                "people.count" to TagPick("单人女性", locked = true),
                "race.kind" to TagPick("东亚面孔", locked = true)
            )
        )
        assertFalse("东亚面孔被直接当名词：$racePrompt", racePrompt.contains("东亚面孔女"))
        assertTrue("应写成东亚女性：$racePrompt", racePrompt.contains("东亚女性"))

        // 日常档的 garment 是连身装，要跟「穿」走。
        val dressPicks = mutableMapOf<String, TagPick>(
            "people.count" to TagPick("单人女性", locked = true),
            "garment.main" to TagPick("米白针织连衣裙", locked = true)
        )
        listOf(
            "top.main", "bottom.main", "socks.type", "shoes.type",
            "outfitState.state", "headwear.type", "jewelry.type"
        ).forEach { dressPicks[it] = TagPick(ignored = true) }
        val dressPrompt = engine.compose(dressPicks)
        assertTrue("连身装应挂在「穿」后面：$dressPrompt", dressPrompt.contains("穿米白针织连衣裙"))

        // 风格与光线属于定调层，不能掉到最后一行的兜底段。
        repeat(20) {
            val picks = mutableMapOf<String, TagPick>("people.count" to TagPick("单人女性", locked = true))
            engine.randomize(picks)
            val prompt = engine.compose(picks)
            val last = prompt.lines().filter { it.isNotBlank() }.last()
            val style = picks["style.medium"]?.value.orEmpty()
            val light = picks["light.kind"]?.value.orEmpty()
            if (style.isNotEmpty()) assertFalse("风格掉到最末一行：$prompt", last.contains(style))
            if (light.isNotEmpty()) assertFalse("光线掉到最末一行：$prompt", last.contains(light))
        }
    }

    @Test
    fun dailyOutfitStateReachesTheSentence() {
        val sfw = storyLib("story_catalog_sfw.json")
        val gen = StoryGenerator(sfw, Random(5))
        val outfit = sfw.pool("outfit").first()
        val state = sfw.pool("outfit_state").first()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.OUTFIT to StoryPick(outfit.text, locked = true),
            StoryGenerator.STATE to StoryPick("袖口卷起", locked = true)
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("日常档的穿着状态没进句子：$text", text.contains("袖口卷起"))
        assertTrue("衣着本身也要在：$text", text.contains(outfit.text))
        // 拿掉衣服，状态就该消失：没穿外衣谈不上袖口卷起。
        val bare = picks + (StoryGenerator.OUTFIT to StoryPick("全裸", locked = true))
        assertFalse("全裸还写袖口卷起", gen.compose(bare, detail = false, dialogue = false).contains("袖口卷起"))
    }
}
