package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * The act is the anchor; the lens arranges itself around it.
 *
 * Framing used to be drawn first so crop words could appear at all, but four of the eight crop
 * words hide groin — so half of all draws came out with no sexual content, and 只露出肩部 (six
 * of seven regions) emptied the body entirely. Framing now yields to the act, and the words
 * that hide groin only come back when 性行为 is switched off.
 */
class FramingPriorityTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun solo(library: TagLibrary): TagComposerEngine =
        TagComposerEngine(library, library.constraints.peopleCount.filterValues { it <= 1 }.keys)

    @Test
    fun aCropWordNeverDeletesTheAct() {
        val library = library()
        val engine = solo(library)
        val picks = mutableMapOf<String, TagPick>()
        val groinHiding = library.constraints.frameHides
            .filterValues { "groin" in it }
            .keys

        var drawsWithAct = 0
        repeat(40) {
            engine.randomize(picks)
            val act = picks["sexAct.act"]?.value.orEmpty()
            val crop = picks["camera.crop"]?.value.orEmpty()
            if (act.isNotEmpty()) drawsWithAct++
            if (act.isNotEmpty() && crop in groinHiding) {
                throw AssertionError("「$act」配了「$crop」：${engine.drawNote()}")
            }
        }
        assertTrue("40 次里一次性行为都没抽到", drawsWithAct >= 30)
    }

    @Test
    fun everyCropWordComesBackWhenTheActIsOff() {
        val library = library()
        val engine = solo(library)
        val picks = mutableMapOf<String, TagPick>(
            // Cleared and ignored: the scene asks for no act at all.
            "sexAct.act" to TagPick("", ignored = true)
        )
        val seen = mutableSetOf<String>()
        repeat(160) {
            engine.randomize(picks)
            picks["sexAct.act"]?.value?.takeIf { v -> v.isNotEmpty() }?.let { seen += "ACT:$it" }
            picks["camera.crop"]?.value?.takeIf { v -> v.isNotEmpty() }?.let { seen += it }
        }
        assertTrue("性行为被忽略却还在抽", seen.none { it.startsWith("ACT:") })
        val cropWords = seen.filterNot { it.startsWith("ACT:") }.toSet()
        val all = library.category("camera")!!.slots.first { it.id == "crop" }.pool.toSet()
        assertTrue("出画只抽到 ${cropWords.size}/${all.size}: $cropWords", cropWords.size == all.size)
    }

    @Test
    fun aShoulderOnlyFrameAlsoDropsHairAndTheFace() {
        val library = library()
        val engine = solo(library)
        val picks = mutableMapOf<String, TagPick>("camera.crop" to TagPick("只露出肩部", locked = true))

        engine.randomize(picks)

        // 只露出肩部 hides head, chest, waist, groin, legs and feet — none of these may print.
        listOf("hair.color", "hair.state", "expression.face", "gazeDir.gazeDir").forEach { key ->
            val value = picks[key]?.value.orEmpty()
            assertTrue("$key 仍是「$value」", value.isEmpty())
        }
        listOf("breasts.size", "waist.detail", "legs.detail").forEach { key ->
            assertTrue("$key 仍是「${picks[key]?.value}」", picks[key]?.value.isNullOrEmpty())
        }
    }
}
