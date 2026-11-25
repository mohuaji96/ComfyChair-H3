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
 * 阴部遮挡：穿了下衣或内裤才遮，下身穿什么与上衣的颜色无关。
 *
 * 规则原来只列了 144 个触发词（下衣颜色、裙裤款式、内裤款式），引擎不记来源，于是
 * 上衣抽到「黑色」也会把阴部整类关掉。
 */
class PussyCoverageTest {

    private fun library() = TagLibraryJson.parse(
        File("src/main/assets/tag_catalog.json").readText(Charsets.UTF_8)
    )

    private fun engine(): TagComposerEngine {
        assumeTrue(File("src/main/assets/tag_catalog.json").exists())
        return TagComposerEngine(library())
    }

    private fun filled(extra: Map<String, TagPick>, times: Int = 30): Int {
        val e = engine()
        var hits = 0
        repeat(times) {
            // 取景各格都锁空：胸部特写、腰部以上这类会把阴部裁出画外，那属于合理剔除，
            // 这里只看规则本身有没有冤枉她。
            val picks = mutableMapOf(
                "people.count" to TagPick("单人女孩", locked = true),
                "camera.shot" to TagPick("", locked = true),
                "camera.angle" to TagPick("", locked = true),
                "camera.frame" to TagPick("", locked = true),
                "camera.lens" to TagPick("", locked = true),
                // 下衣也锁空：穿着裙子本来就把阴部挡住，那不是规则冤枉她。
                "bottom.type" to TagPick("", locked = true)
            )
            picks.putAll(extra)
            e.randomize(picks)
            if (!picks["pussy.look"]?.value.isNullOrEmpty()) hits++
        }
        return hits
    }

    /**
     * 遮挡来源是三格，不是两格。
     *
     * 婚纱、晚礼服、连体衣这些一件式躺在「上衣款式」格里，它们会主动关掉下衣与内裤两格，
     * 规则找不到下装来源就当没穿，于是穿着长裙也照写阴部。上衣款式必须一起看。
     */
    @Test
    fun theRuleListensToTopsBottomsAndPanties() {
        val rule = library().skipRules.first { it.skip == listOf("pussy") }
        assertEquals(listOf("bottom.type", "panties.type", "top.type"), rule.from)
        assertTrue("无内裤 不该算遮住", rule.unless.contains("无内裤"))
        assertTrue("开档内裤 不该算遮住", rule.unless.contains("开档内裤"))
    }

    /** 上衣里只有连体衣算遮挡：T恤 不该把阴部关掉。 */
    @Test
    fun aPlainTopDoesNotCloseHer() {
        val hits = filled(
            mapOf(
                "top.type" to TagPick("T恤", locked = true),
                "bottom.type" to TagPick("", locked = true),
                "panties.type" to TagPick("", locked = true)
            )
        )
        assertTrue("穿 T恤 不该关掉阴部：$hits/30", hits >= 18)
    }

    /** 婚纱挡住全身，阴部外观不该再写。 */
    @Test
    fun aWeddingDressCoversHer() {
        val hits = filled(mapOf("top.type" to TagPick("婚纱", locked = true)))
        assertTrue("穿婚纱时阴部应被遮住：$hits/30", hits <= 4)
    }

    @Test
    fun aTopColourDoesNotCloseHer() {
        val hits = filled(
            mapOf(
                "top.type" to TagPick("T恤", locked = true),
                "top.color" to TagPick("黑色", locked = true),
                "bottom.type" to TagPick("", locked = true),
                "panties.type" to TagPick("", locked = true)
            )
        )
        assertTrue("下身穿空时，上衣是黑色也不该关掉阴部：$hits/30", hits >= 18)
    }

    @Test
    fun pantiesStillCoverHer() {
        val hits = filled(mapOf("panties.type" to TagPick("三角裤", locked = true)))
        assertTrue("穿着内裤时阴部应被遮住：$hits/30", hits <= 4)
    }

    /**
     * 绝对次数不稳：手部预算、遮挡型穿脱状态都会让阴部那一格空着，那不是规则的锅。
     * 改成相对比较——露出状态要比穿内裤时明显更常出现。
     */
    @Test
    fun noPantiesLeavesHerOpenFarMoreOftenThanPanties() {
        val open = filled(mapOf("panties.type" to TagPick("无内裤", locked = true)))
        val covered = filled(mapOf("panties.type" to TagPick("三角裤", locked = true)))
        assertTrue("无内裤 $open 次、三角裤 $covered 次，差距不足", open - covered >= 10)
    }
}
