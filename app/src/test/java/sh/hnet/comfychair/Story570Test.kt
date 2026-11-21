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

/** 570：两女档里女二的衣着状态也要交代，而且按她自己那身算。 */
class Story570Test {

    private fun generator(seed: Int = 7): StoryGenerator {
        val file = File("src/main/assets/story_catalog.json")
        assumeTrue(file.exists())
        return StoryGenerator(StoryLibraryJson.parse(file.readText(Charsets.UTF_8)), Random(seed))
    }

    private fun threeSome(seed: Int): Pair<StoryGenerator, Map<String, StoryPick>> {
        val gen = generator(seed)
        val base = gen.randomize(gen.emptyPicks()).first
        val picks = base + (StoryGenerator.CAST to StoryPick("mff", locked = true))
        return gen to gen.randomize(picks).first
    }

    /** 女二那一段要有她自己的「过程中」，并且写明是她。 */
    @Test
    fun theSecondWomanGetsHerOwnUndressState() {
        var checked = 0
        for (seed in 1..30) {
            val (gen, picks) = threeSome(seed)
            if (picks[StoryGenerator.W2_MID]?.value.isNullOrEmpty()) continue
            // 她开场就全裸，这一句整段不写。
            if (picks["w2_outfit"]?.value == "全裸") continue
            checked++
            val text = gen.compose(picks, detail = false, dialogue = false)
            val mid = text.substringAfter("动情拉扯间", "")
            assertTrue("女二那句没写是谁：$text", mid.contains("女二"))
            if (checked >= 3) break
        }
        assumeTrue("这一批没抽到女二的过程", checked > 0)
    }

    /** 两个人的衣服状态不能落在同一句上。 */
    @Test
    fun theTwoWomenDoNotShareOneClause() {
        repeat(20) { index ->
            val (_, picks) = threeSome(index + 1)
            val first = picks[StoryGenerator.MID]?.value.orEmpty()
            val second = picks[StoryGenerator.W2_MID]?.value.orEmpty()
            assertFalse("两个人的过程中撞了：$first", first.isNotEmpty() && first == second)
        }
    }

    /** 女二全裸时，不能写「她的内裤被拉到膝盖」。 */
    @Test
    fun aNakedSecondWomanUndressesNothing() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mff", locked = true),
            "w2_outfit" to StoryPick("全裸", locked = true)
        )
        val mids = gen.allowed(StoryGenerator.W2_MID, picks)
        listOf("内裤被拉到膝盖", "胸罩被拉了下来", "浴巾掉在地上", "围裙被扯到一边").forEach {
            assertFalse("女二全裸还说「$it」", it in mids)
        }
        // 她身上什么都没有，也还有「全身赤裸」这类可写，这一格不该整格空掉。
        assertTrue("女二全裸时这一格空了", mids.isNotEmpty())
    }

    /** 开场就全裸的人没有衣服可脱，这一句整段不写，免得「衣物已完全除尽」变成当场又脱一遍。 */
    @Test
    fun aNakedWomanGetsNoUndressClause() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mff", locked = true),
            StoryGenerator.OUTFIT to StoryPick("全裸", locked = true),
            "w2_outfit" to StoryPick("全裸", locked = true),
            StoryGenerator.MID to StoryPick("内裤被拉到膝盖", locked = true),
            StoryGenerator.W2_MID to StoryPick("胸罩被拉了下来", locked = true)
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertFalse("全裸还写脱衣服：$text", text.contains("动情拉扯间"))
    }

    /** 一男一女没有女二，这一格必须是空的，句子里也不许冒出「女二」。 */
    @Test
    fun oneManOneWomanHasNoSecondClause() {
        val gen = generator()
        val base = gen.randomize(gen.emptyPicks()).first
        val picks = gen.randomize(base + (StoryGenerator.CAST to StoryPick("mf", locked = true))).first
        assertTrue("一男一女还留着女二的过程", picks[StoryGenerator.W2_MID]?.value.isNullOrEmpty())
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertFalse("一男一女冒出女二：$text", text.contains("女二"))
    }
}
