package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.update.UpdateVersion

/** 更新检查的版本比较：公开版与个人版两条谱系都要判对。 */
class UpdateVersionTest {

    @Test
    fun `a tag carries the versionCode when it is written down`() {
        val (name, code) = UpdateVersion.parseTag("v0.9.8.668-vc78")
        assertEquals("0.9.8.668", name)
        assertEquals(78, code)
    }

    @Test
    fun `a tag without a versionCode leaves it empty`() {
        val (name, code) = UpdateVersion.parseTag("v0.9.8.61")
        assertEquals("0.9.8.61", name)
        assertNull(code)
    }

    @Test
    fun `a local version name in the private scheme parses cleanly`() {
        val (name, code) = UpdateVersion.parseTag("0.9.8.8382.570-h3")
        assertEquals("0.9.8.8382.570", name)
        assertNull(code)
    }

    @Test
    fun `versionCode decides when both sides have one`() {
        // 个人版的 320 已经跑在公开版 78 前面，不该弹提示。
        assertFalse(UpdateVersion.isNewer("0.9.8.668", 78, "0.9.8.8382.570", 320))
        assertTrue(UpdateVersion.isNewer("0.9.8.900", 321, "0.9.8.8382.570", 320))
        assertFalse(UpdateVersion.isNewer("0.9.8.900", 320, "0.9.8.8382.570", 320))
    }

    @Test
    fun `without a versionCode the dotted segments decide`() {
        assertTrue(UpdateVersion.isNewer("0.9.8.670", null, "0.9.8.668", 78))
        assertFalse(UpdateVersion.isNewer("0.9.8.668", null, "0.9.8.8382.570", 320))
        assertFalse(UpdateVersion.isNewer("0.9.8.668", null, "0.9.8.668", 78))
        // 段数不同按缺位补零，1.0 不比 1.0.0 新。
        assertFalse(UpdateVersion.isNewer("1.0", null, "1.0.0", 1))
        assertTrue(UpdateVersion.isNewer("1.0.1", null, "1.0", 1))
    }

    @Test
    fun `a tag that is not a number is never newer`() {
        assertFalse(UpdateVersion.isNewer("nightly", null, "0.9.8.8382.570", 320))
        assertFalse(UpdateVersion.isNewer("", null, "0.9.8.8382.570", 320))
    }
}
