package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * P0 regression guard for ComfyChair H3 0.9.3.
 *
 * Covers: dual-format recognition (P0-2), non-blocking import (P0-3), PrimitiveString
 * prompts (P0-4), LoRA discovery (P0-6), link protection (P0-7) and the diff preview (P0-8).
 * Ordinary built-in workflows are asserted to stay unrecognised.
 */
class H3P0RegressionTest {

    private fun liteFile(name: String) = File("src/test/resources/h3/$name").readText()
    private fun official(name: String) = File("src/main/assets/h3_official/$name").readText()

    private fun api(vararg pairs: Pair<String, JSONObject>): String =
        JSONObject().put("nodes", JSONObject().apply { pairs.forEach { put(it.first, it.second) } }).toString()

    private fun node(type: String, vararg inputs: Pair<String, Any>): JSONObject =
        JSONObject().put("class_type", type).put("inputs", JSONObject().apply {
            inputs.forEach { (key, value) ->
                when (value) {
                    is String, is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is List<*> -> put(key, JSONArray().apply { value.forEach { put(it) } })
                    else -> put(key, value)
                }
            }
        })

    // --- P0-2: dual format -------------------------------------------------

    @Test fun liteGraphH3IsRecognisedOffline() {
        val analysis = H3WorkflowAdapter.inspectAny(liteFile("simple.json"))
        assertNotNull("LiteGraph H3 must be recognised without /object_info", analysis)
        analysis!!
        assertEquals(H3WorkflowAdapter.Mode.R2V, analysis.mode)
        assertEquals("litegraph", analysis.format)
        assertEquals("<Prompt>", analysis.bindings.first { it.kind == "prompt" }.value)
        assertTrue("ref_image_0 must bind", analysis.referenceInputs.containsKey(0))
    }

    @Test fun liteGraphSubgraphKeepsSupportedSlotsOnly() {
        val analysis = H3WorkflowAdapter.inspectAny(liteFile("experimental.json"))!!
        assertEquals(H3WorkflowAdapter.Mode.R2V, analysis.mode)
        // P0 keeps the 0..5 window; 6/7 must stay untouched and explicitly reported.
        assertEquals(setOf(0, 1, 2, 3, 4, 5), analysis.referenceInputs.keys)
        assertTrue(analysis.warnings.any { it.contains("ref_image_6") })
        assertTrue(analysis.warnings.any { it.contains("ref_image_7") })
        assertTrue("LoRA stack must surface as a diagnostic",
            analysis.warnings.any { it.contains("H3PowerLoraStack") })
    }

    @Test fun liteGraphConversionIsStable() {
        val doc = JSONObject(liteFile("simple.json"))
        assertEquals(H3LiteGraphAdapter.convert(doc).toString(),
            H3LiteGraphAdapter.convert(JSONObject(liteFile("simple.json"))).toString())
    }

    @Test fun officialApiBindingsAreUnchanged() {
        val t2v = H3WorkflowAdapter.inspect(official("官方文生视频.json"))!!
        val i2v = H3WorkflowAdapter.inspect(official("官方图生视频.json"))!!
        val r2v = H3WorkflowAdapter.inspect(official("官方参考生视频.json"))!!
        assertEquals(H3WorkflowAdapter.Mode.T2V, t2v.mode); assertEquals(14, t2v.bindings.size)
        assertEquals(H3WorkflowAdapter.Mode.I2V, i2v.mode); assertEquals(15, i2v.bindings.size)
        assertEquals(H3WorkflowAdapter.Mode.R2V, r2v.mode); assertEquals(16, r2v.bindings.size)
        assertEquals(setOf(0, 1), r2v.referenceInputs.keys)
        assertEquals("api", t2v.format)
        // Signature fields must stay exactly where the official samples put them.
        assertEquals("153", t2v.coreId)
        assertEquals("139", i2v.coreId)
        assertEquals("136", r2v.coreId)
    }

    // --- P0-5: FL2V --------------------------------------------------------

