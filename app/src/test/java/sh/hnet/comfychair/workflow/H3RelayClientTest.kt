package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H3RelayClientTest {

    /** What the plugin answers today: relayable templates keyed by id, so the digest comes along. */
    private val objectForm = """
        {
          "plugin": "h3-relay",
          "version": "1.0.0",
          "enabled": true,
          "templates": {"itv_h3_mobile_r2v": "9da5d11e", "ttv_h3_mobile_frames": "7c0bf93b"},
          "relayTemplates": {"itv_h3_mobile_r2v": "9da5d11e", "itv_h3_mobile_r2v_turbo": "fb9aabcf"},
          "refSlots": {"itv_h3_mobile_r2v": 9, "itv_h3_mobile_r2v_turbo": 9},
          "tailBackoffMs": [150, 250, 450, 700]
        }
    """.trimIndent()

    /** An earlier plugin answered with a bare list; a probe that only read this shape would now say "no plugin". */
    private val arrayForm = """
        {
          "plugin": "h3-relay",
          "version": "1.0.0",
          "relayTemplates": ["itv_h3_mobile_r2v", "itv_h3_mobile_r2v_turbo"],
          "refSlots": {"itv_h3_mobile_r2v": 9}
        }
    """.trimIndent()

    @Test
    fun `reads relay templates from the keyed table`() {
        val info = H3RelayClient.parse(objectForm)
        assertNotNull(info)
        // A JSON object has no order of its own; only membership is promised.
        assertEquals(setOf("itv_h3_mobile_r2v", "itv_h3_mobile_r2v_turbo"), info!!.relayTemplates.toSet())
        assertEquals(9, info.capacity("itv_h3_mobile_r2v"))
        assertEquals(9, info.capacity("itv_h3_mobile_r2v_turbo"))
        assertEquals(listOf(150L, 250L, 450L, 700L), info.tailBackoffMs)
    }

    @Test
    fun `still reads the older list form`() {
        val info = H3RelayClient.parse(arrayForm)
        assertNotNull(info)
        assertEquals(2, info!!.relayTemplates.size)
        assertEquals(9, info.capacity("itv_h3_mobile_r2v"))
    }

    @Test
    fun `rejects a version below the contract`() {
        assertNull(H3RelayClient.parse(objectForm.replace("\"1.0.0\"", "\"0.9.9\"")))
    }

    @Test
    fun `rejects a switched off plugin`() {
        assertNull(H3RelayClient.parse(objectForm.replace("\"enabled\": true", "\"enabled\": false")))
    }

    @Test
    fun `rejects a server that chains nothing`() {
        assertNull(H3RelayClient.parse(objectForm.replace(
            "\"relayTemplates\": {\"itv_h3_mobile_r2v\": \"9da5d11e\", \"itv_h3_mobile_r2v_turbo\": \"fb9aabcf\"}",
            "\"relayTemplates\": {}"
        )))
        assertNull(H3RelayClient.parse(objectForm.replace("\"plugin\": \"h3-relay\"", "\"plugin\": \"other\"")))
    }

    @Test
    fun `compares versions numerically`() {
        assertTrue(H3RelayClient.atLeast("1.10.0", "1.2.0"))
        assertFalse(H3RelayClient.atLeast("1.2", "1.2.1"))
        assertTrue(H3RelayClient.atLeast("1.0.0", "1.0.0"))
        assertTrue(H3RelayClient.atLeast("1.0.1", "1.0.0"))
        assertFalse(H3RelayClient.atLeast("", "1.0.0"))
        assertFalse(H3RelayClient.atLeast("abc", "1.0.0"))
    }
}
