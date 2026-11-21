package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 全裸 used to skip the wardrobe categories only, so "透过衣服隐约可见" survived next to a fully
 * nude body. Same class of gap for bed words: they were never part of poseNeeds.
 */
class NudityAndSurfaceTest {

    private fun library() = TagLibraryJson.parse(
        listOf(
            File("src/main/assets/tag_catalog.json"),
            File("app/src/main/assets/tag_catalog.json")
        ).first { it.exists() }.readText(Charsets.UTF_8)
    )

    private val clothingWords = listOf(
        "隔着衣服顶出的乳尖",
        "透过衣服隐约可见",
        "乳头被衣物摩擦得凸起",
        "隔着薄衣服顶出乳尖",
        "乳头刚被衣服摩擦过"
    )

    @Test
    fun fullNudityDropsWordsThatNeedClothes() {
        val lib = library() ?: return
        val engine = TagComposerEngine(lib)
        val picks = mutableMapOf<String, TagPick>("outfitState.state" to TagPick("全裸", locked = true))
        val blocked = engine.blockedWords("garment", "detail", picks)
        clothingWords.forEach { assertTrue("$it should be blocked", it in blocked) }
        assertFalse("乳头完全露在外面 is what nudity looks like", "乳头完全露在外面" in blocked)
    }

    @Test
    fun theSameWordsSurviveWhenClothed() {
        val lib = library() ?: return
        val engine = TagComposerEngine(lib)
        val picks = mutableMapOf<String, TagPick>("outfitState.state" to TagPick("只穿内衣", locked = true))
        val blocked = engine.blockedWords("garment", "detail", picks)
        clothingWords.forEach { assertFalse("$it should be fine while dressed", it in blocked) }
    }

    @Test
    fun aPillowWordNeedsABed() {
        val lib = library() ?: return
        val engine = TagComposerEngine(lib)
        // Surface checks live in 增强 only, so blockedWords (the 灰词 list) cannot answer this;
        // drawing is the honest test.
        var seenInBathroom = 0
        // 60 次 × 12 词池，命中率约 99.5%；抽样偶尔整轮空手，测试就会假报警。加到 240 次。
        repeat(240) {
            val picks = mutableMapOf<String, TagPick>("scene.place" to TagPick("浴室", locked = true))
            engine.randomize(picks, "hair")
            if (picks["hair.state"]?.value == "头发铺散在枕上") seenInBathroom++
        }
        assertTrue("浴室 must not produce a pillow word", seenInBathroom == 0)

        var seenOnBed = 0
        repeat(240) {
            val picks = mutableMapOf<String, TagPick>("scene.place" to TagPick("凌乱的床", locked = true))
            engine.randomize(picks, "hair")
            if (picks["hair.state"]?.value == "头发铺散在枕上") seenOnBed++
        }
        assertTrue("凌乱的床 should allow it", seenOnBed > 0)
    }
}
