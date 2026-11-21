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
 * 跨分类互斥：单个池自己看没问题，合到一句里才露出矛盾。
 *
 * 每一条都对着审计报告里的例子：印度配雪白皮肤、马车里穿 JK、一句里两个「半脱」、
 * 一句里两处逆光。规则只锁驱动的那一格，让 repair 去判被动的那一格。
 */
class CrossCategoryTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun repair(
        library: TagLibrary,
        lock: String,
        spec: List<Pair<String, String>>
    ): MutableMap<String, TagPick> {
        val picks = mutableMapOf<String, TagPick>()
        spec.forEach { (key, value) -> picks[key] = TagPick(value, locked = key == lock) }
        val solo = library.constraints.peopleCount.filterValues { it <= 1 }.keys
        TagComposerEngine(library, solo).repair(picks)
        return picks
    }

    /** 印度不长雪白皮肤。 */
    @Test
    fun aRegionDropsTheSkinItCannotHave() {
        val library = library()
        val picks = repair(
            library,
            lock = "race.ethnicity",
            spec = listOf("race.ethnicity" to "印度", "skin.tone" to "雪白皮肤")
        )
        assertFalse("印度配雪白皮肤还留着", picks["skin.tone"]?.value == "雪白皮肤")
    }

    /** 同一个肤色在说得通的地区照样留着。 */
    @Test
    fun aNordicRegionKeepsTheFairSkin() {
        val library = library()
        val picks = repair(
            library,
            lock = "race.ethnicity",
            spec = listOf("race.ethnicity" to "北欧", "skin.tone" to "雪白皮肤")
        )
        assertTrue("北欧的雪白皮肤被误删", picks["skin.tone"]?.value == "雪白皮肤")
    }

    /** 客栈是古代场景，现代衣服要让位。 */
    @Test
    fun anAncientRoomDropsTheModernOutfit() {
        val library = library()
        val picks = repair(
            library,
            lock = "scene.place",
            spec = listOf("scene.place" to "客栈客房", "top.type" to "白衬衫")
        )
        val left = picks["top.type"]?.value.orEmpty()
        val ancient = library.constraints.eras.filterValues { it == "ancient" }.keys
        assertTrue("客栈里还穿着现代上衣：$left", left.isEmpty() || left in ancient)
    }

    /** 便利店是现代场景，古装要让位。 */
    @Test
    fun aConvenienceStoreDropsTheCostume() {
        val library = library()
        val picks = repair(
            library,
            lock = "scene.place",
            spec = listOf("scene.place" to "便利店", "top.type" to "汉服")
        )
        assertFalse("便利店还穿着汉服", picks["top.type"]?.value == "汉服")
    }

    /** 没标年代的场地不限：卧室里穿汉服照样成立。 */
    @Test
    fun anUntaggedRoomKeepsWhateverIsWorn() {
        val library = library()
        val picks = repair(
            library,
            lock = "scene.place",
            spec = listOf("scene.place" to "卧室", "top.type" to "汉服")
        )
        assertTrue("卧室里的汉服被误删", picks["top.type"]?.value == "汉服")
    }

    /** 一句里不说两遍「半脱」。 */
    @Test
    fun halfUndressedIsSaidOnce() {
        val library = library()
        val picks = repair(
            library,
            lock = "outfitState.state",
            spec = listOf("outfitState.state" to "衣装半褪", "garment.detail" to "上衣掀到胸口")
        )
        assertFalse("衣物细节又说了一遍半脱", picks["garment.detail"]?.value == "上衣掀到胸口")
    }

    /** 一句里不写两处逆光。 */
    @Test
    fun backlightIsSaidOnce() {
        val library = library()
        val picks = repair(
            library,
            lock = "grade.pos",
            spec = listOf("grade.pos" to "侧逆光", "grade.feel" to "逆光剪影调")
        )
        assertFalse("影调里又写了一遍逆光", picks["grade.feel"]?.value == "逆光剪影调")
    }
}
