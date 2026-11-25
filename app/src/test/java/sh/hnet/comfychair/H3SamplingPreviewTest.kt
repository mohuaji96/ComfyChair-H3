package sh.hnet.comfychair

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.H3SamplingPreview

/**
 * The preview switch on the H3 page acts on the exported prompt, not on the stored graph: a graph
 * that already has the node must be pinned to one still per step, a graph without one gains it in
 * front of the guider, and switching off must leave the sampler reading the model directly.
 */
class H3SamplingPreviewTest {

    private fun modelChain(): JSONObject = JSONObject(
        """
        {
          "10": {"class_type": "UNETLoader", "inputs": {"unet_name": "h3.safetensors"}},
          "11": {"class_type": "BasicGuider", "inputs": {"model": ["10", 0], "conditioning": ["12", 0]}},
          "12": {"class_type": "CLIPTextEncode", "inputs": {"text": "a"}},
          "13": {"class_type": "SamplerCustomAdvanced", "inputs": {"guider": ["11", 0], "noise": ["14", 0]}}
        }
        """.trimIndent()
    )

    private fun withPreviewNode(): JSONObject = JSONObject(
        """
        {
          "10": {"class_type": "UNETLoader", "inputs": {"unet_name": "h3.safetensors"}},
          "20": {"class_type": "ModelPreviewOverrideKJ", "inputs": {
             "model": ["10", 0], "preview": "", "preview_frames": ["131", 1],
             "jpeg_quality": 80, "max_resolution": 1024, "tiny_vae": "none"
          }},
          "11": {"class_type": "BasicGuider", "inputs": {"model": ["20", 0], "conditioning": ["12", 0]}},
          "12": {"class_type": "CLIPTextEncode", "inputs": {"text": "a"}}
        }
        """.trimIndent()
    )

    /**
     * H3's latent is 3-D / 24-channel, so the node can only animate it through a tiny VAE named
     * `taeh3`. The name has to come from the server's own option list, never from us guessing.
     */
    @Test
    fun `picks the H3 tiny vae when the server lists it`() {
        val schemaOptional = """
            {"ModelPreviewOverrideKJ": {"input": {"optional": {
               "tiny_vae": [["none", "taeh3.pth", "taesd.pth"], {"tooltip": "x"}]
            }}}}
        """.trimIndent()
        assertEquals("taeh3.pth", H3SamplingPreview.pickTinyVae(schemaOptional))

        val schemaRequired = """
            {"ModelPreviewOverrideKJ": {"input": {"required": {
               "tiny_vae": [["none", "taeh3.pth", "taesd.pth"], {"tooltip": "x"}]
            }}}}
        """.trimIndent()
        assertEquals("taeh3.pth", H3SamplingPreview.pickTinyVae(schemaRequired))
    }

    @Test
    fun `leaves tiny vae alone when the server has no H3 decoder`() {
        val schema = """
            {"ModelPreviewOverrideKJ": {"input": {"required": {
               "tiny_vae": [["none", "taesd.pth"], {}]
            }}}}
        """.trimIndent()
        assertNull(H3SamplingPreview.pickTinyVae(schema))
        assertNull(H3SamplingPreview.pickTinyVae(null))
    }

    @Test
    fun `writes the tiny vae into both the injected node and an existing one`() {
        val injected = JSONObject(
            H3SamplingPreview.apply(
                modelChain().toString(), enabled = true, nodeAvailable = true, tinyVae = "taeh3.pth"
            ).json
        ).optJSONObject("999001")!!.optJSONObject("inputs")!!
        assertEquals("taeh3.pth", injected.optString("tiny_vae"))

        val existing = JSONObject(
            H3SamplingPreview.apply(
                withPreviewNode().toString(), enabled = true, nodeAvailable = true, tinyVae = "taeh3.pth"
            ).json
        ).optJSONObject("20")!!.optJSONObject("inputs")!!
        assertEquals("taeh3.pth", existing.optString("tiny_vae"))
    }

    @Test
    fun `inserts a preview node in front of the guider`() {
        val result = H3SamplingPreview.apply(modelChain().toString(), enabled = true, nodeAvailable = true)
        assertTrue(result.attached)
        val root = JSONObject(result.json)
        val injected = root.optJSONObject("999001")
        assertNotNull(injected)
        assertEquals(H3SamplingPreview.NODE_TYPE, injected!!.optString("class_type"))
        assertEquals(listOf("10", 0), injected.optJSONObject("inputs")!!.optJSONArray("model")!!.toList())
        assertEquals(
            listOf("999001", 0),
            root.optJSONObject("11")!!.optJSONObject("inputs")!!.optJSONArray("model")!!.toList()
        )
    }

