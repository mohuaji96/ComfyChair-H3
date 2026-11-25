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
            val crop = picks["camera.shot"]?.value.orEmpty()
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
            picks["camera.shot"]?.value?.takeIf { v -> v.isNotEmpty() }?.let { seen += it }
        }
        assertTrue("性行为被忽略却还在抽", seen.none { it.startsWith("ACT:") })
        val cropWords = seen.filterNot { it.startsWith("ACT:") }.toSet()
        // 裁切并进景别之后这一格有二十多条，160 次抽不满全部；要验的是那些会裁掉部位的
        // 画外词在性行为关掉后都能回来，而不是逐个穷举。
        val required = setOf(
            "胸部在画外", "下半身在画外", "脚部在画外"
        )
        val missing = required - cropWords
        assertTrue("这些画外词没能回来：$missing（抽到 ${cropWords.size} 个）", missing.isEmpty())
    }

    @Test
    fun aShoulderOnlyFrameAlsoDropsHairAndTheFace() {
        val library = library()
        val engine = solo(library)
        val picks = mutableMapOf<String, TagPick>("camera.shot" to TagPick("只露出肩部", locked = true))

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
