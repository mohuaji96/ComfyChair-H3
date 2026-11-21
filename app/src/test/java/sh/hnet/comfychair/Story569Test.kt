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

/** 569：主戏在女一女二之间轮换，另一个人要有动作。 */
class Story569Test {

    private fun library() = run {
        val file = File("src/main/assets/story_catalog.json")
        assumeTrue(file.exists())
        StoryLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun sample(seed: Int): String {
        val gen = StoryGenerator(library(), Random(seed))
        val base = gen.randomize(gen.emptyPicks()).first
        val picks = base + (StoryGenerator.CAST to StoryPick("mff", locked = true))
        val fresh = gen.randomize(picks).first
        return gen.compose(fresh, detail = false, dialogue = false)
    }

    /** 「一人…另一人…」这种占位说法不该出现在成品里。 */
    @Test
    fun thePlaceholderSubjectsAreResolved() {
        for (seed in 1..6) {
            val text = sample(seed * 3)
            assertFalse("还留着「一人」：$text", text.contains("一人被") || text.contains("一人骑") || text.contains("另一人"))
        }
    }

    /** 两个人的动作都要落到具体某一位身上。 */
    @Test
    fun bothWomenGetNamedActions() {
        var sawSecond = false
        for (seed in 1..8) {
            val text = sample(seed * 5)
            assertTrue("第一个女主没名字：$text", text.contains("女一"))
            if (text.contains("女二被") || text.contains("把女二") || text.contains("女二趴") ||
                text.contains("女二跪") || text.contains("女二跨") || text.contains("女二躺")
            ) sawSecond = true
        }
        assertTrue("八轮里女二一次主位都没轮到", sawSecond)
    }

    /** 单人口吻的动作，旁边那位要补一句在做什么。 */
    @Test
    fun theOtherWomanIsNotIdle() {
        var checked = 0
        for (seed in 1..10) {
            val text = sample(seed * 7)
            if (!text.contains("女二")) continue
            checked++
            assertTrue("女二只在身世里有，动作里没她：$text", text.contains("女二"))
        }
        assertTrue("样本太少，判不了", checked >= 3)
    }
}
