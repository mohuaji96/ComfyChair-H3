package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连拍 naming: an unchecked prefix would either break the save node or collide across segments. */
class H3NamingTest {

    private fun prompt(prefix: String): String = JSONObject().apply {
        put("12", JSONObject().apply {
            put("class_type", "SaveVideo")
            put("inputs", JSONObject().put("filename_prefix", prefix).put("format", "mp4"))
        })
        put("3", JSONObject().apply {
            put("class_type", "KSampler")
            put("inputs", JSONObject().put("seed", 1))
        })
    }.toString()

    @Test
    fun `auto numbering continues from the store value`() {
        assertEquals("h3_seq_01", H3Naming.prefixFor("h3_seq", 1, true))
        assertEquals("h3_seq_12", H3Naming.prefixFor("h3_seq", 12, true))
    }

    @Test
    fun `custom prefix keeps its own numbering`() {
        assertEquals("秋天广告_03", H3Naming.prefixFor("秋天广告", 3, true))
    }

    @Test
    fun `numbering off uses the name verbatim`() {
        assertEquals("秋天广告", H3Naming.prefixFor("秋天广告", 3, false))
    }

    @Test
    fun `blank input still produces a usable prefix`() {
        assertEquals("h3_seq_01", H3Naming.prefixFor("", 1, true))
        assertEquals("h3", H3Naming.prefixFor("  ", 1, false))
    }

    @Test
    fun `characters a filesystem rejects are replaced`() {
        assertEquals("a_b_c", H3Naming.sanitize("a/b\\c"))
        assertEquals("秋天_广告", H3Naming.sanitize("秋天 广告"))
        assertTrue(H3Naming.sanitize("x".repeat(80)).length <= 40)
    }

    @Test
    fun `chain name falls back so recordings still group`() {
        assertEquals("h3_seq", H3Naming.chainName("", true))
        assertEquals("秋天广告", H3Naming.chainName("秋天广告", true))
    }

    @Test
    fun `the save node is rewritten and nothing else is touched`() {
        val before = prompt("h3")
        val after = H3Naming.applyPrefix(before, "h3_seq_02")
        val root = JSONObject(after)
        assertEquals("h3_seq_02", root.getJSONObject("12").getJSONObject("inputs").getString("filename_prefix"))
        assertEquals("mp4", root.getJSONObject("12").getJSONObject("inputs").getString("format"))
        assertEquals(1, root.getJSONObject("3").getJSONObject("inputs").getInt("seed"))
    }

    @Test
    fun `a workflow without a prefix input comes back unchanged`() {
        val noPrefix = JSONObject().put("9", JSONObject().apply {
            put("class_type", "PreviewImage")
            put("inputs", JSONObject())
        }).toString()
        assertEquals(noPrefix, H3Naming.applyPrefix(noPrefix, "h3_seq_01"))
    }

    @Test
    fun `a blank prefix never wipes the template value`() {
        val before = prompt("h3")
        assertEquals(before, H3Naming.applyPrefix(before, "   "))
    }
}
