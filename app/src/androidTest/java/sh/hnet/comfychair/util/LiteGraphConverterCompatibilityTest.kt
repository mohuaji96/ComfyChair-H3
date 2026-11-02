package sh.hnet.comfychair.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition

/** Instrumented tests: Android org.json implementation is required. */
@RunWith(AndroidJUnit4::class)
class LiteGraphConverterCompatibilityTest {
    private fun def(type: String, vararg names: String): NodeTypeDefinition = NodeTypeDefinition(
        classType = type, category = "test", outputs = emptyList(),
        inputs = names.map { InputDefinition(it, "STRING", isRequired = true) }
    )

    @Test fun namedValuesOverridePositionalValues() {
        val converter = LiteGraphConverter { if (it == "Text") def(it, "value") else null }
        val input = JSONObject("""{"nodes":[{"id":1,"type":"Text","widgets_values":["old"],"widgets_values_named":{"value":"new"}}],"links":[]}""")
        val output = JSONObject(converter.convert(input).jsonContent).getJSONObject("nodes")
        assertEquals("new", output.getJSONObject("1").getJSONObject("inputs").getString("value"))
    }

    @Test fun objectWidgetValuesUseEditableNamesOnly() {
        val converter = LiteGraphConverter { if (it == "Video") def(it, "video", "force_rate") else null }
        val input = JSONObject("""{"nodes":[{"id":1,"type":"Video","widgets_values":{"video":"clip.mp4","force_rate":24,"videopreview":{"paused":false}}}],"links":[]}""")
        val mapped = JSONObject(converter.convert(input).jsonContent).getJSONObject("nodes")
            .getJSONObject("1").getJSONObject("inputs")
        assertEquals("clip.mp4", mapped.getString("video"))
        assertEquals(24, mapped.getInt("force_rate"))
        assertFalse(mapped.has("videopreview"))
    }

    @Test fun namedSubgraphInputReachesInternalNodeWithoutProxyWidgets() {
        val converter = LiteGraphConverter { if (it == "Text") def(it, "value") else null }
        val graph = JSONObject("""{
          "nodes":[{"id":2,"type":"sub","inputs":[{"name":"prompt","link":null}],
                     "widgets_values_named":{"prompt":"Hello from instance"}}],
          "links":[],
          "definitions":{"subgraphs":[{"id":"sub","name":"test-subgraph",
            "inputNode":{"id":-10},"outputNode":{"id":-20},
            "inputs":[{"name":"prompt","type":"STRING","linkIds":[1]}],"outputs":[],
            "nodes":[{"id":1,"type":"Text","inputs":[{"name":"value","link":1}]}],
            "links":[{"id":1,"origin_id":-10,"origin_slot":0,
                      "target_id":1,"target_slot":0,"type":"STRING"}]
          }]}
        }""")
        val output = JSONObject(converter.convert(graph).jsonContent).getJSONObject("nodes")
        val expanded = output.getJSONObject("3").getJSONObject("inputs")
        assertEquals("Hello from instance", expanded.getString("value"))
    }

    @Test fun liveConnectionOverridesSubgraphWidgetValue() {
        val converter = LiteGraphConverter { if (it == "Text") def(it, "value") else null }
        val graph = JSONObject("""{
          "nodes":[{"id":1,"type":"Text","widgets_values_named":{"value":"source"}},
                   {"id":2,"type":"sub","inputs":[{"name":"prompt","link":9}],
                    "widgets_values_named":{"prompt":"should not replace wire"}}],
          "links":[[9,1,0,2,0,"STRING"]],
          "definitions":{"subgraphs":[{"id":"sub","name":"test-subgraph",
            "inputNode":{"id":-10},"outputNode":{"id":-20},
            "inputs":[{"name":"prompt","type":"STRING","linkIds":[1]}],"outputs":[],
            "nodes":[{"id":1,"type":"Text","inputs":[{"name":"value","link":1}]}],
            "links":[{"id":1,"origin_id":-10,"origin_slot":0,
                      "target_id":1,"target_slot":0,"type":"STRING"}]
          }]}
        }""")
        val output = JSONObject(converter.convert(graph).jsonContent).getJSONObject("nodes")
        val wire = output.getJSONObject("3").getJSONObject("inputs").getJSONArray("value")
        assertEquals("1", wire.getString(0))
        assertEquals(0, wire.getInt(1))
    }
}
