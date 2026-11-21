package sh.hnet.comfychair.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A template that only wires first_frame still has to accept a tail-only run: `last_frame` is an
 * optional input of MiniMaxH3ImageToVideo, and the sample image baked into the template must not
 * ride along as the opening shot.
 */
class H3LastFrameTest {

    private val template = """
        {"nodes":{
          "1":{"class_type":"MiniMaxH3ImageToVideo","inputs":{
            "prompt":"测试","width":[2,1],"height":[2,0],"length":[3,0],
            "clip":[4,0],"vae":[4,0],"first_frame":[5,0]}},
          "2":{"class_type":"ResolutionSelector","inputs":{"aspect_ratio":"16:9","megapixels":0.5}},
          "3":{"class_type":"PrimitiveFloat","inputs":{"value":6}},
          "4":{"class_type":"CLIPLoader","inputs":{"clip_name":"a.safetensors"}},
          "5":{"class_type":"LoadImage","inputs":{"image":"sample.png"}}
        }}
    """.trimIndent()

    @Test
    fun tailOnlyBuildsAndDropsTheSampleFirstFrame() {
        val spec = H3Plan.resolve(template)!!
        val result = H3Plan.build(
            template, spec,
            H3Plan.Request(mode = H3Plan.Mode.FL2V, lastFrame = "tail.png", clearFirstFrame = true)
        )
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertTrue(result.json.contains("last_frame"))
        assertFalse(result.json.contains("\"first_frame\""))
    }

    @Test
    fun tailOnlyKeepsTemplateFirstFrameWhenTheUiDidNotClearIt() {
        val spec = H3Plan.resolve(template)!!
        val result = H3Plan.build(
            template, spec,
            H3Plan.Request(mode = H3Plan.Mode.FL2V, lastFrame = "tail.png")
        )
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertTrue(result.json.contains("\"first_frame\""))
        assertTrue(result.json.contains("last_frame"))
    }

    @Test
    fun clearingBothFramesIsRejected() {
        val spec = H3Plan.resolve(template)!!
        val result = H3Plan.build(
            template, spec,
            H3Plan.Request(
                mode = H3Plan.Mode.FL2V,
                clearFirstFrame = true,
                clearLastFrame = true
            )
        )
        assertTrue(result.errors.any { it.contains("至少要选一张") })
    }
}
