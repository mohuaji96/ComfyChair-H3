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

/**
 * A selected word has to reach the prompt.
 *
 * 腿 did not: the body cap counted categories that had nothing in them, so the three empty
 * ones spent the budget and every draw lost its leg description. These tests pin the whole
 * render path, not just that one symptom.
 */
class RenderCoverageTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun engine(library: TagLibrary) =
        TagComposerEngine(library, library.constraints.peopleCount.filterValues { it <= 1 }.keys)

    /** Spoken for by the man's own sentence rather than by a section of their own. */
    private val spokenElsewhere = setOf("maleBody", "penis")

    /** Categories whose words only speak once a man is in frame. */
    // maleFace 只由男性句子渲染，测试时要先把人数设成含男性。
    private val needsAMan = setOf("maleHand", "maleFace")

    /** 第三个人的动作只在三人场景出现。 */
    private val needsThree = setOf("partnerAct")

    /**
     * 颜色格单独拿不出句子：没有款式可依附，颜色就只剩「穿米色」这样的半句。
     * 颜色与款式合起来的渲染由 WordClassTest 覆盖。
     */
    private val needsAGarment = setOf("color")

    @Test
    fun everyCategoryCanReachThePrompt() {
        val library = library()
        val engine = engine(library)
        val silent = mutableListOf<String>()

        library.categories.forEach { category ->
            if (category.id in spokenElsewhere) return@forEach
            val slot = category.slots.first()
            if (slot.id in needsAGarment) return@forEach
            val word = slot.pool.first()
            val picks = mutableMapOf(engine.key(category.id, slot.id) to TagPick(word))
            if (category.id in needsAMan || category.id in needsThree) {
                val people = library.category("people")!!.slots.first()
                val cast = if (category.id in needsThree) "一男两女" else "一男一女"
                picks[engine.key("people", people.id)] = TagPick(cast, locked = true)
            }
            val prompt = engine.compose(picks)
            if (!prompt.contains(word)) silent += "${category.id}.${slot.id}「$word」 → 「$prompt」"
        }

        assertTrue("这些分类抽到了词却进不了提示词:\n" + silent.joinToString("\n"), silent.isEmpty())
    }

    @Test
    fun aWordNeverSwitchesOffItsOwnCategory() {
        val library = library()
        val engine = engine(library)
        val slotOf = library.categories.associate { c -> c.id to c.slots.first().id }
        val broken = mutableListOf<String>()

        library.categories.forEach { category ->
            category.slots.flatMap { it.pool }.forEach { word ->
                val picks = mutableMapOf(engine.key(category.id, slotOf.getValue(category.id)) to TagPick(word))
                if (category.id in engine.skipped(picks)) broken += "${category.id}「$word」把自己关掉了"
            }
        }

        assertTrue("这些词会删掉自己所在的分类:\n" + broken.take(20).joinToString("\n"), broken.isEmpty())
    }

    @Test
    fun bothBodyGroupsSurvive() {
        val library = library()
        val engine = engine(library)
        // 腰腹与臀部已经并进身材，乳房与腿是仅剩的两个身体分组，帽额不再生效。
        val bodyIds = listOf("breasts", "legs")
        val wordOf = bodyIds.associateWith { id ->
            val c = library.category(id)!!
            c.slots.first().let { engine.key(id, it.id) to it.pool.first() }
        }
        val picks = mutableMapOf<String, TagPick>()
        wordOf.values.forEach { (key, word) -> picks[key] = TagPick(word) }

        repeat(20) {
            val prompt = engine.compose(picks)
            bodyIds.forEach { id ->
                assertTrue("${wordOf.getValue(id).second} 不该被丢：$prompt",
                    prompt.contains(wordOf.getValue(id).second))
            }
        }
    }

    @Test
    fun aLegsWordAloneStillReachesThePrompt() {
        val library = library()
        val engine = engine(library)
        val legs = library.category("legs")!!
        val slot = legs.slots.first()
        val word = slot.pool.first()
        val prompt = engine.compose(mutableMapOf(engine.key("legs", slot.id) to TagPick(word)))
        assertFalse("腿单独一个词也进不了提示词", prompt.isEmpty())
        assertTrue("「$word」没出现", prompt.contains(word))
    }
}
