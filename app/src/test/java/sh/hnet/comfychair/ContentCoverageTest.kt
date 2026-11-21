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
 * Four holes found in the 8303 sample: body-part names hidden inside the 痕迹 category, the
 * toy and activity versions of mouth tags missing from the budget, one framing word left out
 * of the table, and locomotion never checked against posture.
 */
class ContentCoverageTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun soloEngine(library: TagLibrary): TagComposerEngine {
        val solo = library.constraints.peopleCount.filterValues { it <= 1 }.keys
        return TagComposerEngine(library, solo)
    }

    private fun survives(library: TagLibrary, lockedKey: String, locked: String, otherKey: String, other: String): String? {
        val engine = soloEngine(library)
        val picks = mutableMapOf(
            lockedKey to TagPick(locked, locked = true),
            otherKey to TagPick(other)
        )
        engine.repair(picks)
        return picks[otherKey]?.value
    }

    @Test
    fun aWaistUpShotHasNoThighs() {
        // 臀部与腰腹已并入身材，腿仍然是独立分类：腰部以上特写里不该留下大腿词。
        val library = library()
        val left = survives(library, "camera.crop", "腰部以上特写", "legs.detail", "肉感大腿")
        assertFalse("肉感大腿 survived a waist-up crop", left == "肉感大腿")
    }

    @Test
    fun outOfFrameWaistHasNoGenitalMarks() {
        val library = library()
        val left = survives(library, "camera.crop", "下半身在画外", "skin.mark", "阴部红肿")
        assertFalse("阴部红肿 survived", left == "阴部红肿")
    }

    @Test
    fun shouldersOnlyHasNoMilkTrail() {
        val library = library()
        val left = survives(library, "camera.crop", "只露出肩部", "fluids.type", "乳汁顺着乳尖滴")
        assertFalse("乳汁 survived a shoulders-only crop", left == "乳汁顺着乳尖滴")
    }

    @Test
    fun eyesOnlyHasNoSuckling() {
        val library = library()
        val left = survives(library, "camera.frame", "只露出眼睛以上的偷看", "sexAct.act", "吮吸乳房")
        assertFalse("吮吸乳房 survived an eyes-only shot", left == "吮吸乳房")
    }

    @Test
    fun theToyGagFillsTheMouthToo() {
        val library = library()
        val left = survives(library, "toys.item", "束带口塞", "handAction.hand", "手指抵唇")
        assertFalse("a gaged mouth still held a finger", left == "手指抵唇")
    }

    @Test
    fun aGlassAtTheLipsFillsTheMouth() {
        val library = library()
        val left = survives(library, "activity.doing", "酒杯举到唇边", "handAction.hand", "咬手指")
        assertFalse("a glass and a bitten finger", left == "咬手指")
    }

    @Test
    fun walkingCannotHappenWhileKneeling() {
        val library = library()
        val left = survives(library, "activity.doing", "行走", "pose.body", "跪坐夹腿")
        assertFalse("walking while kneeling", left == "跪坐夹腿")
    }

    @Test
    fun crossLeggedCannotWalk() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf(
            "pose.body" to TagPick("盘腿", locked = true),
            "activity.doing" to TagPick("行走")
        )
        engine.repair(picks)
        assertTrue("盘腿 is sit", picks["pose.body"]?.value == "盘腿")
        assertFalse("sitting cross-legged while walking", picks["activity.doing"]?.value == "行走")
    }

    @Test
    fun dancingNeedsAFloorNotATub() {
        val library = library()
        val left = survives(library, "scene.place", "浴缸", "activity.doing", "跳舞")
        assertFalse("dancing in a bathtub", left == "跳舞")
    }
}
