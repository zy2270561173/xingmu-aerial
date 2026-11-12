package app.xingmu.aerial.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the jukebox's decoder alive while the TV shows Home, the screensaver or another app, and
 * gives it a notification with the same play/pause/next controls as the page. [MusicPlayback] owns
 * the player; this service owns the lifecycle and the notification.
 */
class MusicService : Service() {
    private var notificationListener: ((MusicSnapshot) -> Unit)? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MusicPlayback.attach(this)
        promote(MusicPlayback.snapshot())
        val listener: (MusicSnapshot) -> Unit = { snapshot -> show(snapshot) }
        notificationListener = listener
        MusicPlayback.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> MusicPlayback.toggle()
            ACTION_NEXT -> MusicPlayback.next()
            ACTION_PREVIOUS -> MusicPlayback.previous()
            ACTION_STOP -> {
                MusicPlayback.stop()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> show(MusicPlayback.snapshot())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notificationListener?.let { MusicPlayback.removeListener(it) }
        notificationListener = null
        MusicPlayback.release()
        stopForeground(true)
        super.onDestroy()
    }

    private fun promote(snapshot: MusicSnapshot) {
        val notification = build(snapshot)
        startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun show(snapshot: MusicSnapshot) {
        val notification = build(snapshot)
        if (!foreground) {
            startForeground(NOTIFICATION_ID, notification)
            foreground = true
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun build(snapshot: MusicSnapshot): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Api26.channel(this)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Api26.builder(this)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val song = snapshot.song
        val title = song?.name ?: "点歌台"
        val detail = when {
            song == null -> "手机扫码点歌"
            snapshot.playing -> song.singer.ifEmpty { "正在播放" } + " · " + qualityShort(snapshot.quality)
            snapshot.prepared -> song.singer.ifEmpty { "已暂停" } + " · 已暂停"
            snapshot.status.isNotEmpty() -> snapshot.status
            else -> song.singer
        }
        builder
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                "上一首",
                serviceIntent(ACTION_PREVIOUS, 1),
            )
            builder.addAction(
                if (snapshot.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (snapshot.playing) "暂停" else "播放",
                serviceIntent(ACTION_TOGGLE, 2),
            )
            builder.addAction(
                android.R.drawable.ic_media_next,
                "下一首",
                serviceIntent(ACTION_NEXT, 3),
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.build()
        } else {
            @Suppress("DEPRECATION")
            builder.notification
        }
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun qualityShort(key: String): String = when {
        key.startsWith("MP3_") -> "MP3 " + key.removePrefix("MP3_") + "K"
        key.startsWith("ACC_") -> "AAC " + key.removePrefix("ACC_") + "K"
        key.startsWith("OGG_") -> "OGG " + key.removePrefix("OGG_") + "K"
        key.isEmpty() -> "点歌台"
        else -> key
    }

    companion object {
        private const val CHANNEL = "aerial-music"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_TOGGLE = "app.xingmu.aerial.cast.MUSIC_TOGGLE"
        const val ACTION_NEXT = "app.xingmu.aerial.cast.MUSIC_NEXT"
        const val ACTION_PREVIOUS = "app.xingmu.aerial.cast.MUSIC_PREVIOUS"
        const val ACTION_STOP = "app.xingmu.aerial.cast.MUSIC_STOP"

        fun start(context: Context) {
            val intent = Intent(context, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Api26.start(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    /**
     * API 26's notification channel and foreground-service start live in their own class: Dalvik on
     * 4.x otherwise verifies these references while loading [MusicService] and throws
     * NoClassDefFoundError before the version check can help (this box did).
     */
    private object Api26 {
        fun channel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL, "点歌台", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }

        fun builder(context: Context): Notification.Builder = Notification.Builder(context, CHANNEL)

        fun start(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }
    }
}
