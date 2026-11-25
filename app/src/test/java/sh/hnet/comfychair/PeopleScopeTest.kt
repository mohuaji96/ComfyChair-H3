package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * `constraints.people` is keyed by the word's text, so a word shared by a male category and a
 * female one carries the male meaning everywhere. 全裸 meant "there is a man in frame" for the
 * male wardrobe and made the female 穿脱 word unselectable under 单人.
 */
class PeopleScopeTest {

    private fun library(): TagLibrary? {
        val text = listOf(
            File("src/main/assets/tag_catalog.json"),
            File("app/src/main/assets/tag_catalog.json")
        ).firstOrNull { it.exists() }?.readText(Charsets.UTF_8) ?: return null
        return TagLibraryJson.parse(text)
    }

    @Test
    fun aWordCanNeedOneBodyHereAndTwoThere() {
        val lib = library() ?: return
        // The female state means one woman with nothing on.
        assertEquals(1, lib.peopleNeeded("outfitState", "全裸"))
        assertEquals(1, lib.peopleNeeded("outfitState", "只穿内裤"))
        assertEquals(1, lib.peopleNeeded("top", "浴袍"))
        assertEquals(1, lib.peopleNeeded("bodyType", "微胖"))
        // 男方那些衣服词不再单独标人数：男方句只在有男性时才会拼出来，这一格没有人可数。
        assertEquals(0, lib.peopleNeeded("maleBody", "背心短裤"))
    }

    @Test
    fun aSoloCastCanStillDrawFullNudity() {
        val lib = library()
        assumeTrue("catalog asset not reachable from this working dir", lib != null)
        val engine = TagComposerEngine(lib!!)
        var reached = 0
        repeat(200) {
            val picks = mutableMapOf<String, TagPick>("people.count" to TagPick("单人", locked = true))
            engine.randomize(picks, "outfitState")
            if (picks["outfitState.state"]?.value == "全裸") reached++
        }
        assertTrue("全裸 must stay reachable under 单人, got $reached/200", reached > 0)
    }

    /**
     * 全裸 no longer carries `requires: 男`, so the male wardrobe has to be held back by its own
     * category instead — otherwise a two-woman scene would get his clothes.
     */
    @Test
    fun theMaleWardrobeStaysOutOfAnAllFemaleCast() {
        val lib = library() ?: return
        val engine = TagComposerEngine(lib)
        val leaked = mutableSetOf<String>()
        repeat(80) {
            val picks = mutableMapOf<String, TagPick>("people.count" to TagPick("两个女孩", locked = true))
            engine.randomize(picks)
            picks.filterKeys { it.startsWith("male") }
                .filterValues { it.value.isNotEmpty() }
                .forEach { (key, pick) -> leaked += "$key=${pick.value}" }
        }
        assertTrue("男性分类泄漏到全女场景：${leaked.take(6)}", leaked.isEmpty())
    }

    /**
     * The pinned-common list used to name words that had moved or never existed, and the panel
     * dropped them without a word: the slot looked like it only had five words.
     */
    @Test
    fun commonFirstPointsAtWordsThatExist() {
        val lib = library() ?: return
        val dead = mutableListOf<String>()
        lib.commonFirst.forEach { (key, words) ->
            val parts = key.split(".")
            val slot = lib.category(parts.getOrElse(0) { "" })
                ?.slots
                ?.firstOrNull { it.id == parts.getOrElse(1) { "" } }
            if (slot == null) {
                dead += "$key [槽不存在]"
            } else {
                words.filter { it !in slot.pool }.forEach { dead += "$key :: $it" }
            }
        }
        assertTrue("置顶表里有不存在的词：$dead", dead.isEmpty())
    }
}
