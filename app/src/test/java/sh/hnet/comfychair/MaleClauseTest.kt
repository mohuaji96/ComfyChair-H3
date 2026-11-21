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
 * The man used to arrive as one glued noun ("穿着背心短裤，汗湿的背的熊系男人") and, when no male
 * slot was drawn, as the bare word 男人. He now gets his own sentence, and that sentence must not
 * borrow anything that belongs to her.
 */
class MaleClauseTest {

    private fun libraryText(): String? = listOf(
        File("src/main/assets/tag_catalog.json"),
        File("app/src/main/assets/tag_catalog.json")
    ).firstOrNull { it.exists() }?.readText(Charsets.UTF_8)

    private fun engine(): TagComposerEngine {
        val text = libraryText()
        assumeTrue("catalog asset not reachable", text != null)
        return TagComposerEngine(TagLibraryJson.parse(text!!))
    }

    @Test
    fun hisClauseIsItsOwnSentenceAndBorrowsNothingFromHer() {
        val engine = engine()
        val hers = listOf("巨乳", "丝袜", "阴唇", "乳头", "高跟鞋", "连衣裙", "长发", "她")
        repeat(40) {
            val picks = mutableMapOf("people.count" to TagPick("一男一女", locked = true))
            engine.randomize(picks)
            val text = engine.compose(picks)
            val clause = Regex("他是一名[^。]*。|画面里有一名男性。").find(text)?.value
            assertTrue("male clause missing in: $text", clause != null)
            hers.forEach { word ->
                assertFalse("male clause borrowed $word: $clause", clause!!.contains(word))
            }
        }
    }

    @Test
    fun clothingNeverReadsAsWearingNothing() {
        val engine = engine()
        val broken = listOf("穿无内裤", "穿无内衣", "穿着只穿", "穿着西装革履", "穿着全裸")
        listOf("单人女孩", "一男一女").forEach { count ->
            repeat(40) {
                val picks = mutableMapOf("people.count" to TagPick(count, locked = true))
                engine.randomize(picks)
                val text = engine.compose(picks)
                broken.forEach { phrase ->
                    assertFalse("$phrase in: $text", text.contains(phrase))
                }
            }
        }
    }
}
