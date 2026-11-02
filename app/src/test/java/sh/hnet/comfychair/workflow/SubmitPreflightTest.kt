package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitPreflightTest {

    private fun prompt(vararg nodes: Pair<String, JSONObject>) = JSONObject().apply {
        nodes.forEach { (id, node) -> put(id, node) }
    }

    private fun node(classType: String, input: String, value: Any) = JSONObject()
        .put("class_type", classType)
        .put("inputs", JSONObject().put(input, value))

    // Verbatim names from the 22:33:09 server error.
    private val unetOptions = listOf(
        "H3\\官方首尾帧控制版minimax_h3_fl2va_pruned_int8_convrot.safetensors",
        "krea2\\无审核\\Moody-Krea-Mix-v8_00001__clean-int8_convrot.safetensors",
        "krea2\\krea2_turbo_int8_convrot.safetensors"
    )

    @Test fun forwardSlashNameIsReplacedByTheServerString() {
        val sent = "krea2/无审核/Moody-Krea-Mix-v8_00001__clean-int8_convrot.safetensors"
        val json = prompt("66" to node("UNETLoader", "unet_name", sent)).toString()
        val r = SubmitPreflight.prepare(json) { c, i ->
            if (c == "UNETLoader" && i == "unet_name") unetOptions else null
        }
        assertTrue(r.issues.isEmpty())
        assertEquals(unetOptions[1], JSONObject(r.prompt).getJSONObject("66").getJSONObject("inputs").getString("unet_name"))
        assertEquals(1, r.rewrites.size)
    }

    @Test fun unknownModelIsReportedAndNotGuessed() {
        val json = prompt("66" to node("UNETLoader", "unet_name", "krea2/无审核/missing.safetensors")).toString()
        val r = SubmitPreflight.prepare(json) { _, _ -> unetOptions }
        assertFalse(r.ok)
        assertEquals("66", r.issues.single().nodeId)
        assertEquals("unet_name", r.issues.single().input)
    }

    @Test fun unresolvedPlaceholderBlocksTheSubmit() {
        val json = prompt("83" to node("LoraLoaderModelOnly", "lora_name", "{{lora_name}}")).toString()
        val r = SubmitPreflight.prepare(json) { _, _ -> listOf("a.safetensors") }
        assertFalse(r.ok)
        assertTrue(r.issues.single().detail.contains("{{lora_name}}"))
    }

    @Test fun knownNameIsLeftByteIdentical() {
        val json = prompt("66" to node("UNETLoader", "unet_name", unetOptions[0])).toString()
        val r = SubmitPreflight.prepare(json) { _, _ -> unetOptions }
        assertTrue(r.ok)
        assertTrue(r.rewrites.isEmpty())
        assertEquals(unetOptions[0], JSONObject(r.prompt).getJSONObject("66").getJSONObject("inputs").getString("unet_name"))
    }

    @Test fun unknownNodeDefinitionIsNotTouched() {
        val json = prompt("9" to node("MysteryNode", "weird", "a/b.safetensors")).toString()
        val r = SubmitPreflight.prepare(json) { _, _ -> null }
        assertTrue(r.ok)
        assertTrue(r.rewrites.isEmpty())
        assertNull(SubmitPreflight.resolveCombo("x", emptyList()))
    }
}