    @Test fun officialFl2vMapsBothFrames() {
        val fl2v = H3WorkflowAdapter.inspect(official("官方首尾帧生视频.json"))!!
        assertEquals(H3WorkflowAdapter.Mode.FL2V, fl2v.mode)
        assertEquals("140", fl2v.coreId)
        assertEquals(16, fl2v.bindings.size)
        assertTrue("FL2V must not be treated as R2V", fl2v.referenceInputs.isEmpty())
        val frames = fl2v.bindings.filter { it.kind == "image" }
        assertEquals(2, frames.size)
        assertEquals("114", frames.first { it.slot == 0 }.nodeId)
        assertEquals("127", frames.first { it.slot == 1 }.nodeId)
        assertEquals("首帧图片", frames.first { it.slot == 0 }.label)
        assertEquals("尾帧图片", frames.first { it.slot == 1 }.label)
        // Shared model/prompt mapping must still hold on FL2V.
        assertEquals(1, fl2v.bindings.count { it.kind == "prompt" })
        assertTrue(fl2v.bindings.any { it.input == "megapixels" })
        assertTrue(fl2v.bindings.any { it.label.contains("时长") })
        assertTrue(fl2v.bindings.any { it.input == "lora_name" })
    }

    @Test fun fl2vFramesAreReplacedIndependently() {
        val source = official("官方首尾帧生视频.json")
        val replaced = H3WorkflowAdapter.setFrame(source, "last_frame", "end.png")
        val nodes = H3WorkflowAdapter.nodes(JSONObject(replaced))
        // Only the last_frame loader changes; the first_frame loader keeps its filename.
        assertEquals("end.png", nodes.getJSONObject("127").getJSONObject("inputs").getString("image"))
        assertEquals("transparent_rgb_gaming_mouse.png",
            nodes.getJSONObject("114").getJSONObject("inputs").getString("image"))
        assertEquals("127", nodes.getJSONObject("140").getJSONObject("inputs")
            .getJSONArray("last_frame").getString(0))
        assertEquals("114", nodes.getJSONObject("140").getJSONObject("inputs")
            .getJSONArray("first_frame").getString(0))
    }

    @Test fun fl2vFrameCanBeClearedAndRestored() {
        val source = official("官方首尾帧生视频.json")
        val cleared = H3WorkflowAdapter.setFrame(source, "last_frame", null)
        val clearedInputs = H3WorkflowAdapter.nodes(JSONObject(cleared)).getJSONObject("140").getJSONObject("inputs")
        assertFalse(clearedInputs.has("last_frame"))
        assertTrue("loader node must survive clearing", H3WorkflowAdapter.nodes(JSONObject(cleared)).has("127"))

        val restored = H3WorkflowAdapter.setFrame(cleared, "last_frame", "again.png")
        val restoredNodes = H3WorkflowAdapter.nodes(JSONObject(restored))
        val link = restoredNodes.getJSONObject("140").getJSONObject("inputs").getJSONArray("last_frame")
        val loader = restoredNodes.getJSONObject(link.getString(0))
        assertEquals("LoadImage", loader.getString("class_type"))
        assertEquals("again.png", loader.getJSONObject("inputs").getString("image"))
    }

    @Test fun sharedLoaderIsNotEditedForOneFrame() {
        // Both frames pointing at the same loader: replacing one must not change the other.
        val doc = JSONObject(official("官方首尾帧生视频.json"))
        val nodes = H3WorkflowAdapter.nodes(doc)
        nodes.getJSONObject("140").getJSONObject("inputs")
            .put("last_frame", JSONArray().put("114").put(0))
        val replaced = H3WorkflowAdapter.setFrame(doc.toString(), "last_frame", "only_end.png")
        val after = H3WorkflowAdapter.nodes(JSONObject(replaced))
        assertEquals("transparent_rgb_gaming_mouse.png",
            after.getJSONObject("114").getJSONObject("inputs").getString("image"))
        val link = after.getJSONObject("140").getJSONObject("inputs").getJSONArray("last_frame")
        assertNotEquals("114", link.getString(0))
        assertEquals("only_end.png", after.getJSONObject(link.getString(0)).getJSONObject("inputs").getString("image"))
    }

    // --- P0-3: recognition must not throw ----------------------------------

