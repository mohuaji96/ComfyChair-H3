package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 身份分类已经整个删掉，异族也不会再被塞一个职业。
 *
 * 这里守两件事：词库里真的没有身份格了；异族的地域只当长相写（韩国面容），
 * 不会被拼成「韩国精灵」这种国籍 + 种族的组合。
 */
class RaceNamingTest {

    private fun realLibrary(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    @Test
    fun theLibraryNoLongerHasAJobSlot() {
        val lib = realLibrary()
        val person = lib.categories.first { it.id == "person" }
        assertEquals("形象", person.label)
        assertEquals(listOf("face", "age", "look"), person.slots.map { it.id })
        assertEquals(listOf("妆容", "年龄感", "气质"), person.slots.map { it.label })
        assertTrue(
            "还有分类留着身份格：" + lib.categories.filter { c -> c.slots.any { it.id == "origin" } }.map { it.id },
            lib.categories.none { c -> c.slots.any { it.id == "origin" } }
        )
        assertTrue("妆容应当已并入形象", lib.categories.none { it.id == "makeup" })
    }

    @Test
    fun aFantasyRegionIsAFaceNotANationality() {
        val lib = realLibrary()
        val engine = TagComposerEngine(lib)
        val race = lib.categories.first { it.id == "race" }
        val text = engine.phrase(race, mapOf("kind" to "精灵", "ethnicity" to "韩国"))
        assertTrue("地域要当长相写，实际是 $text", text.contains("韩国面容"))
        assertFalse("不该拼成国籍 + 种族，实际是 $text", text.contains("韩国精灵"))
    }

    @Test
    fun ageDoesNotHangOnTheLookAsAPossessive() {
        val library = TagLibrary(
            categories = listOf(
                TagCategory("people", "人数", "", listOf(TagSlot("count", "人数", listOf("单人")))),
                TagCategory(
                    "race", "种族", "list", who = "female",
                    slots = listOf(TagSlot("kind", "种类", listOf("人类", "精灵")))
                ),
                TagCategory(
                    "person", "形象", "person", who = "female",
                    slots = listOf(
                        TagSlot("look", "气质", listOf("清纯")),
                        TagSlot("age", "年龄感", listOf("二十八岁"))
                    )
                ),
            ),
            constraints = TagConstraints(
                people = mapOf("单人" to 1),
                raceLead = mapOf("精灵" to "修长尖耳，银白长发的"),
                raceName = mapOf("精灵" to "精灵")
            ),
            sectionMode = true
        )
        val engine = TagComposerEngine(library)
        val picks = mutableMapOf(
            "people.count" to TagPick("单人", locked = true),
            "race.kind" to TagPick("精灵", locked = true),
            "person.look" to TagPick("清纯", locked = true),
            "person.age" to TagPick("二十八岁", locked = true)
        )
        val prompt = engine.compose(picks)
        assertTrue("不该写「二十八岁的清纯」，实际是 $prompt", !prompt.contains("二十八岁的清纯"))
        assertTrue("年龄仍在，实际是 $prompt", prompt.contains("二十八岁"))
    }
}
