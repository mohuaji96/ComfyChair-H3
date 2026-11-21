package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 场地依赖。
 *
 * 过去场景为空时 fitsSurface 直接放行，而且锚点根本不查场地：没有墙也能写「侧身靠墙」，
 * 森林的地上也能埋进枕头。现在空场景按「只有地面」算，锚点也过一遍场地。
 */
class SurfaceGateTest {

    private fun library() = TagLibraryJson.parse(
        File("src/main/assets/tag_catalog.json").readText(Charsets.UTF_8)
    )

    private fun engine(): TagComposerEngine {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagComposerEngine(library())
    }

    private fun base(place: String? = null) = mutableMapOf<String, TagPick>().apply {
        put("people.count", TagPick("单人女孩", locked = true))
        if (place != null) put("scene.place", TagPick(place, locked = true))
    }

    @Test
    fun aWallPoseNeedsAWallEvenWithoutAScene() {
        val e = engine()
        val why = e.strictBlockedWords("pose", "body", base())["侧身靠墙"]
        assertNotNull("没有场景时不能出现靠墙的姿态", why)
        assertTrue(why!!, why.contains("支撑面"))
    }

    @Test
    fun lyingOnTheFloorStillWorksWithoutAScene() {
        val e = engine()
        assertNull("仰躺只需要地面", e.strictBlockedWords("pose", "body", base())["仰躺"])
    }

    @Test
    fun aForestCannotHoldABedsheetPose() {
        val e = engine()
        assertNotNull(
            "森林只有站立与地面",
            e.strictBlockedWords("pose", "body", base("森林"))["趴在皱床单上"]
        )
    }

    @Test
    fun aBedroomHoldsTheSamePose() {
        val e = engine()
        assertNull(
            "卧室有床",
            e.strictBlockedWords("pose", "body", base("卧室"))["趴在皱床单上"]
        )
    }

    @Test
    fun anIgnoredSceneReleasesTheChecks() {
        val e = engine()
        val picks = base()
        picks["scene.place"] = TagPick(ignored = true)
        assertNull("关掉场景是明确的选择，界面也写明了", e.strictBlockedWords("pose", "body", picks)["侧身靠墙"])
    }

    @Test
    fun theAnchorYieldsToTheScene() {
        val e = engine()
        val lib = library()
        val forestSurfaces = setOf("stand", "floor")
        repeat(20) {
            val picks = base("森林")
            e.randomize(picks)
            val act = picks["sexAct.act"]?.value.orEmpty()
            val needs = lib.constraints.poseNeeds[act].orEmpty()
            assertTrue("$act 需要 $needs，森林撑不住", needs.all { it in forestSurfaces })
        }
    }

    @Test
    fun aDeskTraceNeedsADesk() {
        val e = engine()
        assertNotNull(
            "桌上的东西被扫到了地上 不能出现在没有桌子的地方",
            e.strictBlockedWords("sceneTrace", "trace", base())["桌上的东西被扫到了地上"]
        )
    }

    @Test
    fun everySceneDeclaresItsSurfaces() {
        val lib = library()
        val scene = lib.category("scene")!!.slots.first().pool
        val missing = scene.filter { it !in lib.constraints.placeSupports }
        assertTrue("未登记支撑面：$missing", missing.isEmpty())
    }

    @Test
    fun everySurfaceWordIsInAPool() {
        val lib = library()
        val words = lib.categories.flatMap { category -> category.slots.flatMap { it.pool } }.toSet()
        val dead = lib.constraints.poseNeeds.keys.filter { it !in words }
        assertTrue("poseNeeds 里挂着不存在的词：$dead", dead.isEmpty())
    }
}