    @Test fun multipleCoresReportInsteadOfThrowing() {
        val source = api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to "a"),
            "2" to node("MiniMaxH3ReferenceToVideo", "prompt" to "b", "ref_images.ref_image_0" to listOf("9", 0))
        )
        val analysis = H3WorkflowAdapter.inspectAny(source)
        assertNotNull(analysis)
        assertEquals(H3WorkflowAdapter.Mode.AMBIGUOUS, analysis!!.mode)
        assertTrue(analysis.bindings.isEmpty())
        assertTrue(analysis.warnings.single().contains("#1"))
        assertTrue(analysis.warnings.single().contains("#2"))
    }

    @Test fun malformedJsonIsNotH3AndDoesNotThrow() {
        assertNull(H3WorkflowAdapter.inspectAny("{not json"))
        assertNull(H3WorkflowAdapter.inspectAny("[]"))
    }

    @Test fun ordinaryBuiltInsAreNeverH3() {
        val raw = File("src/main/res/raw")
        val files = raw.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.sorted().orEmpty()
        assertTrue("raw workflows missing", files.size >= 20)
        val ordinary = files.filterNot { it.name.contains("_h3_") }
        val builtInH3 = files.filter { it.name.contains("_h3_") }
        // Only the two mobile templates remain; the legacy official samples were removed in 0.9.5.
        assertEquals("built-in H3 templates must all be recognised",
            2, builtInH3.count { H3WorkflowAdapter.inspectAny(it.readText()) != null })
        assertEquals("every H3 template must also resolve into a plan",
            builtInH3.size, builtInH3.count { H3Plan.resolve(it.readText()) != null })
        val recognised = ordinary.filter { H3WorkflowAdapter.inspectAny(it.readText()) != null }.map { it.name }
        assertEquals("ordinary workflows must not be recognised as H3: $recognised", emptyList<String>(), recognised)
        assertEquals(21, ordinary.size)
    }

    // --- P0-4: prompt sources ---------------------------------------------

    @Test fun primitiveStringPromptIsRecognised() {
        val source = api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to listOf("2", 0)),
            "2" to node("PrimitiveString", "value" to "a plain prompt")
        )
        val binding = H3WorkflowAdapter.inspect(source)!!.bindings.first { it.kind == "prompt" }
        assertEquals("2", binding.nodeId)
        assertEquals("a plain prompt", binding.value)
    }

    @Test fun twoPromptSourcesAreReportedNotChosen() {
        val source = api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to listOf("9", 0)),
            "9" to node("ComfySwitchNode", "on_true" to listOf("2", 0), "on_false" to listOf("3", 0)),
            "2" to node("PrimitiveString", "value" to "a"),
            "3" to node("PrimitiveStringMultiline", "value" to "b")
        )
        val analysis = H3WorkflowAdapter.inspect(source)!!
        assertTrue(analysis.bindings.none { it.kind == "prompt" })
        val warning = analysis.warnings.first { it.contains("提示词") }
        assertTrue(warning.contains("#2"))
        assertTrue(warning.contains("#3"))
    }

    @Test fun promptTextIsNeverRewritten() {
        val text = "a <Picture 1> and <Video 1>\n第二行 \" quoted"
        val source = api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to text),
            "2" to node("PrimitiveFloat", "value" to 5.0)
        )
        val binding = H3WorkflowAdapter.inspect(source)!!.bindings.first { it.kind == "prompt" }
        val patched = JSONObject(H3WorkflowAdapter.change(source, binding, text)).getJSONObject("nodes")
            .getJSONObject("1").getJSONObject("inputs").getString("prompt")
        assertEquals(text, patched)
    }

    // --- P0-6: LoRA --------------------------------------------------------

    @Test fun loraLabelsComeFromTheWorkflowNotAProductName() {
        val source = api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to "x"),
            "5" to node("LoraLoaderModelOnly", "lora_name" to "my_lora.safetensors", "strength_model" to 0.7),
            "6" to node("LoraLoader", "lora_name" to "dual.safetensors", "strength_model" to 0.8,
                "strength_clip" to 0.6)
        )
        val bindings = H3WorkflowAdapter.inspect(source)!!.bindings
        val names = bindings.filter { it.input == "lora_name" }.map { it.value.toString() }
        assertEquals(setOf("my_lora.safetensors", "dual.safetensors"), names.toSet())
        assertTrue(bindings.none { it.label.contains("加速") || it.label.contains("Turbo", true) })
        assertNotNull(bindings.firstOrNull { it.nodeId == "6" && it.input == "strength_clip" })
    }

    @Test fun bypassedLoraIsReadOnly() {
        val source = api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to "x"),
            "5" to node("LoraLoaderModelOnly", "lora_name" to "off.safetensors", "strength_model" to 1.0)
                .put("mode", 4)
        )
        val binding = H3WorkflowAdapter.inspect(source)!!.bindings.first { it.nodeId == "5" }
        assertTrue(binding.readOnly)
        assertTrue(binding.note!!.contains("绕过"))
        val failed = runCatching { H3WorkflowAdapter.change(source, binding, "other.safetensors") }.isFailure
        assertTrue("read-only bindings must refuse edits", failed)
    }

    // --- P0-7: link protection --------------------------------------------

    @Test fun changingALiteralToALinkIsRejected() {
        val original = api("1" to node("MiniMaxH3ImageToVideo", "prompt" to "a"))
        val edited = api("1" to node("MiniMaxH3ImageToVideo", "prompt" to listOf("2", 0)))
        val prepared = original
        val error = runCatching { H3ApiPatch.apply(original, edited, prepared) }.exceptionOrNull()
        assertNotNull("structural change must not be merged silently", error)
        assertTrue(error!!.message!!.contains("连线冲突"))
    }

    @Test fun removingAReferenceSlotIsApplied() {
        val original = api(
            "1" to node("MiniMaxH3ReferenceToVideo",
                "ref_images.ref_image_0" to listOf("7", 0),
                "ref_images.ref_image_1" to listOf("8", 0))
        )
        val edited = api(
            "1" to node("MiniMaxH3ReferenceToVideo",
                "ref_images.ref_image_0" to listOf("7", 0))
        )
        val result = H3ApiPatch.applyDetailed(original, edited, original)
        val inputs = JSONObject(result.json).getJSONObject("nodes").getJSONObject("1").getJSONObject("inputs")
        assertFalse(inputs.has("ref_images.ref_image_1"))
        assertEquals("link-remove", result.changes.single().kind)
    }

    @Test fun literalEditKeepsProfessionalEditorNodes() {
        val original = api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to "a", "length" to listOf("4", 1)),
            "4" to node("PrimitiveFloat", "value" to 5.0)
        )
        val edited = api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to "b", "length" to listOf("4", 1)),
            "4" to node("PrimitiveFloat", "value" to 5.0)
        )
        val prepared = JSONObject(api(
            "1" to node("MiniMaxH3ImageToVideo", "prompt" to "a", "length" to listOf("4", 1)),
            "4" to node("PrimitiveFloat", "value" to 5.0)
        )).put("extra_node", JSONObject().put("class_type", "SaveVideo")).toString()
        val result = H3ApiPatch.applyDetailed(original, edited, prepared)
        assertTrue(result.json.contains("extra_node"))
        assertEquals("b", JSONObject(result.json).getJSONObject("nodes").getJSONObject("1")
            .getJSONObject("inputs").getString("prompt"))
        // The untouched link survives byte-for-byte.
        assertEquals("4", JSONObject(result.json).getJSONObject("nodes").getJSONObject("1")
            .getJSONObject("inputs").getJSONArray("length").getString(0))
    }

    // --- P0-8: diff preview ------------------------------------------------

    @Test fun diffPreviewDescribesTheSamePayload() {
        // Integers keep the rendering deterministic: org.json rewrites 5.0 as 5.
        val original = api("4" to node("PrimitiveInt", "value" to 5))
        val edited = api("4" to node("PrimitiveInt", "value" to 8))
        val patch = H3ApiPatch.applyDetailed(original, edited, original)
        val preview = H3DiffPreview.build(patch, listOf("77" to "Reroute"), nodesBefore = 2, nodesAfter = 2)
        assertEquals(2, preview.lines.size)
        assertEquals("#4.value", preview.lines[0].title)
        assertEquals("5 → 8", preview.lines[0].detail)
        assertEquals(H3DiffPreview.Kind.REMOVED, preview.lines[1].kind)
        assertEquals(0, preview.nodeDelta)
    }
    @Test fun referenceSlotsFollowTheWorkflowNotTheUi() {
        val official2 = H3WorkflowAdapter.inspect(official("官方参考生视频.json"))!!
        assertEquals(listOf(0, 1), official2.referenceInputs.keys.sorted())
        val ext6 = H3WorkflowAdapter.inspect(official("video_minimax_h3_r2v.json"))!!
        assertEquals(listOf(0, 1, 2, 3, 4, 5), ext6.referenceInputs.keys.sorted())
        assertTrue("no workflow defines a 9th reference slot", ext6.referenceInputs.keys.none { it > 5 })
    }

    @Test fun manualModeOverrideAppliesWithoutTouchingTheNodes() {
        val src = official("官方图生视频.json")
        val auto = H3WorkflowAdapter.inspectWithMode(src, null)!!
        assertEquals(H3WorkflowAdapter.Mode.I2V, auto.mode)
        val forced = H3WorkflowAdapter.inspectWithMode(src, H3WorkflowAdapter.Mode.T2V)!!
        assertEquals(H3WorkflowAdapter.Mode.T2V, forced.mode)
        assertEquals(auto.coreId, forced.coreId)
        assertEquals(auto.bindings.size, forced.bindings.size)
    }
}
