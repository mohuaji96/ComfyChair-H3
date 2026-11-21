package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 自带颜色的款式（白衬衫）不该再被颜色格加前缀。
 *
 * 颜色格与款式格独立抽取，拼句时颜色无条件当「的」前缀，于是出现「黑色的白衬衫」。
 * 登记表只收词面写明颜色的款式：青城弟子服、青丘侍女裙的「青」是地名，不算。
 */
class SelfColouredTest {

    private fun library() = TagLibraryJson.parse(
        File("src/main/assets/tag_catalog.json").readText(Charsets.UTF_8)
    )

    private fun engine(): TagComposerEngine {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagComposerEngine(library())
    }

    @Test
    fun theCatalogueListsColouredGarments() {
        val lib = library()
        listOf("白衬衫", "湿透白衬衫", "白大褂", "雪灵仙子白衣", "幽冥圣女黑袍", "小白鞋").forEach {
            assertTrue("$it 应登记为自带颜色", it in lib.constraints.selfColoured)
        }
        // 地名与意象不能靠首字判断。
        listOf("青城弟子服", "青丘侍女裙", "红莲妖姬套装").forEach {
            assertFalse("$it 不是自带颜色", it in lib.constraints.selfColoured)
        }
    }

    @Test
    fun aColouredTypeLeavesTheColourSlotEmpty() {
        val e = engine()
        repeat(20) {
            val picks = mutableMapOf(
                "people.count" to TagPick("单人女孩", locked = true),
                "top.type" to TagPick("白衬衫", locked = true)
            )
            e.randomize(picks, "top")
            assertEquals(
                "白衬衫 自带白色，颜色格不该再抽：${picks["top.color"]?.value}",
                "",
                picks["top.color"]?.value.orEmpty()
            )
        }
    }

    @Test
    fun anExistingColourIsDroppedByRepair() {
        val e = engine()
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "top.color" to TagPick("黑色"),
            "top.type" to TagPick("白衬衫")
        )
        e.repair(picks)
        assertEquals("", picks["top.color"]?.value.orEmpty())
    }

    @Test
    fun thePromptNeverSaysBlackWhiteShirt() {
        val e = engine()
        repeat(30) {
            // 颜色被手锁住时不清格，但句子仍以款式为准。
            val picks = mutableMapOf(
                "people.count" to TagPick("单人女孩", locked = true),
                "outfitState.state" to TagPick("衣装半褪", locked = true),
                "top.type" to TagPick("白衬衫", locked = true),
                "top.color" to TagPick("黑色", locked = true)
            )
            e.randomize(picks)
            val prompt = e.compose(picks)
            assertFalse(prompt, prompt.contains("黑色的白衬衫"))
            assertTrue(prompt, prompt.contains("白衬衫"))
        }
    }

    @Test
    fun aSelfColouredStyleBlocksEveryColour() {
        val e = engine()
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "top.type" to TagPick("白衬衫", locked = true)
        )
        val blocked = e.blockedWords("top", "color", picks)
        assertTrue("颜色格应当整格让位", blocked.isNotEmpty())
        assertTrue("白色 也不该叠", "白色" in blocked)
    }
}
