package app.xingmu.aerial.cast

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * Cast playback on ijkplayer: the only engine on this project that decodes HLS with the TV's own
 * hardware codecs. The 4.4 box cannot reach the CDN and its WebView draws video black, so the stream
 * comes from the backend relay and is decoded here natively.
 */
class IjkCastPlayerActivity : Activity(), SurfaceHolder.Callback {

    private var player: IjkMediaPlayer? = null
    private var surface: SurfaceView? = null

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
        val view = SurfaceView(this)
        view.holder.addCallback(this)
        surface = view
        setContentView(
            FrameLayout(this).apply {
                addView(
                    view,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            },
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        start(holder, intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = release()

    private fun start(holder: SurfaceHolder, url: String) {
        release()
        val media = IjkMediaPlayer()
        player = media
        runCatching {
            // Hardware decoding is mandatory on 4.x for 1080p HLS; the software path stutters.
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 30)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 8000000)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "analyzeduration", 2000000)
            media.setDisplay(holder)
            media.setDataSource(url)
            media.setOnPreparedListener { it.start() }
            media.setOnCompletionListener {
                CastPlayer.state = CastPlayer.State.STOPPED
                finish()
            }
            media.setOnErrorListener { _, what, extra ->
                Log.w(CastPlayer.TAG, "ijk error $what/$extra")
                CastPlayer.state = CastPlayer.State.STOPPED
                finish()
                true
            }
            media.setOnInfoListener { _, what, _ ->
                if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.i(CastPlayer.TAG, "first frame")
                }
                false
            }
            media.prepareAsync()
        }.onFailure {
            Log.w(CastPlayer.TAG, "ijk prepare failed: " + it.javaClass.simpleName)
            finish()
        }
        CastPlayer.bind(Control(media))
        CastPlayer.state = CastPlayer.State.PLAYING
    }

    override fun onPause() {
        super.onPause()
        if (CastPlayer.state == CastPlayer.State.PLAYING) CastPlayer.pause()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        CastPlayer.unbind()
        val media = player
        player = null
        runCatching {
            media?.stop()
            media?.release()
        }
        // Keep the surface alive for a possible second click on the same stream.
        surface = surface
    }

    private class Control(private val media: IjkMediaPlayer) : CastPlayer.Control {
        override fun pause() = runCatching { media.pause() }.let { }
        override fun resume() = runCatching { media.start() }.let { }
        override fun stop() = runCatching { media.stop() }.let { }
        override fun seek(ms: Long) = runCatching { media.seekTo(ms) }.let { }
        override fun isPlaying(): Boolean = runCatching { media.isPlaying }.getOrDefault(false)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"
    }
}
