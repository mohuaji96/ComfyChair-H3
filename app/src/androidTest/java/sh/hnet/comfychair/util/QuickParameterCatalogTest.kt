package sh.hnet.comfychair.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.storage.QuickParameterPins
import sh.hnet.comfychair.workflow.QuickParameterCatalog
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuickParameterCatalogTest {
    private val workflow = """{
      "nodes": [
        {"id":2,"type":"PrimitiveStringMultiline","title":"Prompt","mode":0,
          "inputs":[{"name":"value","widget":{"name":"value"},"link":null}],
          "widgets_values":["hello"],"widgets_values_named":{"value":"hello"}},
        {"id":20,"type":"ResolutionSelector","mode":0,
          "inputs":[{"name":"aspect_ratio","widget":{"name":"aspect_ratio"},"link":null},
                    {"name":"megapixels","widget":{"name":"megapixels"},"link":null}],
          "widgets_values":["16:9",0.3],"widgets_values_named":{"aspect_ratio":"16:9","megapixels":0.3}},
        {"id":42,"type":"BasicScheduler","title":"Scheduler Second Pass","mode":0,
          "inputs":[{"name":"steps","widget":{"name":"steps"},"link":99}],
          "widgets_values":[8],"widgets_values_named":{"steps":8}},
        {"id":101,"type":"H3PowerLoraStack","mode":0,
          "widgets_values":[{"lora":"x"}],"widgets_values_named":{"lora_1":{"lora":"x"}}},
        {"id":166,"type":"VHS_LoadVideo","mode":4,
          "inputs":[{"name":"video","widget":{"name":"video"},"link":null}],
          "widgets_values":{"video":"clip.mp4"},"widgets_values_named":{"video":"clip.mp4"}}
       ]
    }""".trimIndent()

    @Test fun discoversCoreFieldsWithoutFlatteningNestedWidgets() {
        val fields = QuickParameterCatalog.discover(workflow)
        assertTrue(fields.any { it.key == "2:value" && it.group == QuickParameterCatalog.Group.PROMPT && it.editable })
        assertTrue(fields.any { it.key == "20:megapixels" && it.editable })
        assertTrue(fields.any { it.key == "166:video" && it.bypassed && it.editable })
        assertFalse(fields.any { it.nodeId == 101 })
    }

    @Test fun connectedWidgetIsVisibleButNotEditable() {
        val field = QuickParameterCatalog.discover(workflow).single { it.key == "42:steps" }
        assertFalse(field.editable)
        assertNotNull(field.reason)
    }

    @Test fun pinnedParametersAreScopedByWorkflowId() {
        val store = QuickParameterPins(InstrumentationRegistry.getInstrumentation().targetContext)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        store.setPinned(a,"20:megapixels",true)
        assertTrue("20:megapixels" in store.read(a))
        assertFalse("20:megapixels" in store.read(b))
        store.setPinned(a,"20:megapixels",false)
        assertTrue(store.read(a).isEmpty())
    }
}
