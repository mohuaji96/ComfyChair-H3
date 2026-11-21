package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * Rerolling one category has to release the categories its old value was holding shut.
 *
 * 只穿内衣 switches 上衣 / 下衣 off; rerolling 穿脱 to 拉链半开 lifted that skip but left both
 * blank, because a single-category reroll only redraws that category and `repair()` never
 * refills. The player sees 未选 with no reason.
 */
class RerollReleaseTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    /** Any slot filled counts: the type slot can legitimately be empty while colour is not. */
    private fun hasAny(picks: Map<String, TagPick>, categoryId: String): Boolean =
        picks.any { (key, pick) -> key.startsWith("$categoryId.") && pick.value.isNotEmpty() }

    private fun solo(library: TagLibrary): TagComposerEngine =
        TagComposerEngine(library, library.constraints.peopleCount.filterValues { it <= 1 }.keys)

    /** Every 穿脱 word that switches 上衣 or 下衣 off. */
    private fun clothesOff(library: TagLibrary): Set<String> =
        library.skipRules
            .filter { "top" in it.skip || "bottom" in it.skip }
            .flatMap { it.whenAny }
            .toSet()

    @Test
    fun liftingASkipBringsTheClothesBack() {
        val library = library()
        val engine = solo(library)
        val off = clothesOff(library)
        val picks = mutableMapOf<String, TagPick>(
            "outfitState.state" to TagPick("只穿内衣"),
            "top.type" to TagPick("衬衫"),
            "bottom.type" to TagPick("牛仔裤")
        )
        engine.repair(picks)
        assertTrue("setup: 上衣应为空", picks["top.type"]?.value.isNullOrEmpty())

        var tries = 0
        while (tries < 40 && picks["outfitState.state"]?.value in off) {
            engine.randomize(picks, "outfitState")
            tries++
        }
        val state = picks["outfitState.state"]?.value.orEmpty()
        assertTrue("穿脱停在「$state」，本来就是跳过的", state.isNotEmpty() && state !in off)
        val skip = engine.skipped(picks)
        if ("top" !in skip) assertTrue("上衣还是空的：${engine.drawNote()}", hasAny(picks, "top"))
        if ("bottom" !in skip) assertTrue("下衣还是空的：${engine.drawNote()}", hasAny(picks, "bottom"))
    }

    @Test
    fun aManuallyClearedSlotStaysCleared() {
        val library = library()
        val engine = solo(library)
        val picks = mutableMapOf<String, TagPick>(
            "outfitState.state" to TagPick("穿得整齐"),
            "top.type" to TagPick("")
        )
        engine.randomize(picks, "outfitState")
        assertTrue(
            "手动清空的上衣被补回来了：${picks["top.type"]?.value}",
            picks["top.type"]?.value.isNullOrEmpty()
        )
    }

    @Test
    fun aFullDrawFollowedByARerollNeverStrandsTheClothes() {
        val library = library()
        val engine = solo(library)
        val off = clothesOff(library)
        val picks = mutableMapOf<String, TagPick>()

        repeat(30) {
            engine.randomize(picks)
            engine.randomize(picks, "outfitState")
            val state = picks["outfitState.state"]?.value.orEmpty()
            if (state in off) return@repeat
            val top = if (hasAny(picks, "top")) "x" else ""
            val bottom = if (hasAny(picks, "bottom")) "x" else ""
            // A word drawn elsewhere (连体衣, 泳衣) can switch a category off just as well, so the
            // question is only whether a category that is NOT skipped came out blank.
            val skip = engine.skipped(picks)
            if ("top" !in skip) {
                assertTrue("上衣空着而穿脱是「$state」${engine.drawNote()}", top.isNotEmpty())
            }
            if ("bottom" !in skip) {
                assertTrue("下衣空着而穿脱是「$state」${engine.drawNote()}", bottom.isNotEmpty())
            }
        }
    }
}
