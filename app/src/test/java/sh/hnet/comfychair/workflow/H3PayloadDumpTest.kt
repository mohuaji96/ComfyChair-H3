package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dumps the exact payloads the four modes would post, so a separate step can validate every
 * field against the live `/object_info` without spending VRAM. Nothing is sent from here.
 *
 * Output: `app/build/h3-payloads/<mode>.json`.
 */
class H3PayloadDumpTest {

    private fun raw(name: String) = File("src/main/res/raw/$name").readText()

    @Test
    fun dumpFourModePayloads() {
        val dir = File("build/h3-payloads").apply { mkdirs() }
        val frames = raw("ttv_h3_mobile_frames.json")
        val r2v = raw("itv_h3_mobile_r2v.json")
        val good = listOf("16:9 (Widescreen)", "9:16 (Portrait Widescreen)", "1:1 (Square)",
            "4:3 (Standard)", "3:4 (Portrait Standard)", "21:9 (Ultrawide)")

        val frameSpec = H3Plan.resolve(frames)!!
        val r2vSpec = H3Plan.resolve(r2v)!!
        val cases = mapOf(
            "T2V" to H3Plan.build(frames, frameSpec, H3Plan.Request(
                mode = H3Plan.Mode.T2V, prompt = "a lighthouse at dusk", steps = 4,
                seconds = 6.0, ratio = "16:9", megapixels = 0.5), good),
            "I2V" to H3Plan.build(frames, frameSpec, H3Plan.Request(
                mode = H3Plan.Mode.I2V, prompt = "a lighthouse at dusk", steps = 4,
                seconds = 6.0, ratio = "16:9", megapixels = 0.5,
                firstFrame = "h3_first.png"), good),
            "FL2V" to H3Plan.build(frames, frameSpec, H3Plan.Request(
                mode = H3Plan.Mode.FL2V, prompt = "a lighthouse at dusk", steps = 4,
                seconds = 6.0, ratio = "16:9", megapixels = 0.5,
                firstFrame = "h3_first.png", lastFrame = "h3_last.png"), good),
            "R2V" to H3Plan.build(r2v, r2vSpec, H3Plan.Request(
                mode = H3Plan.Mode.R2V, prompt = "<Picture 1> dances", steps = 4,
                seconds = 10.0, ratio = "16:9", megapixels = 0.4,
                references = listOf("h3_ref0.png", "h3_ref1.png", "h3_ref2.png")), good),
            // The template wires nine slots; the ends of that range break first.
            "R2V_1" to H3Plan.build(r2v, r2vSpec, H3Plan.Request(
                mode = H3Plan.Mode.R2V, prompt = "<Picture 1> dances", steps = 4,
                seconds = 10.0, ratio = "16:9", megapixels = 0.4,
                references = listOf("h3_ref0.png")), good),
            "R2V_9" to H3Plan.build(r2v, r2vSpec, H3Plan.Request(
                mode = H3Plan.Mode.R2V, prompt = "<Picture 1>..<Picture 9> dance", steps = 4,
                seconds = 10.0, ratio = "16:9", megapixels = 0.4,
                references = (0..8).map { "h3_ref$it.png" }), good),
            // Image + standalone audio, no reference video: the combination this build ships.
            "R2V_AUDIO" to H3Plan.build(r2v, r2vSpec, H3Plan.Request(
                mode = H3Plan.Mode.R2V, prompt = "<Picture 1> speaks, <Audio 1> is the voice", steps = 4,
                seconds = 10.0, ratio = "16:9", megapixels = 0.4,
                references = listOf("h3_ref0.png"), refAudios = listOf("h3_voice.m4a")), good)
        )
        cases.forEach { (mode, result) ->
            assertTrue("$mode: ${result.errors}", result.ok)
            File(dir, "$mode.json").writeText(result.json)
            // The payload must be a bare node map, which is what /prompt accepts.
            val doc = JSONObject(result.json)
            assertTrue(doc.keys().asSequence().any { doc.getJSONObject(it).has("class_type") })
            assertTrue(!doc.has("nodes"))
        }
    }
}
