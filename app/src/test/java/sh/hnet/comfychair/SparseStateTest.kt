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
 * A page that opens with most categories showing 未选: the store drops empty slots on save,
 * restore only repaired conflicts, and the draw cache leaked between calls so rerolling
 * 构图 or 人数 could silently do nothing.
 */
class SparseStateTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun soloEngine(library: TagLibrary): TagComposerEngine {
        val solo = library.constraints.peopleCount.filterValues { it <= 1 }.keys
        return TagComposerEngine(library, solo)
    }

    @Test
    fun aSparseRestoreFillsTheCategoriesThatCameBackBlank() {
        val library = library()
        val engine = soloEngine(library)
        // What an old save with holes looks like: a few survivors, everything else absent.
        val picks = mutableMapOf<String, TagPick>(
            "mood.medium" to TagPick("写实照片"),
            "grade.feel" to TagPick("港风青绿胶片"),
            "grade.pos" to TagPick("侧逆光"),
            "scene.place" to TagPick("榻榻米"),
            "sexAct.act" to TagPick("手铐拘束"),
            "race.ethnicity" to TagPick("中东")
        )
        val before = picks.size
        engine.fillBlanks(picks)
        val filled = picks.count { it.value.value.isNotEmpty() && !it.value.ignored }
        assertTrue("only $filled filled (was $before)", filled >= 25)
    }

    @Test
    fun fillingBlanksKeepsWhatWasAlreadyChosen() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "mood.medium" to TagPick("写实照片"),
            "scene.place" to TagPick("榻榻米")
        )
        engine.fillBlanks(picks)
        assertTrue(picks["mood.medium"]?.value == "写实照片")
        assertTrue(picks["scene.place"]?.value == "榻榻米")
    }

    @Test
    fun aCategoryRerollIsNotBlockedByThePreviousFullDraw() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>()
        engine.randomize(picks)
        // What the user does: clear 构图, then tap its label to draw one again.
        picks["camera.shot"] = TagPick("")
        engine.randomize(picks, "camera")
        assertFalse("构图 reroll drew nothing", picks["camera.shot"]?.value.isNullOrEmpty())
    }

    @Test
    fun thePeopleCountCanBeRerolledAfterAFullDraw() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>()
        engine.randomize(picks)
        picks["people.count"] = TagPick("")
        engine.randomize(picks, "people")
        assertFalse("人数 reroll drew nothing", picks["people.count"]?.value.isNullOrEmpty())
    }
}
