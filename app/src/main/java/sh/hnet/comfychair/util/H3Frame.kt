package sh.hnet.comfychair.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Last-frame capture for chained generation.
 *
 * A clip's final keyframe is often not its final image, so we step back a little and skip frames
 * that decode to black (fade-outs and the padding some encoders leave at the tail).
 */
object H3Frame {
    private val backOffMs = longArrayOf(100L, 250L, 450L, 700L)
    private const val MAX_EDGE = 1280

    fun lastFramePng(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            var last: Bitmap? = null
            for (back in backOffMs) {
                val us = (duration - back).coerceAtLeast(0L) * 1000L
                val frame = runCatching {
                    retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull() ?: continue
                last = frame
                if (!isBlack(frame)) return encode(frame)
            }
            return last?.let { encode(it) }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isBlack(bitmap: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        var sum = 0L
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val c = small.getPixel(x, y)
                sum += ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
            }
        }
        val avg = sum.toDouble() / (small.width * small.height)
        if (small !== bitmap) small.recycle()
        return avg < 12.0
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_EDGE) {
            val factor = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true
            )
        } else bitmap
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }
}
