package sh.hnet.comfychair.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.LiteGraphExecutionPatch
import sh.hnet.comfychair.workflow.LiteGraphSourceEditor
import sh.hnet.comfychair.workflow.NodeTypeDefinition

@RunWith(AndroidJUnit4::class)
class LiteGraphExecutionPatchTest {
    private fun def(type: String, vararg fields: Pair<String, String>): NodeTypeDefinition =
        NodeTypeDefinition(type, "test", fields.map {
            InputDefinition(it.first, it.second, isRequired = true)
        }, emptyList())

    private val defs = mapOf(
        "PrimitiveStringMultiline" to def("PrimitiveStringMultiline", "value" to "STRING"),
        "ResolutionSelector" to def("ResolutionSelector", "aspect_ratio" to "COMBO", "megapixels" to "FLOAT"),
        "VHS_LoadVideo" to def("VHS_LoadVideo", "video" to "STRING")
    )
    private val getDef: (String) -> NodeTypeDefinition? = { defs[it] }
    private fun source(mode: Int = 0) = """{
       "nodes":[
         {"id":2,"type":"PrimitiveStringMultiline","mode":$mode,
          "inputs":[{"name":"value","type":"STRING","widget":{"name":"value"},"link":null}],
          "widgets_values":["original prompt"],"widgets_values_named":{"value":"original prompt"}},
         {"id":20,"type":"ResolutionSelector","mode":0,
          "inputs":[{"name":"aspect_ratio","type":"COMBO","widget":{"name":"aspect_ratio"},"link":null},
                    {"name":"megapixels","type":"FLOAT","widget":{"name":"megapixels"},"link":null}],
          "widgets_values":["4:3",0.3],"widgets_values_named":{"aspect_ratio":"4:3","megapixels":0.3}}
       ],"links":[],"definitions":{"subgraphs":[{"id":"keep-me","nodes":[]}]}
    }""".trimIndent()

    private fun prepared(source: String) = LiteGraphConverter(getDef).convert(JSONObject(source)).jsonContent

    @Test fun patchPromptIntoPreparedApiWithoutMutatingOriginal() {
        val original = source()
        val working = LiteGraphSourceEditor.changeTopLevelWidget(original, 2, "value", "revised").json
        val prepared = prepared(original)
        val result = LiteGraphExecutionPatch.apply(original, working, prepared, getDef)
        val json = JSONObject(result.json).getJSONObject("nodes")
        assertEquals("revised", json.getJSONObject("2").getJSONObject("inputs").getString("value"))
        assertEquals(0.3, json.getJSONObject("20").getJSONObject("inputs").getDouble("megapixels"), 0.00001)
        assertEquals("original prompt", JSONObject(original).getJSONArray("nodes").getJSONObject(0)
            .getJSONObject("widgets_values_named").getString("value"))
        assertEquals(listOf("2:value"), result.changedFields)
    }

    @Test fun patchMultipleValuesWithoutChangingOthers() {
        val original = source()
        val first = LiteGraphSourceEditor.changeTopLevelWidget(original, 20, "megapixels", 0.7).json
        val working = LiteGraphSourceEditor.changeTopLevelWidget(first, 2, "value", "new prompt").json
        val result = LiteGraphExecutionPatch.apply(original, working, prepared(original), getDef)
        val nodes = JSONObject(result.json).getJSONObject("nodes")
        assertEquals(0.7, nodes.getJSONObject("20").getJSONObject("inputs").getDouble("megapixels"), 0.00001)
        assertEquals("4:3", nodes.getJSONObject("20").getJSONObject("inputs").getString("aspect_ratio"))
        assertEquals(2, result.changedFields.size)
    }

    @Test fun unchangedSourceReturnsPromptUnmodified() {
        val prompt = prepared(source())
        val result = LiteGraphExecutionPatch.apply(source(), source(), prompt, getDef)
        assertEquals(prompt, result.json)
        assertTrue(result.changedFields.isEmpty())
    }

    @Test fun quickEditsOverridePreparedTemporaryScalarValue() {
        val original = source()
        val working = LiteGraphSourceEditor.changeTopLevelWidget(original, 2, "value", "quick value").json
        val prompt = JSONObject(prepared(original))
        prompt.getJSONObject("nodes").getJSONObject("2").getJSONObject("inputs")
            .put("value", "temporary form value")
        val result = LiteGraphExecutionPatch.apply(original, working, prompt.toString(), getDef)
        assertEquals("quick value", JSONObject(result.json).getJSONObject("nodes")
            .getJSONObject("2").getJSONObject("inputs").getString("value"))
    }

    @Test fun quickEditCannotOverwritePreparedConnection() {
        val original = source()
        val working = LiteGraphSourceEditor.changeTopLevelWidget(original, 2, "value", "quick value").json
        val prompt = JSONObject(prepared(original))
        prompt.getJSONObject("nodes").getJSONObject("2").getJSONObject("inputs")
            .put("value", org.json.JSONArray().put("20").put(0))
        assertThrows(IllegalArgumentException::class.java) {
            LiteGraphExecutionPatch.apply(original, working, prompt.toString(), getDef)
        }
    }

    @Test fun failsClosedOnMissingNodeDefinition() {
        val original = source()
        val working = LiteGraphSourceEditor.changeTopLevelWidget(original, 2, "value", "new").json
        assertThrows(IllegalArgumentException::class.java) {
            LiteGraphExecutionPatch.apply(original, working, prepared(original)) { null }
        }
    }

    @Test fun failsClosedWhenPreparedPromptDropsNode() {
        val original = source()
        val working = LiteGraphSourceEditor.changeTopLevelWidget(original, 2, "value", "new").json
        val prompt = JSONObject(prepared(original))
        prompt.getJSONObject("nodes").remove("2")
        assertThrows(IllegalStateException::class.java) {
            LiteGraphExecutionPatch.apply(original, working, prompt.toString(), getDef)
        }
    }

    @Test fun failsClosedIfBypassedNodeIsModified() {
        val original = source(mode = 4)
        val working = LiteGraphSourceEditor.changeTopLevelWidget(original, 2, "value", "new").json
        assertThrows(IllegalArgumentException::class.java) {
            LiteGraphExecutionPatch.apply(original, working, prepared(original), getDef)
        }
    }

    @Test fun failsClosedOnUnrelatedTopologyChange() {
        val original = source()
        val working = JSONObject(LiteGraphSourceEditor.changeTopLevelWidget(original, 2, "value", "new").json)
        working.getJSONArray("links").put(org.json.JSONArray().put(1))
        assertThrows(IllegalArgumentException::class.java) {
            LiteGraphExecutionPatch.apply(original, working.toString(), prepared(original), getDef)
        }
    }

    @Test fun objectShapedVideoWidgetIsApplied() {
        val original = """{"nodes":[{"id":8,"type":"VHS_LoadVideo","mode":0,
           "inputs":[{"name":"video","type":"STRING","widget":{"name":"video"},"link":null}],
           "widgets_values":{"video":"old.mp4"},"widgets_values_named":{"video":"old.mp4"}}],"links":[]}"""
        val working = LiteGraphSourceEditor.changeTopLevelWidget(original, 8, "video", "new.mp4").json
        val result = LiteGraphExecutionPatch.apply(original, working, prepared(original), getDef)
        assertEquals("new.mp4", JSONObject(result.json).getJSONObject("nodes")
            .getJSONObject("8").getJSONObject("inputs").getString("video"))
    }
}
