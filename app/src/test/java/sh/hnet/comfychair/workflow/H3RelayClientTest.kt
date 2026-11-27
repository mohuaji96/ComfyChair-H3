package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H3RelayClientTest {

    /** Unwraps a successful answer; a failed one fails the test with its own reason. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> ok(answer: H3RelayClient.Answer<T>): T =
        (answer as H3RelayClient.Answer.Ok<T>).value

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

    // ---- chain status -----------------------------------------------------------------

    /** A chain the plugin reports mid-run: two segments done, the third being sampled. */
    private val runningChain = """
        {
          "chainId": "c-7", "prefix": "h3_seq", "state": "running",
          "segment": 3, "of": 5, "done": 2, "stage": "running",
          "pauseRequested": true, "templateId": "itv_h3_mobile_r2v",
          "claimed": false, "error": null, "failedAt": null,
          "startedAt": 1700000000000, "updatedAt": 1700000060000
        }
    """.trimIndent()

    @Test
    fun `reads a running chain`() {
        val answer = H3RelayClient.parseSummary(runningChain)
        assertTrue(answer is H3RelayClient.Answer.Ok)
        val summary = ok(answer)
        assertEquals("c-7", summary.chainId)
        assertEquals("h3_seq", summary.prefix)
        assertEquals(3, summary.current)
        assertEquals(5, summary.of)
        assertEquals(2, summary.done)
        assertTrue(summary.pauseRequested)
        assertTrue(summary.running)
        assertFalse(summary.ended)
        assertEquals("running", summary.stage)
        // A JSON null is not the string "null"; a bar that shows "失败：null" is a bug.
        assertNull(summary.error)
        assertNull(summary.failedAt)
    }

    @Test
    fun `a finished chain is ended but not running`() {
        val done = runningChain.replace("\"state\": \"running\"", "\"state\": \"done\"")
        val summary = ok(H3RelayClient.parseSummary(done))
        assertTrue(summary.ended)
        assertFalse(summary.running)
    }

    @Test
    fun `a pruned chain is gone rather than refused`() {
        // The plugin forgot the chain after a restart: no reason to show an error card.
        assertTrue(H3RelayClient.parseSummary("{\"error\": \"unknown_chain\"}") is H3RelayClient.Answer.Gone)
    }

    @Test
    fun `a refusal keeps the code and the slot count`() {
        val text = "{\"error\": \"too_many_references\", \"message\": \"segment 3 would carry 10\", \"slots\": 9}"
        val refused = (H3RelayClient.parseSummary(text) as H3RelayClient.Answer.Rejected).refused
        assertEquals(H3RelayClient.Failure.TOO_MANY_REFERENCES, refused.failure)
        assertEquals(9, refused.slots)
        assertEquals("segment 3 would carry 10", refused.message)
    }

    @Test
    fun `an unreadable body is unreachable, not a refusal`() {
        assertTrue(H3RelayClient.parseSummary("<html>") is H3RelayClient.Answer.Unreachable)
    }

    @Test
    fun `maps every wire code the plugin can send`() {
        assertEquals(H3RelayClient.Failure.DISK_FULL, H3RelayClient.Failure.of("disk_full"))
        assertEquals(H3RelayClient.Failure.TEMPLATE_NO_RELAY, H3RelayClient.Failure.of("template_no_relay"))
        assertEquals(H3RelayClient.Failure.UNKNOWN_CHAIN, H3RelayClient.Failure.of("unknown_chain"))
        // A code from a newer plugin is still a refusal, just one we cannot name yet.
        assertEquals(H3RelayClient.Failure.UNKNOWN, H3RelayClient.Failure.of("something_new"))
        assertEquals(H3RelayClient.Failure.UNKNOWN, H3RelayClient.Failure.of(null))
    }

    // ---- acceptance ---------------------------------------------------------------------

    @Test
    fun `acceptance echoes the project name the server settled on`() {
        val text = """
            {"accepted": true, "chainId": "c-9", "templates": ["itv_h3_mobile_r2v"],
             "project": "夜景-2", "segments": 3, "queuePosition": 1}
        """.trimIndent()
        val accepted = ok(H3RelayClient.parseAccepted(text))
        // The plugin dedupes against every chain it has ever run; the phone must not invent its own suffix.
        assertEquals("夜景-2", accepted.project)
        assertEquals("c-9", accepted.chainId)
        assertEquals(3, accepted.segments)
        assertEquals(1, accepted.queuePosition)
        assertFalse(accepted.duplicate)
    }

    @Test
    fun `a resent chain is a duplicate rather than a second chain`() {
        val text = "{\"accepted\": true, \"chainId\": \"c-9\", \"duplicate\": true, \"queuePosition\": 4}"
        val accepted = ok(H3RelayClient.parseAccepted(text))
        assertTrue(accepted.duplicate)
        assertEquals(4, accepted.queuePosition)
    }

    @Test
    fun `a refused submit names the reason`() {
        val text = "{\"error\": \"template_no_relay\", \"message\": \"this template has no continuation mode\"}"
        val refused = (H3RelayClient.parseAccepted(text) as H3RelayClient.Answer.Rejected).refused
        assertEquals(H3RelayClient.Failure.TEMPLATE_NO_RELAY, refused.failure)
        assertNull(refused.slots)
    }

    // ---- request body -------------------------------------------------------------------

    @Test
    fun `the body carries one plan entry per segment and the user's project name`() {
        val body = H3RelayClient.chainBody(
            "itv_h3_mobile_r2v",
            "夜景",
            listOf(
                H3RelayClient.Segment(
                    prompt = "一",
                    params = org.json.JSONObject().put("seconds", 6),
                    references = listOf("a.png", "b.png"),
                    seed = 42L
                ),
                H3RelayClient.Segment(
                    prompt = "二",
                    params = org.json.JSONObject().put("seconds", 6),
                    references = listOf("a.png")
                )
            )
        )
        assertEquals("itv_h3_mobile_r2v", body.getString("templateId"))
        // `segments` is a number the plugin compares against `plan.length`; disagreeing is a 400.
        assertEquals(2, body.getInt("segments"))
        assertEquals(2, body.getJSONArray("plan").length())
        assertEquals("夜景", body.getJSONObject("numbering").getString("prefix"))
        val first = body.getJSONArray("plan").getJSONObject(0)
        assertEquals(42L, first.getLong("seed"))
        assertEquals(2, first.getJSONArray("references").length())
        // An unset optional must be absent, not a JSON null the plugin would reject.
        assertFalse(first.has("startImage"))
        assertFalse(first.has("graph"))
        assertFalse(body.getJSONArray("plan").getJSONObject(1).has("seed"))
    }

    @Test
    fun `a whole graph is sent only when the caller has one`() {
        val graph = org.json.JSONObject().put("1", org.json.JSONObject().put("class_type", "LoadImage"))
        val body = H3RelayClient.chainBody(
            "itv_h3_mobile_r2v", "p",
            listOf(H3RelayClient.Segment("一", org.json.JSONObject(), graph = graph))
        )
        assertEquals(
            "LoadImage",
            body.getJSONArray("plan").getJSONObject(0).getJSONObject("graph")
                .getJSONObject("1").getString("class_type")
        )
    }
}
