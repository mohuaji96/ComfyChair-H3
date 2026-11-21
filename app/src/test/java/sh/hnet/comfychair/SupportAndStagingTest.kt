package sh.hnet.comfychair

import org.junit.Assert.assertEquals
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
 * The supporting cast used to be generated independently of what the hero was doing: an act
 * that needs a second body could be surrounded by women who are only watching.
 */
class SupportAndStagingTest {

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
    fun everyExcludeEntryIsStillAPair() {
        val library = library()
        val bad = library.constraints.excludes.filter { it.size != 2 }
        assertTrue("excludes must be pairs, got $bad", bad.isEmpty())
    }

    @Test
    fun anActThatNeedsTwoBodiesGetsSomeoneToPerformIt() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mapOf(
            "people.count" to TagPick("五个女孩"),
            "sexAct.act" to TagPick("被两人同时抚摸")
        )
        val text = engine.compose(picks)
        val joined = listOf("扶着她的腰", "托着她的肩", "掌心停在她胸前", "按着她的腿", "贴着她")
            .any { text.contains(it) }
        assertTrue("nobody present to touch her: $text", joined)
        assertFalse("spectators only: $text", text.contains("安静地看着这一幕"))
    }

    @Test
    fun aWatchingCastIsFineWhenTheActNeedsNoPartner() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mapOf(
            "people.count" to TagPick("五个女孩"),
            "sexAct.act" to TagPick("自慰")
        )
        val text = engine.compose(picks)
        assertFalse("solo act should not recruit help: $text", text.contains("扶着她的腰"))
    }

    @Test
    fun aSeatedCastDoesNotGetAStandingMan() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mapOf(
            "people.count" to TagPick("一男两女"),
            "pose.body" to TagPick("三人围坐"),
            "sexAct.act" to TagPick("共同服侍")
        )
        val text = engine.compose(picks)
        assertFalse("everyone is seated: $text", text.contains("他站在她面前"))
    }

    @Test
    fun aWardrobeGazeNeedsAWardrobe() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "scene.place" to TagPick("公园", locked = true),
            "gazeDir.gazeDir" to TagPick("藏在衣柜里探出脸")
        )
        engine.repair(picks)
        assertFalse(
            "no wardrobe in a park",
            picks["gazeDir.gazeDir"]?.value == "藏在衣柜里探出脸"
        )
    }

    @Test
    fun pocketsNeedSomethingWithPockets() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "outfitState.state" to TagPick("下身赤裸", locked = true),
            "pose.body" to TagPick("手插口袋")
        )
        engine.repair(picks)
        assertFalse(
            "下身赤裸 has no pockets",
            picks["pose.body"]?.value == "手插口袋"
        )
    }

    @Test
    fun aLyingGazeCannotSitOnAChair() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "pose.body" to TagPick("站立", locked = true),
            "gazeDir.gazeDir" to TagPick("躺着侧过身看镜头")
        )
        engine.repair(picks)
        // Re-drawing to a posture-free gaze is fine; the lying one is what cannot survive.
        assertFalse(
            "standing cannot lie down: ${picks["gazeDir.gazeDir"]?.value}",
            picks["gazeDir.gazeDir"]?.value == "躺着侧过身看镜头"
        )
    }
}
