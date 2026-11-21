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
 * 词性：一句里该用哪个动词，不能按整类一刀切。
 *
 * 这几条都摘自连抽八次打出来的真实句子：「戴着创可贴」「拿着拘束椅」「穿米色」
 * 「棕褐皮肤的汗水浸透的皮肤」。
 */
class WordClassTest {

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

    /** 创可贴是贴的，不是戴的。 */
    @Test
    fun aBandageIsStuckOnNotWorn() {
        val text = compose("jewelry.item" to "创可贴")
        assertTrue("创可贴没写成贴着：$text", text.contains("贴着创可贴"))
        assertFalse("创可贴还挂在戴着下面：$text", text.contains("戴着创可贴"))
    }

    /** 拘束椅是摆在旁边的，不是拿在手上。 */
    @Test
    fun aBondageChairIsPlacedNotHeld() {
        val text = compose("toys.item" to "拘束椅")
        assertTrue("拘束椅没写成旁边摆着：$text", text.contains("旁边摆着拘束椅"))
        assertFalse("拘束椅还写成拿着：$text", text.contains("拿着拘束椅"))
    }

    /** 手套这类正常道具仍按「拿着」。 */
    @Test
    fun aHeldToyStaysHeld() {
        val text = compose("toys.item" to "跳蛋")
        assertTrue("跳蛋没写成拿着：$text", text.contains("拿着跳蛋"))
    }

    /** 没有款式的时候颜色不出场。 */
    @Test
    fun aColourWithoutAGarmentIsNotClothing() {
        val text = compose("top.color" to "米色")
        assertFalse("颜色单独变成了衣服：$text", text.contains("穿米色"))
    }

    /** 颜色和款式都在时，颜色照常落在衣服上。 */
    @Test
    fun aColourSticksToItsGarment() {
        val text = compose("top.color" to "米色", "top.type" to "毛衣")
        assertTrue("颜色没落到衣服上：$text", text.contains("米色"))
        assertTrue("款式没出来：$text", text.contains("毛衣"))
    }

    /** 皮肤状态已经带名词，色调不再重复说皮肤。 */
    @Test
    fun skinColourIsNotSaidTwice() {
        val text = compose("skin.tone" to "棕褐皮肤", "skin.state" to "汗水浸透的皮肤")
        assertFalse("皮肤说了两遍：$text", text.contains("棕褐皮肤的汗水"))
        assertTrue("肤色没落到颜色上：$text", text.contains("棕褐"))
    }

    /** 眼镜的动作写法改成名词短语，才接得上「戴着」。 */
    @Test
    fun glassesStateIsANounPhrase() {
        val text = compose("headwear.eyewear" to "推在发间的墨镜")
        assertTrue("眼镜短语没接上戴着：$text", text.contains("戴着推在发间的墨镜"))
    }
}
