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

/** 564 的五条：妆容归位、皮带词性、道具场地、死词、下垂与挺起。 */
class P1FixTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun compose(vararg pairs: Pair<String, String>): String {
        val picks = mutableMapOf<String, TagPick>()
        pairs.forEach { (key, value) -> picks[key] = TagPick(value) }
        return TagComposerEngine(library()).compose(picks)
    }

    private fun repair(
        lock: String,
        spec: List<Pair<String, String>>
    ): MutableMap<String, TagPick> {
        val library = library()
        val picks = mutableMapOf<String, TagPick>()
        spec.forEach { (key, value) -> picks[key] = TagPick(value, locked = key == lock) }
        TagComposerEngine(library).repair(picks)
        return picks
    }

    /** 妆容不再混在穿脱状态里。 */
    @Test
    fun makeupLeavesTheUndressSlot() {
        val library = library()
        val states = library.category("outfitState")!!.slots.first().pool
        val face = library.category("person")!!.slots.first { it.id == "face" }.pool
        val leftBehind = states.filter { it.contains("妆") || it.contains("口红") || it.contains("眼线") }
        assertTrue("穿脱格里还留着妆容：$leftBehind", leftBehind.isEmpty())
        assertTrue("妆容没进面部格", "口红花了" in face)
    }

    /** 皮带是系着的，不是戴着的。 */
    @Test
    fun aBeltIsTiedOn() {
        val text = compose("jewelry.item" to "皮带")
        assertTrue("皮带没写成系着：$text", text.contains("系着皮带"))
        assertFalse("皮带还写成戴着：$text", text.contains("戴着皮带"))
    }

    /** 森林没有顶棚，吊环不该出现。 */
    @Test
    fun aHoistNeedsACeiling() {
        val picks = repair("scene.place", listOf("scene.place" to "森林", "toys.item" to "悬吊吊环"))
        assertFalse("森林里还挂着吊环", picks["toys.item"]?.value == "悬吊吊环")
    }

    /** 卧室有顶棚，吊环可以留。 */
    @Test
    fun aHoistSurvivesUnderACeiling() {
        val picks = repair("scene.place", listOf("scene.place" to "卧室", "toys.item" to "悬吊吊环"))
        assertTrue("卧室里的吊环被误删", picks["toys.item"]?.value == "悬吊吊环")
    }

    /** 极度下垂与「被冷风激得挺起」不再同句。 */
    @Test
    fun saggingDoesNotRiseAtTheSameTime() {
        val picks = repair("breasts.shape", listOf("breasts.shape" to "极度下垂", "breasts.nipple" to "被冷风激得挺起"))
        assertFalse("下垂又挺起", picks["breasts.nipple"]?.value == "被冷风激得挺起")
    }
}
