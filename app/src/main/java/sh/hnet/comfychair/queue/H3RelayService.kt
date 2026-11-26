package sh.hnet.comfychair.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import sh.hnet.comfychair.MainActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.AppSettings

/**
 * Keeps a 完整连拍 run alive while the phone is locked.
 *
 * The queue itself is advanced by the app process; this service exists so the system treats that
 * process as foreground instead of a candidate for collection between two segments. It also owns
 * the progress notification, whose only action is a pause toggle -- clearing the queue from a
 * notification is one mistap away from losing work.
 */
class H3RelayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY may recreate the service with a null Intent after process death.
        // Rebuild the notification from durable queue metadata; the H3 page reconciles the
        // current prompt against /queue and /history when the user returns to the workspace.
        if (intent == null) {
            H3RelayQueue.restore(this)
            if (!H3RelayStatus.active) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(ID, buildNotification())
            return START_STICKY
        }
        when (intent.action) {
            ACTION_PAUSE -> {
                H3RelayQueue.setPaused(this, true)
                startForeground(ID, buildNotification())
            }
            ACTION_RESUME -> {
                H3RelayQueue.setPaused(this, false)
                startForeground(ID, buildNotification())
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForeground(ID, buildNotification())
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val paused = H3RelayStatus.paused.value
        val done = H3RelayStatus.done.value
        val remaining = H3RelayStatus.remaining.value
        val detail = H3RelayStatus.detail.value
        val title = if (paused) getString(R.string.relay_title_paused, remaining) else getString(R.string.relay_title_running)
        // The segment counter says how far the run got; it says nothing about the segment that is
        // actually costing the time. That line goes first, the tally moves to the sub-text.
        val body = if (detail.isNotBlank()) detail else getString(R.string.relay_body_progress, done, remaining)

        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, H3RelayService::class.java)
                .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.h3_24px)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(if (detail.isNotBlank()) getString(R.string.relay_body_progress, done, remaining) else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(if (paused) R.string.relay_action_resume else R.string.relay_action_pause), toggle)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "h3_relay"
        const val ID = 4711
        const val ACTION_PAUSE = "sh.hnet.comfychair.relay.PAUSE"
        const val ACTION_RESUME = "sh.hnet.comfychair.relay.RESUME"
        const val ACTION_STOP = "sh.hnet.comfychair.relay.STOP"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.relay_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.relay_channel_desc)
                    setShowBadge(false)
                }
            )
        }

        /** Starts or refreshes the foreground notification, if the user turned it on. */
        fun update(context: Context) {
            if (!AppSettings.isRelayNotifyEnabled(context) || !H3RelayStatus.active) return
            val intent = Intent(context, H3RelayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, H3RelayService::class.java))
        }
    }
}
