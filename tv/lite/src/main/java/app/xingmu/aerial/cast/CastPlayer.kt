package app.xingmu.aerial.cast

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

/**
 * The casting player, on ExoPlayer 2 because the platform MediaPlayer on Android 4.4 cannot play
 * HLS at all and drops HTTPS ones outright. OkHttp is the data source so the TLS 1.2 workaround for
 * 4.4 (see Tls12Setup) applies to the media requests too.
 *
 * No controller on screen: the phone is the remote, and a stray tap cannot pause the cast.
 */
class CastPlayerActivity : Activity(), Player.Listener {

    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private val ticker = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val exo = player ?: return
            CastPlayer.positionMs = exo.currentPosition.coerceAtLeast(0L)
            val duration = exo.duration
            CastPlayer.durationMs = if (duration == C.TIME_UNSET || duration < 0) 0L else duration
            CastPlayer.state = when (exo.playbackState) {
                Player.STATE_READY, Player.STATE_BUFFERING ->
                    if (exo.playWhenReady) CastPlayer.State.PLAYING else CastPlayer.State.PAUSED
                else -> CastPlayer.State.STOPPED
            }
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isEmpty()) {
            finish()
            return
        }
        CastPlayer.currentUri = url
        CastPlayer.state = CastPlayer.State.PLAYING

        // DefaultHttpDataSource keeps the dependency set 4.4-compatible; extension-okhttp declares a
        // higher minSdk and breaks the manifest merge.
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("XingmuAerial/1.26")
            .setAllowCrossProtocolRedirects(true)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                com.google.android.exoplayer2.source.DefaultMediaSourceFactory(dataSource)
                    .setLoadErrorHandlingPolicy(
                        com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy(3),
                    ),
            )
            .build()
        player = exo
        exo.addListener(this)

        val playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            this.player = exo
        }
        view = playerView
        setContentView(
            FrameLayout(this).apply {
                addView(
                    playerView,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            },
        )

        if (url.endsWith(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true)) {
            exo.setMediaSource(HlsMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(Uri.parse(url))))
        } else {
            exo.setMediaSource(
                com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(dataSource)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url))),
            )
        }
        exo.prepare()
        exo.playWhenReady = true
        exo.seekTo(intent.getIntExtra(EXTRA_POSITION, 0).toLong())
        CastPlayer.bind(Control(exo))
        ticker.post(tick)
    }

    override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
        Log.w(CastPlayer.TAG, "player error: " + error.errorCodeName + " / " + error.message)
        CastPlayer.state = CastPlayer.State.STOPPED
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (CastPlayer.state == CastPlayer.State.PLAYING) CastPlayer.pause()
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        CastPlayer.unbind()
        player?.release()
        player = null
        view = null
        super.onDestroy()
    }

    private class Control(private val exo: ExoPlayer) : CastPlayer.Control {
        override fun pause() = runCatching { exo.pause() }.let { }
        override fun resume() = runCatching { exo.play() }.let { }
        override fun stop() = runCatching { exo.stop() }.let { }
        override fun seek(ms: Long) = runCatching { exo.seekTo(ms) }.let { }
        override fun isPlaying(): Boolean = exo.isPlaying
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"
    }
}

/** Shared playback state: the DLNA renderer, the agent heartbeat and the activity all read it. */
object CastPlayer {
    const val TAG = "AerialCastPlayer"

    enum class State { STOPPED, PLAYING, PAUSED }

    interface Control {
        fun pause()
        fun resume()
        fun stop()
        fun seek(ms: Long)
        fun isPlaying(): Boolean
    }

    @Volatile var state: State = State.STOPPED
    @Volatile var currentUri: String = ""
    @Volatile var positionMs: Long = 0L
    @Volatile var durationMs: Long = 0L

    @Volatile private var control: Control? = null

    fun bind(next: Control) {
        control = next
    }

    fun unbind() {
        control = null
    }

    fun pause() {
        control?.pause()
        state = State.PAUSED
    }

    fun resume() {
        control?.resume()
        state = State.PLAYING
    }

    fun stop() {
        control?.stop()
        control = null
        state = State.STOPPED
        positionMs = 0L
    }

    fun seek(ms: Long) {
        control?.seek(ms)
        positionMs = ms
    }

    /** uc:hh:mm:ss, the shape UPnP expects. */
    fun hms(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }
}
