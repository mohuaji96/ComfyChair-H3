package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File
import kotlin.random.Random

/**
 * 整段锁住：这一段不参与全局随机。
 *
 * 锁住不等于补词：空格仍然空、忽略的仍然忽略；段内单格重抽照常生效。
 */
class SectionHoldTest {

    private fun tagLibrary() = TagLibraryJson.parse(
        File("src/main/assets/tag_catalog.json").readText(Charsets.UTF_8)
    )

    private fun engine(): TagComposerEngine {
        assumeTrue(File("src/main/assets/tag_catalog.json").exists())
        return TagComposerEngine(tagLibrary())
    }

    private fun storyLibrary(): StoryLibrary {
        val file = listOf(
            File("src/main/assets/story_catalog.json"),
            File("app/src/main/assets/story_catalog.json")
        ).firstOrNull { it.exists() }
        assumeTrue("story_catalog.json not found", file != null)
        return StoryLibraryJson.parse(file!!.readText(Charsets.UTF_8))
    }

    @Test
    fun aHeldSectionKeepsEveryWord() {
        val e = engine()
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "top.type" to TagPick("衬衫"),
            "bottom.type" to TagPick("百褶裙"),
            "panties.type" to TagPick("三角裤")
        )
        repeat(20) {
            val next = picks.toMutableMap()
            e.randomize(next, holdGroups = setOf("wear"))
            assertEquals("上衣不该动", "衬衫", next["top.type"]?.value)
            assertEquals("下衣不该动", "百褶裙", next["bottom.type"]?.value)
            assertEquals("内裤不该动", "三角裤", next["panties.type"]?.value)
        }
    }

    @Test
    fun aHeldSectionDoesNotFillItsEmptySlots() {
        val e = engine()
        val picks = mutableMapOf("people.count" to TagPick("单人女孩", locked = true))
        // 穿着段整段空着，锁住之后不该被补上任何词。
        repeat(10) {
            val next = picks.toMutableMap()
            e.randomize(next, holdGroups = setOf("wear"))
            assertEquals("", next["top.type"]?.value.orEmpty())
            assertEquals("", next["bottom.type"]?.value.orEmpty())
            assertEquals("", next["outfitState.state"]?.value.orEmpty())
        }
    }

    @Test
    fun anUnheldSectionStillRedraws() {
        val e = engine()
        val seen = mutableSetOf<String>()
        repeat(25) {
            val next = mutableMapOf("people.count" to TagPick("单人女孩", locked = true))
            e.randomize(next, holdGroups = setOf("person"))
            next["scene.place"]?.value?.takeIf { it.isNotEmpty() }?.let { seen += it }
        }
        assertTrue("锁住人物段后，场景仍应变化：$seen", seen.size > 1)
    }

    @Test
    fun aHeldStorySectionKeepsEveryField() {
        val gen = StoryGenerator(storyLibrary(), Random(31))
        val first = gen.randomize(gen.emptyPicks()).first
        val held = gen.sections.first { it.label == "人物" }.fields.map { it.key }.toSet()
        repeat(10) {
            val next = gen.randomize(first, held = held).first
            held.forEach { key ->
                assertEquals("$key 不该动", first[key]?.value, next[key]?.value)
            }
        }
    }

    @Test
    fun aHeldStorySectionKeepsIgnoredFieldsEmpty() {
        val gen = StoryGenerator(storyLibrary(), Random(41))
        val first = gen.randomize(gen.emptyPicks()).first
        val held = gen.sections.first { it.label == "过程" }.fields.map { it.key }.toSet()
        val next = gen.randomize(first, held = held).first
        held.forEach { key -> assertEquals("$key 不该动", first[key]?.value, next[key]?.value) }
    }

    @Test
    fun anUnheldStorySectionStillRedraws() {
        val gen = StoryGenerator(storyLibrary(), Random(51))
        val first = gen.randomize(gen.emptyPicks()).first
        val personKeys = gen.sections.first { it.label == "人物" }.fields.map { it.key }.toSet()
        val seen = mutableSetOf<String>()
        repeat(20) {
            val next = gen.randomize(first, held = personKeys).first
            next["place"]?.value?.let { seen += it }
        }
        assertTrue("锁住人物段后，场景仍应变化：$seen", seen.size > 1)
    }
}
