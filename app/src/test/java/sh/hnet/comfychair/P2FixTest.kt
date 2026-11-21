package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/** 566 的三条：地域两侧对齐、SFW 整句型道具、四人档的女伴。 */
class P2FixTest {

    private fun library(name: String = "tag_catalog.json"): TagLibrary {
        val file = File("src/main/assets/$name")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun compose(library: TagLibrary, vararg pairs: Pair<String, String>): String {
        val picks = mutableMapOf<String, TagPick>()
        pairs.forEach { (key, value) -> picks[key] = TagPick(value) }
        return TagComposerEngine(library).compose(picks)
    }

    /** 词库组合的地域与剧情侧一致，不再有台湾香港。 */
    @Test
    fun theRegionPoolMatchesTheStorySide() {
        val ethnicity = library().category("race")!!.slots.first { it.id == "ethnicity" }.pool
        assertFalse("地域里还有台湾", "台湾" in ethnicity)
        assertFalse("地域里还有香港", "香港" in ethnicity)
        assertEquals("地域条数与剧情侧不一致", 25, ethnicity.size)
    }

    /** 四个女孩要有三位女伴的描写。 */
    @Test
    fun fourWomenAllGetTheirOwnClause() {
        val text = compose(
            library(),
            "people.count" to "四个女孩",
            "race.kind" to "人类",
            "camera.shot" to "全身照"
        )
        val companions = Regex("另一位是").findAll(text).count()
        assertEquals("四人档只写了 $companions 位女伴：$text", 3, companions)
    }
}
