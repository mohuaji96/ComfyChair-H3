package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.RequirementFiller
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/** Renames must not empty an old save, and a locked provider must say so instead of blaming the catalog. */
class RenameAndLockTest {

    private fun library() = TagLibraryJson.parse(
        listOf(
            File("src/main/assets/tag_catalog.json"),
            File("app/src/main/assets/tag_catalog.json")
        ).first { it.exists() }.readText(Charsets.UTF_8)
    )

    @Test
    fun anOldGradeWordFollowsTheRename() {
        val lib = library() ?: return
        assertEquals("灰蓝低饱和", lib.canonicalWord("冷灰低饱和"))
        assertEquals("夜色蓝调", lib.canonicalWord("蓝调夜色滤镜"))
        assertEquals("粉白高光", lib.canonicalWord("粉白高亮"))
        // A word nobody renamed stays itself.
        assertEquals("青橙电影调色", lib.canonicalWord("青橙电影调色"))
    }

    @Test
    fun renamedWordsAreNoLongerInThePool() {
        val lib = library() ?: return
        val feel = lib.category("grade")?.slots?.first { it.id == "feel" }?.pool.orEmpty()
        listOf("冷灰低饱和", "蓝调夜色滤镜", "粉白高亮", "莫兰迪低饱和").forEach {
            assertTrue("$it should be gone", it !in feel)
        }
        assertTrue("灰蓝低饱和" in feel)
        assertTrue("莫兰迪灰调" in feel)
    }

    @Test
    fun aLockedProviderIsNamedNotBlamed() {
        val lib = library() ?: return
        val needs = lib.constraints.requires["只披衬衫"].orEmpty()
        val picks = mapOf("top.type" to TagPick("针织衫", locked = true))
        assertEquals("上衣", RequirementFiller.lockedProvider(lib, needs, "outfitState", picks))
        // Unlocked but occupied is the normal case: the filler is allowed to replace it.
        var top = TagPick("针织衫")
        assertNull(
            RequirementFiller.lockedProvider(
                lib, needs, "outfitState", mapOf("top.type" to top)
            )
        )
        top = top.copy(value = "白衬衫")
        assertNull(
            RequirementFiller.lockedProvider(
                lib, needs, "outfitState", mapOf("top.type" to top)
            )
        )
    }

    @Test
    fun nudityAlsoDropsTheSixLateFindings() {
        val lib = library() ?: return
        val engine = TagComposerEngine(lib)
        val nude = mutableMapOf<String, TagPick>("outfitState.state" to TagPick("全裸", locked = true))
        mapOf(
            "maleHand" to "chest" to "隔着衣物揉着她的胸",
            "pose" to "body" to "衣领被抓按在墙角",
            "sexAct" to "act" to "隔着衣服揉胸",
            "fluids" to "type" to "精液在衣服上"
        ).forEach { (key, word) ->
            val (category, slot) = key
            assertTrue("$word should be blocked by 全裸", word in engine.blockedWords(category, slot, nude))
        }
    }
}
