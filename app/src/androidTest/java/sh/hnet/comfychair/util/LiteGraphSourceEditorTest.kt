package sh.hnet.comfychair.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.storage.LiteGraphSourceStore
import sh.hnet.comfychair.workflow.LiteGraphSourceEditor
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiteGraphSourceEditorTest {
    private val sample = """{
      "id":"sample","nodes":[
        {"id":20,"type":"ResolutionSelector","pos":[100,200],"mode":0,
         "inputs":[{"name":"aspect_ratio","widget":{"name":"aspect_ratio"},"link":null},
                   {"name":"megapixels","widget":{"name":"megapixels"},"link":null},
                   {"name":"multiple","widget":{"name":"multiple"},"link":null}],
         "widgets_values":["4:3",0.3,32],
         "widgets_values_named":{"aspect_ratio":"4:3","megapixels":0.3,"multiple":32}},
        {"id":166,"type":"VHS_LoadVideo","mode":4,
         "inputs":[{"name":"video","widget":{"name":"video"},"link":null}],
         "widgets_values":{"video":"old.mp4","videopreview":{"paused":true}},
         "widgets_values_named":{"video":"old.mp4"}},
        {"id":15,"type":"Text","inputs":[{"name":"value","widget":{"name":"value"},"link":8}],
         "widgets_values":["attached"],"widgets_values_named":{"value":"attached"}}
      ],"links":[[8,3,0,15,0,"STRING"]],
      "definitions":{"subgraphs":[{"id":"abc","nodes":[{"id":9,"type":"Unknown"}],"inputs":[]}]}}
    """.trimIndent()

    private fun node(json: String, index: Int) = JSONObject(json).getJSONArray("nodes").getJSONObject(index)

    @Test fun namedNumericUpdateAlsoUpdatesPositionalWithoutTouchingOtherNodes() {
        val edit = LiteGraphSourceEditor.changeTopLevelWidget(sample, 20, "megapixels", 0.7)
        assertEquals(0.3, (edit.oldValue as Number).toDouble(), 0.00001)
        assertEquals(0.7, node(edit.json, 0).getJSONArray("widgets_values").getDouble(1), 0.00001)
        assertEquals(0.7, node(edit.json, 0).getJSONObject("widgets_values_named").getDouble("megapixels"), 0.00001)
        assertEquals(node(sample,1).toString(),node(edit.json,1).toString())
        assertEquals(JSONObject(sample).getJSONObject("definitions").toString(), JSONObject(edit.json).getJSONObject("definitions").toString())
        assertEquals(JSONObject(sample).getJSONArray("links").toString(), JSONObject(edit.json).getJSONArray("links").toString())
    }

    @Test fun objectVideoUpdatePreservesUnknownFieldsAndBypassFlag() {
        val edit = LiteGraphSourceEditor.changeTopLevelWidget(sample, 166, "video", "new.mp4")
        val video = node(edit.json, 1)
        assertEquals("new.mp4", video.getJSONObject("widgets_values").getString("video"))
        assertEquals("new.mp4", video.getJSONObject("widgets_values_named").getString("video"))
        assertTrue(video.getJSONObject("widgets_values").getJSONObject("videopreview").getBoolean("paused"))
        assertEquals(4, video.getInt("mode"))
    }

    @Test fun connectedInputFailsClosed() {
        val result = runCatching { LiteGraphSourceEditor.changeTopLevelWidget(sample,15,"value","unsafe") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("connected"))
    }

    @Test fun unknownFieldFailsClosed() {
        assertTrue(runCatching { LiteGraphSourceEditor.changeTopLevelWidget(sample,20,"unexpected",1) }.isFailure)
    }

    @Test fun importedOriginIsImmutableAndWorkingRevisionIsSeparate() {
        val store = LiteGraphSourceStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID().toString()
        store.saveOriginal(id, sample)
        assertEquals(sample, store.exportOriginal(id))
        assertTrue(runCatching { store.saveOriginal(id,"{}") }.isFailure)
        store.changeWidget(id,20,"megapixels",0.9)
        assertEquals(sample, store.exportOriginal(id))
        assertEquals(0.9, node(store.readWorking(id)!!,0).getJSONObject("widgets_values_named").getDouble("megapixels"),0.00001)
    }
}
