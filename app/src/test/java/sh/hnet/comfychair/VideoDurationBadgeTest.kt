package sh.hnet.comfychair

import org.junit.Test
import org.junit.Assert.*
import sh.hnet.comfychair.cache.VideoDimensions

class VideoDurationBadgeTest {
    private fun formatPlaybackTime(ms: Long, roundUp: Boolean = false): String {
        val totalSeconds = if (roundUp) (ms + 999) / 1000 else ms / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    @Test
    fun testDurationFormatting() {
        assertEquals("0:06", formatPlaybackTime(6000L))
        assertEquals("0:15", formatPlaybackTime(15000L))
        assertEquals("1:05", formatPlaybackTime(65000L))
        assertEquals("1:01:01", formatPlaybackTime(3661000L))
        
        // 向上取整验证：5.2秒向上取整显示 0:06，避免进度条超过总时长
        assertEquals("0:06", formatPlaybackTime(5200L, roundUp = true))
        assertEquals("0:15", formatPlaybackTime(14100L, roundUp = true))
    }

    @Test
    fun testVideoDimensionsWithDuration() {
        val dims = VideoDimensions(width = 1024, height = 768, durationMs = 15000L)
        assertEquals(1024, dims.width)
        assertEquals(768, dims.height)
        assertEquals(15000L, dims.durationMs)
    }
}
