package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagDefaults
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 8303 reshaped the library: one word deleted, the palette slot off by default, the 画幅
 * category folded into 构图. These are the invariants that keep the reshuffle honest.
 */
class LibraryShapeTest {

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
    fun aWordRemovedFromTheLibraryDoesNotSurviveInASavedPick() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>("bodyType.type" to TagPick("胸肌缝隙"))
        engine.repair(picks)
        assertFalse(
            "a deleted word must not linger",
            picks["bodyType.type"]?.value == "胸肌缝隙"
        )
    }

    @Test
    fun thePaletteSlotStartsSwitchedOff() {
        val library = library()
        assertTrue(
            "grade.palette should be listed as a default-off slot",
            "grade.palette" in library.defaultIgnored
        )
        val out = TagDefaults.ignored(library, emptyMap())
        assertTrue("palette should be ignored", out["grade.palette"]?.ignored == true)
        assertFalse("the style itself stays on", out["mood.medium"]?.ignored == true)
        assertFalse("grading stays on", out["grade.feel"]?.ignored == true)
    }

    @Test
    fun lockingThePaletteOutranksTheDefault() {
        val library = library()
        val existing = mapOf("grade.palette" to TagPick("冷青与乳白", locked = true))
        val out = TagDefaults.ignored(library, existing)
        assertFalse("a locked choice wins", out["grade.palette"]?.ignored == true)
    }

    @Test
    fun theAspectRatioCategoryIsGone() {
        val library = library()
        assertNull("画幅 should be folded into 构图", library.category("frame"))
        val camera = library.category("camera")
        assertTrue(
            "竖幅全身 must still exist somewhere",
            camera?.slots?.any { "竖幅全身" in it.pool } == true
        )
    }
}
