package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import java.io.File

/**
 * Places and poses are drawn independently, which let a garage floor hold a bedsheet. The axis
 * only has to separate the cases that actually broke; everything unannotated stays free.
 */
class SurfaceAxisTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun clash(library: TagLibrary, place: String, pose: String): Boolean {
        val offers = library.constraints.placeSupports[place] ?: return false
        val needs = library.constraints.poseNeeds[pose] ?: return false
        return needs.none { it in offers }
    }

    @Test
    fun thePairsThatBrokeAreIncompatible() {
        val library = library()
        // 场景池这轮清掉了姿态词与小说片段，能构成「为什么不行」原始案例的只剩这两组。
        val broken = listOf(
            "地下停车场" to "手指抓紧床单",
            "温泉露天浴池" to "头靠在墙上"
        )
        broken.forEach { (place, pose) ->
            assertTrue("$place + $pose should clash", clash(library, place, pose))
        }
    }

    @Test
    fun ordinaryPairingsStayAllowed() {
        val library = library()
        val fine = listOf(
            "卧室" to "仰躺张腿",
            "卧室" to "站立",
            "浴室" to "跪在地上",
            "沙发" to "跨坐在他身上",
            "教室" to "趴在桌上",
            "阳台" to "靠墙",
            "卧室" to "趴在床上"
        )
        fine.forEach { (place, pose) ->
            assertTrue("$place + $pose must stay allowed", !clash(library, place, pose))
        }
    }

    @Test
    fun noPlaceIsLeftWithoutPoses() {
        val library = library()
        val poses = library.category("pose")!!.slots.flatMap { it.pool }
        library.constraints.placeSupports.forEach { (place, offers) ->
            val usable = poses.count { pose ->
                val needs = library.constraints.poseNeeds[pose] ?: return@count true
                needs.any { it in offers }
            }
            assertTrue("$place only allows $usable poses", usable >= 20)
        }
    }
}

