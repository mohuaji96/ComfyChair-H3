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

/** 563 的五条硬伤，每条都对着一次真实抽样里的句子。 */
class P0FixTest {

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

    /** 他身上本来就没穿，不要再写「穿着」。 */
    @Test
    fun aBareManIsNotDressedInBareness() {
        val text = compose("people.count" to "一男一女", "maleBody.outfit" to "身上什么也没穿")
        assertFalse("出现了「穿着身上什么也没穿」：$text", text.contains("穿着身上什么也没穿"))
        assertTrue("裸体句没出来：$text", text.contains("身上什么也没穿"))
    }

    /** 只用毛巾挡着，不该同时穿着上衣。 */
    @Test
    fun aTowelDropsTheTop() {
        val picks = repair("outfitState.state", listOf("outfitState.state" to "毛巾遮在胸前", "top.type" to "毛衣"))
        assertTrue("毛巾遮胸还穿着上衣", picks["top.type"]?.value.isNullOrEmpty())
    }

    /** 床单裹身同理。 */
    @Test
    fun aBedsheetDropsTheTop() {
        val picks = repair("outfitState.state", listOf("outfitState.state" to "床单松散裹身", "top.type" to "毛衣"))
        assertTrue("床单裹身还穿着上衣", picks["top.type"]?.value.isNullOrEmpty())
    }

    /** 身材丰满与胸围丰满胸部不再叠着说。 */
    @Test
    fun plumpBodyDoesNotRepeatTheBust() {
        val picks = repair("bodyType.type", listOf("bodyType.type" to "丰满", "breasts.size" to "丰满胸部"))
        assertFalse("丰满说了两遍", picks["breasts.size"]?.value == "丰满胸部")
    }

    /** 豹纹自带图案，不再挂颜色。 */
    @Test
    fun aLeopardPrintDropsTheColour() {
        val library = library()
        val engine = TagComposerEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "panties.type" to TagPick("豹纹内裤"),
            "panties.color" to TagPick("白色")
        )
        engine.repair(picks)
        assertFalse("豹纹还挂了颜色", picks["panties.color"]?.value == "白色")
    }
}