    @Test
    fun `leaves the graph alone when the server has no such node`() {
        val result = H3SamplingPreview.apply(modelChain().toString(), enabled = true, nodeAvailable = false)
        assertFalse(result.attached)
        assertNull(JSONObject(result.json).optJSONObject("999001"))
    }

    @Test
    fun `pins an existing node to our own short clip`() {
        val result = H3SamplingPreview.apply(withPreviewNode().toString(), enabled = true, nodeAvailable = true)
        assertTrue(result.attached)
        val inputs = JSONObject(result.json).optJSONObject("20")!!.optJSONObject("inputs")!!
        assertEquals(16, inputs.optInt("preview_frames"))
        // The phone used to ask for 512/q78; that is what made previews look soft, so both are pinned too.
        assertEquals(1024, inputs.optInt("max_resolution"))
        assertEquals(85, inputs.optInt("jpeg_quality"))
    }

    @Test
    fun `switching off removes the node and restores the direct model link`() {
        val result = H3SamplingPreview.apply(withPreviewNode().toString(), enabled = false, nodeAvailable = true)
        assertFalse(result.attached)
        val root = JSONObject(result.json)
        assertNull(root.optJSONObject("20"))
        assertEquals(
            listOf("10", 0),
            root.optJSONObject("11")!!.optJSONObject("inputs")!!.optJSONArray("model")!!.toList()
        )
    }

    @Test
    fun `switching off a graph without the node changes nothing`() {
        val source = modelChain().toString()
        val result = H3SamplingPreview.apply(source, enabled = false, nodeAvailable = true)
        assertFalse(result.attached)
        assertEquals(JSONObject(source).toString(), JSONObject(result.json).toString())
    }

    @Test
    fun `keeps an injected id free of collisions`() {
        val root = modelChain()
        root.put("999001", JSONObject().put("class_type", "Other").put("inputs", JSONObject()))
        val result = H3SamplingPreview.apply(root.toString(), enabled = true, nodeAvailable = true)
        val patched = JSONObject(result.json)
        assertEquals("Other", patched.optJSONObject("999001")!!.optString("class_type"))
        assertEquals(
            listOf("999002", 0),
            patched.optJSONObject("11")!!.optJSONObject("inputs")!!.optJSONArray("model")!!.toList()
        )
    }

    /**
     * The two-pass H3 templates: two guiders over one model, each feeding its own sampler. The
     * second guider is written first in the file, which is how a single-guider patch ended up
     * previewing only the pass nobody watches.
     */
    private fun twoPass(): JSONObject = JSONObject(
        """
        {
          "37": {"class_type": "MiniMaxH3SigmaShift", "inputs": {"model": ["10", 0]}},
          "151:32": {"class_type": "BasicGuider", "inputs": {"model": ["37", 0], "conditioning": ["193", 0]}},
          "151:29": {"class_type": "BasicGuider", "inputs": {"model": ["37", 0], "conditioning": ["192", 0]}},
          "151:16": {"class_type": "SamplerCustomAdvanced", "inputs": {"guider": ["151:32", 0]}},
          "151:21": {"class_type": "SamplerCustomAdvanced", "inputs": {"guider": ["151:29", 0]}}
        }
        """.trimIndent()
    )

    @Test
    fun `every guider on the same model reads the preview node`() {
        val result = H3SamplingPreview.apply(twoPass().toString(), enabled = true, nodeAvailable = true)
        assertTrue(result.attached)
        val root = JSONObject(result.json)
        assertEquals(
            listOf("999001", 0),
            root.optJSONObject("151:29")!!.optJSONObject("inputs")!!.optJSONArray("model")!!.toList()
        )
        assertEquals(
            listOf("999001", 0),
            root.optJSONObject("151:32")!!.optJSONObject("inputs")!!.optJSONArray("model")!!.toList()
        )
        assertEquals(
            listOf("37", 0),
            root.optJSONObject("999001")!!.optJSONObject("inputs")!!.optJSONArray("model")!!.toList()
        )
        // Both passes share the model, so one node serves both instead of one node per guider.
        assertEquals(1, root.keys().asSequence().count { it == "999001" })
    }

    @Test
    fun `switching off a two-pass graph restores both guiders`() {
        val patched = H3SamplingPreview.apply(twoPass().toString(), enabled = true, nodeAvailable = true).json
        val result = H3SamplingPreview.apply(patched, enabled = false, nodeAvailable = true)
        val root = JSONObject(result.json)
        assertNull(root.optJSONObject("999001"))
        assertEquals(
            listOf("37", 0),
            root.optJSONObject("151:29")!!.optJSONObject("inputs")!!.optJSONArray("model")!!.toList()
        )
        assertEquals(
            listOf("37", 0),
            root.optJSONObject("151:32")!!.optJSONObject("inputs")!!.optJSONArray("model")!!.toList()
        )
    }

    private fun JSONArray.toList(): List<Any> = (0 until length()).map { opt(it) }
}
