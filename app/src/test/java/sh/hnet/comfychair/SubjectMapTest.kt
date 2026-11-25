package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import java.io.File

/**
 * Step 1 of 谁是谁: every category says whose body it describes, and nothing reads it yet.
 *
 * The values are data only, so a wrong one cannot change a single draw -- that is the point of
 * doing this half first. What this test protects is that the table stays complete and sensible
 * while step 2 starts depending on it.
 */
class SubjectMapTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private val allowed = setOf("female", "male", "scene")

    @Test
    fun everyCategorySaysWhoseBodyItDescribes() {
        val lib = library()
        val blank = lib.categories.filter { it.who.isBlank() }.map { it.id }
        assertTrue("not tagged: $blank", blank.isEmpty())
        val odd = lib.categories.filter { it.who !in allowed }.map { it.id to it.who }
        assertTrue("unexpected owner: $odd", odd.isEmpty())
    }

    @Test
    fun theBodiesLandOnTheRightSide() {
        val lib = library()
        fun who(id: String) = lib.categories.first { it.id == id }.who
        // Hers.
        assertEquals("female", who("breasts"))
        assertEquals("female", who("pussy"))
        assertEquals("female", who("top"))
        // 妆容并进了「形象」，这一类仍然整类都是她的。
        assertEquals("female", who("person"))
        // His.
        // 阴茎那一格已经删掉，由男方句统一交代，不再单独成一个分类。
        assertTrue("阴茎分类应已删除", lib.categories.none { it.id == "penis" })
        assertEquals("male", who("maleBody"))
        // Nobody's: the cast, the act, the lens.
        assertEquals("scene", who("people"))
        assertEquals("scene", who("sexAct"))
        assertEquals("scene", who("camera"))
    }

    @Test
    fun aWordMayOverrideTheCategory() {
        val lib = library()
        // Empty today: 体液 describes where the fluid landed on her, so the category default wins.
        assertTrue(lib.constraints.subjects.isEmpty())
        val custom = TagLibraryJson.parse(
            """{"schemaVersion":1,"categories":[{"id":"top","label":"上衣","who":"female",
               "slots":[{"id":"type","label":"款式","pool":["衬衫"]}]}],
               "constraints":{"subjects":{"衬衫":"male"}}}"""
        )
        assertEquals("female", custom.categories.first().who)
        assertEquals("male", custom.constraints.subjects["衬衫"])
    }

    @Test
    fun writingKeepsTheOwnership() {
        val lib = library()
        val again = TagLibraryJson.parse(TagLibraryJson.write(lib))
        assertEquals(
            lib.categories.associate { it.id to it.who },
            again.categories.associate { it.id to it.who }
        )
        assertEquals(lib.constraints.subjects, again.constraints.subjects)
    }
}
