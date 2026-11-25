package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.StoryPick
import java.io.File
import kotlin.random.Random

/** 567：日常补主语、过程中并进动作链、双女档第一女主叫「女一」。 */
class Story567Test {

    private fun generator(seed: Int = 7): StoryGenerator {
        val file = File("src/main/assets/story_catalog.json")
        assumeTrue(file.exists())
        return StoryGenerator(StoryLibraryJson.parse(file.readText(Charsets.UTF_8)), Random(seed))
    }

    private fun threeSome(seed: Int = 7): Pair<StoryGenerator, Map<String, StoryPick>> {
        val gen = generator(seed)
        val base = gen.randomize(gen.emptyPicks()).first
        val picks = base + (StoryGenerator.CAST to StoryPick("mff", locked = true))
        return gen to gen.randomize(picks).first
    }

    /** 日常要交代是谁在做。 */
    @Test
    fun theChoreHasASubject() {
        val gen = generator()
        val picks = gen.randomize(gen.emptyPicks()).first
        val chore = picks[StoryGenerator.CHORE]?.value.orEmpty()
        assumeTrue("这一轮没抽到日常动作", chore.isNotEmpty())
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("日常没有主语：$text", text.contains("此时") && (text.contains("她正在") || text.contains("女一正在")))
    }

    /**
     * 过程中交代的是主戏开始之后整段戏的固定衣着状态，要排在「开始」之后、动作链之前，
     * 单独占一段。
     */
    @Test
    fun theUndressStateFollowsTheOpening() {
        val lib = StoryLibraryJson.parse(File("src/main/assets/story_catalog.json").readText(Charsets.UTF_8))
        lib.templates.forEach { (cast, templates) ->
            templates.forEach { template ->
                val mid = template.indexOf("{outfit_mid}")
                assertTrue("$cast 模板里没有过程中", mid >= 0)
                assertTrue("$cast 过程中排到动作之后：$template", mid < template.indexOf("{acts"))
                assertTrue("$cast 过程中排到开始之前：$template", mid > template.indexOf("{escalate}"))
            }
        }
        var checked = 0
        for (seed in 1..30) {
            val gen = generator(seed)
            val picks = gen.randomize(gen.emptyPicks()).first
            if (picks[StoryGenerator.ACT]?.value.isNullOrEmpty()) continue
            if (picks[StoryGenerator.MID]?.value.isNullOrEmpty()) continue
            checked++
            val text = gen.compose(picks, detail = false, dialogue = false)
            val midAt = text.indexOf("不知不觉间")
            assertTrue("没有过程中：$text", midAt >= 0)
            // 过程中之后还要有动作，否则就是又掉到末尾了。
            assertTrue("过程中落在末尾：$text", text.lastIndexOf("；") > midAt)
            if (checked >= 3) break
        }
        assumeTrue("这一批没抽到过程中", checked > 0)
    }

    /** 一男两女时，过程中的衣服挂在谁身上要说清楚。 */
    @Test
    fun theUndressStateNamesWhichWoman() {
        var checked = 0
        for (seed in 1..30) {
            val gen = generator(seed)
            val base = gen.randomize(gen.emptyPicks()).first
            val picks = gen.randomize(base + (StoryGenerator.CAST to StoryPick("mff", locked = true))).first
            if (picks[StoryGenerator.MID]?.value.isNullOrEmpty()) continue
            // 开场全裸没有衣服可脱，这一句整段不写。
            if (picks[StoryGenerator.OUTFIT]?.value == "全裸") continue
            checked++
            val text = gen.compose(picks, detail = false, dialogue = false)
            // 「不知不觉间」后面现在带一个逗号作停顿，判断归属前先把它剥掉。
            val clause = text.substringAfter("不知不觉间", "").removePrefix("，")
            assertTrue("过程中没交代是谁：$text", clause.startsWith("女一"))
            if (checked >= 3) break
        }
        assumeTrue("这一批没抽到过程中", checked > 0)
    }

    /** 一男两女时第一位叫女一，不再和她混着叫。 */
    @Test
    fun theLeadIsNamedInAThreeSome() {
        val (gen, picks) = threeSome()
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("女主没叫女一：$text", text.contains("女一是一名"))
        val acts = text.substringAfter("女二", "")
        assertFalse("动作里还混着「她」：$acts", acts.contains("把她") && !acts.contains("女一"))
    }

    /** 「盘发，一头冷棕色头发」不该再加一个「留着一头」。 */
    @Test
    fun theHairDoesNotSayOneHeadTwice() {
        val gen = generator()
        val picks = mutableMapOf(
            StoryGenerator.HAIR to StoryPick("盘发", locked = true),
            StoryGenerator.HAIR_COLOR to StoryPick("冷棕色", locked = true)
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertFalse("两个一头：$text", text.contains("留着一头") && text.contains("，一头"))
    }
}
