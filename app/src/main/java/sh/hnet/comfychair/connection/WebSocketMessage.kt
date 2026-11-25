package sh.hnet.comfychair.connection

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/**
 * Typed WebSocket messages parsed from ComfyUI server.
 * ConnectionManager parses raw messages and emits these typed events via SharedFlow.
 */
sealed class WebSocketMessage {
    data class ExecutionStart(val promptId: String) : WebSocketMessage()
    data class Executing(val promptId: String?, val node: String?) : WebSocketMessage()
    data class ExecutionComplete(val promptId: String) : WebSocketMessage()
    data class Progress(val value: Int, val max: Int) : WebSocketMessage()
    /** A job failed in a node: the server's account of it (type, message, traceback), for the user only, never logged. */
    data class ExecutionError(
        val promptId: String?,
        val message: String,
        val nodeType: String? = null,
        val nodeId: String? = null,
        val exceptionType: String? = null,
        val traceback: List<String> = emptyList(),
        /** One of this app's jobs, as known when the error came (the registry forgets it right after) */
        val isOwn: Boolean = false
    ) : WebSocketMessage()
    data class ExecutionSuccess(val promptId: String) : WebSocketMessage()
    data class Status(val queueRemaining: Int?) : WebSocketMessage()
    data class ExecutionCached(val nodeCount: Int) : WebSocketMessage()
    data class PreviewImage(val bitmap: Bitmap) : WebSocketMessage()
    /**
     * One denoise step of a video job, framed by a preview override node on the server: the still it
     * decoded plus the step it belongs to. The plain [PreviewImage] carries no step, and a video
     * sampler's frame only arrives through this event when such a node is in the graph.
     */
    data class SamplingPreview(
        val bitmap: Bitmap?,
        val step: Int,
        val total: Int,
        val stepMillis: Long? = null,
        /** An animated frame sequence, when the server sent a WebP clip; drawn by itself, no bitmap. */
        val animated: Drawable? = null,
        /** Fragmented MP4 clip when the server encoded video/mp4 (default when PyAV/x264 is present). */
        val videoBytes: ByteArray? = null,
        val width: Int = 0,
        val height: Int = 0
    ) : WebSocketMessage()
    data class Unknown(val type: String) : WebSocketMessage()
}

/**
 * WebSocket connection state.
 * Subscribers can react to state changes (e.g., check server on reconnect).
 */
sealed class WebSocketState {
    data object Disconnected : WebSocketState()
    data object Connecting : WebSocketState()
    data object Connected : WebSocketState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : WebSocketState()
    data class Failed(
        val reason: String?,
        val failureType: ConnectionFailure = ConnectionFailure.NETWORK
    ) : WebSocketState()
}
