package sh.hnet.comfychair

import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.StoryPick
import java.io.File

/** Temporary probe: dump full prompts for the eight-section layout proposal. */
class ProbeLayoutTest {

    @Test
    fun dump() {
        val file = File("src/main/assets/story_catalog.json")
        assumeTrue(file.exists())
        val lib = StoryLibraryJson.parse(file.readText(Charsets.UTF_8))
        val out = StringBuilder()
        for ((cast, seed) in listOf("mf" to 21, "mff" to 33, "solo" to 44)) {
            val gen = StoryGenerator(lib, kotlin.random.Random(seed))
            repeat(6) {
                val picks = gen.randomize(mapOf(StoryGenerator.CAST to StoryPick(cast, locked = true))).first
                val text = gen.compose(picks, detail = true, dialogue = false)
                if (text.length > 90) out.append("[$cast] ").append(text).append("\n\n")
            }
        }
        File("build/layout-probe.txt").writeText(out.toString(), Charsets.UTF_8)
    }
}
