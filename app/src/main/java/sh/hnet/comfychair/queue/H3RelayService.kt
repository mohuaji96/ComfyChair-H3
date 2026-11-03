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
        when (intent?.action) {
            ACTION_PAUSE -> {
                H3RelayStatus.setPaused(true)
                startForeground(ID, buildNotification())
            }
            ACTION_RESUME -> {
                H3RelayStatus.setPaused(false)
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
        val title = if (paused) "完整连拍已暂停 · 还剩 $remaining 段" else "完整连拍进行中"
        val body = "已跑完 $done 段，还剩 $remaining 段"

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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "继续" else "暂停", toggle)
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
                NotificationChannel(CHANNEL_ID, "完整连拍", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "连拍进度与暂停"
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
