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
 * A flat tag list gives every word the same weight and no owner, so a five-woman scene read
 * exactly like a one-woman scene. These checks pin the paragraph layout that replaced it.
 */
class SectionedPromptTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun prompts(library: TagLibrary, people: String, rounds: Int = 40): List<String> {
        val engine = TagComposerEngine(library)
        return (1..rounds).map {
            val picks = mutableMapOf<String, TagPick>("people.count" to TagPick(people, locked = true))
            engine.randomize(picks)
            engine.compose(picks)
        }
    }

    @Test
    fun theShippedCatalogUsesParagraphs() {
        assertTrue("sectionMode should ship switched on", library().sectionMode)
    }

    @Test
    fun everyCastOfSeveralStatesWhoElseIsInFrame() {
        val library = library()
        for (people in listOf("两个女孩", "三个女孩", "五个女孩", "一男两女", "一男多女")) {
            prompts(library, people, 20).forEach { text ->
                val named = text.contains("另外") || text.contains("其余")
                assertTrue("$people lost the supporting cast: $text", named)
                assertTrue("$people lost the position: $text", text.contains("画面"))
            }
        }
    }

    @Test
    fun aSoloCastNeverInventsAnyone() {
        prompts(library(), "单人女孩", 40).forEach { text ->
            assertFalse(text, text.contains("另外"))
            assertFalse(text, text.contains("男人"))
        }
    }

    @Test
    fun aManIsNamedOnceAndOnlyWhenTheCastHasOne() {
        val library = library()
        for (people in listOf("一男一女", "一男两女", "一男多女")) {
            prompts(library, people, 40).forEach { text ->
                // His own sentence says 男性; the head-count note also says 男性, so count the
                // clause rather than every mention, and keep the bare 男人 from doubling up.
                val clause = Regex("他是一名[^。]*男性|画面里有一名男性").findAll(text).count()
                val bare = Regex("男人").findAll(text).count()
                assertTrue("$people stated him $clause + $bare times: $text", clause == 1 && bare <= 1)
            }
        }
    }

    @Test
    fun outputIsLayeredInsteadOfOneRunOnLine() {
        prompts(library(), "一男两女", 10).forEach { text ->
            assertTrue("expected paragraphs: $text", text.lines().count { it.isNotBlank() } >= 3)
        }
    }

    @Test
    fun aCappedBodyWordDoesNotReappearAtTheEnd() {
        val library = library()
        val body = listOf("breasts", "waist", "hips", "legs")
            .flatMap { id -> library.category(id)?.slots.orEmpty().flatMap { it.pool } }
            .toSet()

        prompts(library, "单人女孩", 60).forEach { text ->
            val last = text.lines().last { it.isNotBlank() }.trim()
            assertFalse("body word dumped at the tail: " + last, last in body)
        }
    }

    @Test
    fun noCategoryIsSaidTwice() {
        prompts(library(), "三个女孩", 40).forEach { text ->
            val parts = text.split(", ", "\n").map { it.trim() }.filter { it.isNotEmpty() }
            // Nothing repeats any more: the style is one sentence, not cues echoed at both ends.
            val dup = parts.groupBy { it }.filterValues { it.size > 1 }.keys
            assertTrue("repeated segment $dup in: $text", dup.isEmpty())
        }
    }
}
