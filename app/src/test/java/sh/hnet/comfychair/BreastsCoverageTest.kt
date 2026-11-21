package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 乳房三格：没穿遮挡时该抽得到。
 *
 * 535 那条「穿上衣就遮形态和乳头」只写了触发词，引擎不记来源，下衣、袜子、鞋的颜色都会
 * 命中，裸上身时形态和乳头整格空。现在规则限定来源为上衣，外套敞开一类的状态另列放行。
 */
class BreastsCoverageTest {

    private fun library() = TagLibraryJson.parse(
        File("src/main/assets/tag_catalog.json").readText(Charsets.UTF_8)
    )

    private fun engine(): TagComposerEngine {
        assumeTrue(File("src/main/assets/tag_catalog.json").exists())
        return TagComposerEngine(library())
    }

    /** 取景裁掉胸部属于合理剔除，这里锁空裁切槽，只看规则本身。 */
    private fun fillRate(state: String, slot: String, extra: Map<String, TagPick> = emptyMap()): Int {
        val e = engine()
        var filled = 0
        repeat(30) {
            val picks = mutableMapOf(
                "people.count" to TagPick("单人女孩", locked = true),
                "outfitState.state" to TagPick(state, locked = true),
                "camera.crop" to TagPick("", locked = true)
            )
            picks.putAll(extra)
            e.randomize(picks)
            if (!picks["breasts.$slot"]?.value.isNullOrEmpty()) filled++
        }
        return filled
    }

    @Test
    fun theRuleOnlyListensToTheTopCategory() {
        val rule = library().skipRules.first { "breasts.shape" in it.skip }
        assertEquals(listOf("top"), rule.from)
        assertTrue("敞开的穿脱状态要放行", rule.unless.contains("衬衫敞开未扣"))
    }

    @Test
    fun aNudeWomanStillGetsShapeAndNipple() {
        val shape = fillRate("全裸", "shape")
        val nipple = fillRate("全裸", "nipple")
        assertTrue("全裸时形态不该整格空：$shape/30", shape >= 18)
        assertTrue("全裸时乳头不该整格空：$nipple/30", nipple >= 18)
    }

    @Test
    fun aBareChestStillGetsShapeAndNipple() {
        assertTrue("上身赤裸时形态不该整格空", fillRate("上身赤裸", "shape") >= 18)
    }

    @Test
    fun underwearAloneDoesNotCountAsACoveringTop() {
        assertTrue("只穿内衣时形态不该被遮", fillRate("只穿内衣", "shape") >= 15)
    }

    @Test
    fun aPlainTopStillHidesShape() {
        val filled = fillRate(
            "只穿上衣",
            "shape",
            mapOf("top.type" to TagPick("T恤", locked = true))
        )
        assertTrue("穿 T恤 时形态应被遮住，实际 $filled/30", filled <= 4)
    }

    @Test
    fun anOpenShirtReleasesTheShape() {
        val filled = fillRate(
            "衬衫敞开未扣",
            "shape",
            mapOf("top.type" to TagPick("衬衫", locked = true))
        )
        assertTrue("衬衫敞开时形态应可见，实际 $filled/30", filled >= 15)
    }

    @Test
    fun aDishevelledLookCountsAsOpen() {
        assertTrue("衣冠不整时衣服已经没遮住", fillRate("衣冠不整", "shape") >= 18)
        assertTrue("衣装半褪时衣服已经没遮住", fillRate("衣装半褪", "shape") >= 18)
    }

    @Test
    fun anExposedChestKeepsOnlyTheSize() {
        val size = fillRate("衣襟大敞，双乳外露", "size")
        val shape = fillRate("衣襟大敞，双乳外露", "shape")
        assertTrue("双乳外露时大小应照常抽，实际 $size/30", size >= 18)
        assertTrue("这一条现在只关形态，实际 $shape/30", shape <= 4)
    }
}
