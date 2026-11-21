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
 * 8296 built the surface axis for poses and left all 131 sex acts unmarked, so a mirror act
 * could happen in a taxi. These are the four data fixes that closed the remaining gaps.
 */
class ActSceneAxisTest {

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
    fun aMirrorActCannotHappenInAnOffice() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "scene.place" to TagPick("办公室", locked = true),
            "sexAct.act" to TagPick("手撑镜后入")
        )
        engine.repair(picks)
        assertFalse(
            "办公室 offers no wall",
            picks["sexAct.act"]?.value == "手撑镜后入"
        )
    }

    @Test
    fun everyActWithASurfaceNeedIsReachableSomewhere() {
        val library = library()
        val needs = library.constraints.poseNeeds
        val offers = library.constraints.placeSupports.values.flatten().toSet()
        // The engine accepts a pose when any one of its needs is offered, so a dangling token
        // is harmless at runtime — but it advertises a capability no place has, which is how
        // 悬空 looked like six unreachable poses. Keep the table honest instead.
        needs.values.flatten().forEach { support ->
            assertTrue("no place offers $support", support in offers)
        }
    }

    @Test
    fun theProtagonistIsNeverTheSpectator() {
        val library = library()
        val crowd = library.constraints.peopleCount.filterValues { it >= 3 }.keys
        assumeTrue(crowd.isNotEmpty())
        repeat(200) {
            val picks = mutableMapOf<String, TagPick>()
            TagComposerEngine(library, crowd).randomize(picks)
            assertFalse(
                "旁观 belongs to the supporting cast",
                picks["sexAct.act"]?.value == "旁观"
            )
        }
    }

    @Test
    fun aMaskLeavesNoRoomForAGag() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "headwear.item" to TagPick("口罩", locked = true),
            "sexAct.act" to TagPick("口塞")
        )
        engine.repair(picks)
        // The slot may re-draw, it just cannot hold another mouth tag.
        assertFalse(
            "口塞 needs the mouth 口罩 already covers",
            picks["sexAct.act"]?.value == "口塞"
        )
    }

    @Test
    fun aBittenLipLeavesNoRoomForAFingerInTheMouth() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "expression.face" to TagPick("咬着下唇忍住声音", locked = true),
            "handAction.hand" to TagPick("手指含齿间")
        )
        engine.repair(picks)
        // Re-drawing to a hand action that leaves the mouth free is fine; another mouth tag
        // is not, so the check is on the cost table rather than on an empty slot.
        val redrawn = picks["handAction.hand"]?.value.orEmpty()
        assertTrue(
            "mouth already busy, but got $redrawn",
            redrawn.isEmpty() || library.constraints.mouthCost[redrawn] == null
        )
    }

    @Test
    fun lookingDownCannotLookIntoTheCamera() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "headPose.pose" to TagPick("低头", locked = true),
            "gazeDir.gazeDir" to TagPick("看镜头")
        )
        engine.repair(picks)
        assertFalse(
            "低头 cannot look into the camera",
            picks["gazeDir.gazeDir"]?.value == "看镜头"
        )
    }

    @Test
    fun theToyHandcuffsRemoveTheHandAction() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "toys.item" to TagPick("手铐", locked = true),
            "handAction.hand" to TagPick("手放自己腿上")
        )
        engine.repair(picks)
        assertEquals("", picks["handAction.hand"]?.value.orEmpty())
    }
}
