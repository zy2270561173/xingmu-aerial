package app.xingmu.aerial.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the 星投屏 agent alive while the TV is on. A foreground service because Android kills plain
 * background services within minutes, and the phone must still be able to reach this TV then.
 *
 * Deliberately tiny: it owns the notification and the lifecycle, the protocol lives in [CastAgent].
 */
class CastService : Service() {
    private var dlna: DlnaRenderer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promote()
        CastAgent.start(this)
        // The TV is a DLNA renderer too, so a phone's cast button finds it while the agent runs.
        dlna = DlnaRenderer(this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CastAgent.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        promote()
        CastAgent.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        dlna?.stop()
        dlna = null
        CastAgent.stop()
        super.onDestroy()
    }

    private fun promote() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "星投屏", NotificationManager.IMPORTANCE_MIN)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle("星投屏")
            .setContentText(CastAgent.message.ifEmpty { "正在连接控制中心" })
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.build()
        } else {
            // build() is API 16; 4.0/4.1 use the deprecated getNotification().
            @Suppress("DEPRECATION")
            builder.notification
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "aerial-cast"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_STOP = "app.xingmu.aerial.cast.STOP"

        fun start(context: Context) {
            val intent = Intent(context, CastService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }
    }
}
