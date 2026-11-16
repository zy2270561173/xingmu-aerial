package app.xingmu.aerial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import app.xingmu.aerial.Ui.textPx
import app.xingmu.aerial.cast.MusicApi
import app.xingmu.aerial.cast.MusicPlayback
import app.xingmu.aerial.cast.MusicSnapshot
import kotlin.random.Random

/**
 * The screensaver, in the full app's look (ui/home/Screensaver.kt), over everything with Home
 * hidden beneath it.
 *
 * Aerial: Apple's films in the system player (VideoView) through [FilmProxy], a little shade at
 * the top and bottom, the time top left and the place bottom left; both drift a few units every
 * two minutes so nothing sits on the same pixels all night. Three failures in a row and it is the
 * clock.
 *
 * Photos: the owner's photos (else the daily ones, else the built-ins), each for 18 seconds,
 * slowly zooming from 1.04 to 1.12 and drifting up to 40 units across (Ken Burns, as the full
 * app), the next fading in over 2 seconds; still pictures where there is no GPU.
 *
 * My videos: the films sent from the phone page, one after another, from the TV's own storage.
 *
 * Clock: the large clock (date, time, weather) over the wallpaper at 75%, somewhere new each minute.
 *
 * MV (Personalize → 屏保 → MV): the jukebox's music video itself, full screen and hardware decoded
 * as in the page, with a big half-transparent clock in one corner and the now-playing card in the
 * other; the middle of the picture is left alone. No MV playing (or 屏保 → 壁纸) and the saver is
 * the wallpaper clock it has always been.
 *
 * A song playing over any of these: its cover, name and singer in the top-right card, and the one
 * lyric line it is on in the bottom-left corner ([SaverLyric]) — never inside the card, and kept
 * clear of the big clock and of the photo/film caption.
 *
 * The time shows over the films and photos when Personalize says so.
 */
class SaverView(context: Context, mode: ScreensaverMode, private val proxy: FilmProxy, private val wallpaper: Bitmap?) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val time = context.label("", 33.8f, 600, 0xEBFFFFFF.toInt(), spacingEm = 0.05f)
    private val caption = TextView(context)
    private val shade = Shade(context)
    private var video: VideoView? = null
    private var films: List<Film> = emptyList()
    private var next = 0
    private var failures = 0
    private var clockOnly = mode == ScreensaverMode.Clock
    private var clock: ClockView? = null
    /** A quiet now-playing card with cover, name, singer and a playing dot; it takes no key. */
    private val music = SaverMusic(context)
    /**
     * The one lyric line the song is on, in its own bottom-left corner: outside the card, clear of
     * the big clock, 45% of the screen wide. Hidden while a MV is the picture.
     */
    private val lyric = SaverLyric(context)
    /**
     * MV 跟随：MV 正在播放时，屏保用 MV 封面当背景（和 Bing 壁纸一样整屏绘制），而不是纯黑；
     * 电影/照片/壁纸让位给这张图，卡片与时钟仍在上层。
     */
    private val mvBackdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        alpha = 0.72f
        visibility = GONE
    }
    private var mvActive = false
    private var mvCoverUrl = ""
    /** The clock mode's wallpaper view, hidden while the MV background owns the screen. */
    private var wallpaperView: ImageView? = null

    // ---- MV mode (Personalize → 屏保 → MV) ----

    /** Personalize chose MV: a playing music video fills the saver, everything else is the wallpaper. */
    private val mvSaver = LiteSettings.saverMode == SaverMode.Mv
    /** The MV layer is what the saver is showing right now. */
    private var mvLive = false
    private var mvVideoW = 0
    private var mvVideoH = 0
    /**
     * The screensaver's video target, full screen under the clock and the card. ijk's MediaCodec
     * wants its output surface at configure time on these boxes, so the view exists from the start
     * and only its visibility changes; MusicPlayback rebuilds the decoder behind it (see setSurface).
     */
    private val mvView = TextureView(context).apply {
        visibility = GONE
        isClickable = false
        isFocusable = false
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                mvSurface = Surface(texture)
                if (mvLive) mvSurface?.let { MusicPlayback.setSurface(it, MusicPlayback.SURFACE_SAVER) }
            }

            override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                applyMvFill()
            }

            override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                mvSurface = null
                MusicPlayback.setSurface(null, MusicPlayback.SURFACE_SAVER)
                return true
            }

            override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
        }
    }
    /**
     * One Surface for the view's SurfaceTexture: MusicPlayback rebuilds the decoder when it is
     * handed a different Surface, and a second wrapper of the same texture would look like one.
     */
    private var mvSurface: Surface? = null
    /** The big clock over the video: one corner, half transparent, out of the picture's middle. */
    private val mvClock = ClockView(context).apply {
        large = true
        alpha = 0.7f
        refresh()
        weather = Weather.line(context)
        visibility = GONE
    }

    private val musicTick = object : Runnable {
        override fun run() {
            val snapshot = MusicPlayback.snapshot()
            music.update(snapshot, MusicPlayback.positionMs())
            // MV is the picture (or the MV saver's own mode): the lyric steps aside then.
            lyric.update(if (snapshot.mvActive || mvSaver) null else music.currentLine())
            applyMv(snapshot)
            handler.postDelayed(this, 500L)
        }
    }
    /** Films from the phone page, played from a file rather than through the proxy. */
    private val local = mode == ScreensaverMode.Videos
    private val photos = mode == ScreensaverMode.Photos
    private val showTime = LiteSettings.screensaverClock && (mode == ScreensaverMode.Aerial || photos)

    private val pictures = Wallpapers.saverPictures()
    private val frames = arrayOfNulls<ImageView>(2)
    private var front = 0
    private var kenBurns: android.animation.Animator? = null
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** No picture after [STALL_MS]: a slow network or a film the decoder never starts. */
    private val stalled = Runnable { fail() }

    private val tick = object : Runnable {
        override fun run() {
            time.text = timeText(context)
            clock?.let { it.refresh(); it.weather = Weather.line(context); moveClock() }
            if (mvClock.visibility == VISIBLE) {
                mvClock.refresh()
                mvClock.weather = Weather.line(context)
            }
            val now = System.currentTimeMillis()
            // Every two minutes the words wander a little.
            if (!clockOnly && (now / 60_000) % 2 == 0L) drift()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        keepScreenOn = true
        // The MV cover sits at the very bottom: every other layer (film, photo, clock, card) is
        // added above it, so hiding those layers reveals the cover instead of black.
        addView(mvBackdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The MV itself, just above that cover: the clock and the card go on top of it.
        addView(mvView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        time.setShadowLayer(Ui.px(4f), 0f, 0f, 0x66000000)
        caption.apply {
            setTextColor(0xEBFFFFFF.toInt())
            textPx(19f)
            typeface = Ui.font(600, 32f)
            if (Build.VERSION.SDK_INT >= 21) letterSpacing = -0.01f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = Ui.pxi(620f)
            includeFontPadding = false
            setShadowLayer(Ui.px(4f), 0f, 0f, 0x66000000)
        }
        when (mode) {
            ScreensaverMode.Aerial -> films = Aerials.load(context).shuffled()
            // The owner's own films carry no caption, as in the full app.
            ScreensaverMode.Videos -> films = ScreensaverVideos.all(context).shuffled().map { Film(it.file.toURI().toString(), null) }
            else -> Unit
        }
        if (!clockOnly && !photos && films.isEmpty()) clockOnly = true
        // 屏保 → MV is the wallpaper clock when no MV plays: the video only takes over on top of it.
        if (mvSaver) clockOnly = true
        when {
            clockOnly -> showClock()
            photos -> showPhotos()
            else -> showFilms()
        }
        installMusic()
        tick.run()
        musicTick.run()
    }

    /** Puts the read-only now-playing card above whatever the screensaver is showing. */
    private fun installMusic(compact: Boolean = false) {
        // MV mode keeps the card small (`compact`): a third of the screen wide, in a corner, so the
        // picture's middle is left to the video. The audio card is a single row now that the lyric
        // lives in its own corner view.
        val params = LayoutParams(
            Ui.pxi(if (compact) 320f else 410f),
            Ui.pxi(if (compact) 132f else 84f),
            Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = Ui.pxi(if (compact) 40f else 44f)
            rightMargin = Ui.pxi(if (compact) 48f else 56f)
        }
        music.compact = compact
        if (music.parent == null) {
            music.alpha = 0.94f
            addView(music, params)
        } else {
            music.layoutParams = params
            music.bringToFront()
        }
        // The lyric is a view of its own in the bottom-left corner, never inside the card. Above
        // the film/photo caption when there is one, so the two never touch.
        val lyricParams = LayoutParams(Ui.pxi(LYRIC_WIDTH), Ui.pxi(LYRIC_HEIGHT), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.pxi(56f)
            bottomMargin = Ui.pxi(if (caption.parent != null) 124f else LYRIC_BOTTOM)
        }
        if (lyric.parent == null) addView(lyric, lyricParams) else lyric.layoutParams = lyricParams
        lyric.bringToFront()
    }

    /**
     * MV mode: the video takes the screen while a MV is really playing, and the wallpaper clock
     * comes back the moment it stops, is paused, or the song turns out to be audio only.
     */
    private fun syncMvSaver(snapshot: MusicSnapshot) {
        if (snapshot.mvVideoWidth != mvVideoW || snapshot.mvVideoHeight != mvVideoH) {
            mvVideoW = snapshot.mvVideoWidth
            mvVideoH = snapshot.mvVideoHeight
            if (mvLive) applyMvFill()
        }
        // The TextureView only paints on a hardware accelerated window; without one the saver
        // stays the wallpaper instead of turning black.
        val want = snapshot.mvActive && snapshot.playing && snapshot.song != null &&
            (parent == null || isHardwareAccelerated)
        if (want == mvLive) {
            // Nobody holds the picture any more though the MV never stopped: the jukebox page was
            // opened over the saver (a song pushed from the phone) and closed again. Take it back,
            // or the saver would sit on one frozen frame.
            if (mvLive && MusicPlayback.attachedSurfaceOwner().isEmpty()) attachMvSurface()
            return
        }
        mvLive = want
        if (want) {
            Log.i("AerialLite", "saver mv on: " + snapshot.mvTitle.ifEmpty { snapshot.song?.name.orEmpty() } +
                " " + snapshot.mvVideoWidth + "x" + snapshot.mvVideoHeight)
            enterMv()
        } else {
            Log.i("AerialLite", "saver mv off: back to the wallpaper")
            leaveMv()
        }
    }

    /** The video over the wallpaper: the film, the pictures and the wallpaper clock step aside. */
    private fun enterMv() {
        video?.let { runCatching { it.pause() }; it.visibility = GONE }
        for (frame in frames) frame?.visibility = GONE
        wallpaperView?.visibility = GONE
        clock?.visibility = GONE
        mvBackdrop.visibility = GONE
        mvClock.visibility = VISIBLE
        mvView.visibility = VISIBLE
        installMusic(compact = true)
        mvView.post { applyMvFill() }
        attachMvSurface()
    }

    /** No MV any more: the wallpaper clock, the card and (in the other modes) the film come back. */
    private fun leaveMv() {
        MusicPlayback.setSurface(null, MusicPlayback.SURFACE_SAVER)
        mvView.visibility = GONE
        mvClock.visibility = GONE
        video?.visibility = VISIBLE
        for (frame in frames) frame?.visibility = VISIBLE
        wallpaperView?.visibility = VISIBLE
        clock?.visibility = VISIBLE
        installMusic(compact = false)
    }

    /**
     * Hands the MV the screensaver's surface. The TextureView's texture appears a moment after it
     * is shown, so this waits for it instead of giving up on the first try.
     */
    private fun attachMvSurface(retries: Int = 5) {
        if (!mvLive) return
        val surface = mvSurface ?: runCatching { mvView.surfaceTexture }.getOrNull()?.let { texture ->
            Surface(texture).also { mvSurface = it }
        }
        if (surface != null) {
            MusicPlayback.setSurface(surface, MusicPlayback.SURFACE_SAVER)
            return
        }
        if (retries > 0) handler.postDelayed({ attachMvSurface(retries - 1) }, 200L)
    }

    /** Fills the screen at the picture's own aspect ratio, cropping the overflow, as the page does. */
    private fun applyMvFill() {
        val width = mvView.width
        val height = mvView.height
        if (width <= 0 || height <= 0) return
        val srcWidth = if (mvVideoW > 0) mvVideoW else 16
        val srcHeight = if (mvVideoH > 0) mvVideoH else 9
        val fill = maxOf(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        val matrix = Matrix()
        matrix.setScale(fill * srcWidth / width, fill * srcHeight / height, width / 2f, height / 2f)
        runCatching { mvView.setTransform(matrix) }
    }

    /**
     * MV 跟随：MV 播放时把 MV 封面当整屏背景，电影/照片/壁纸暂时让位（不停止音频），
     * 卡片和时钟留在上层；MV 结束或切回音频时恢复原来的画面。
     *
     * With 屏保 → MV chosen this is the video itself instead of its cover ([syncMvSaver]);
     * the cover is only a backdrop for the default 壁纸 choice.
     */
    private fun applyMv(snapshot: MusicSnapshot) {
        if (mvSaver) {
            syncMvSaver(snapshot)
            return
        }
        val active = snapshot.mvActive
        if (active != mvActive) {
            mvActive = active
            if (active) {
                mvBackdrop.visibility = VISIBLE
                // A film under the cover would burn a second decoder with nothing to show.
                runCatching { video?.pause() }
                video?.visibility = GONE
            } else {
                mvBackdrop.visibility = GONE
                mvBackdrop.setImageBitmap(null)
                mvCoverUrl = ""
                if (video != null) {
                    video?.visibility = VISIBLE
                    runCatching { video?.start() }
                }
            }
            for (frame in frames) frame?.visibility = if (active) GONE else VISIBLE
            wallpaperView?.visibility = if (active) GONE else VISIBLE
        }
        if (!active) return
        // No MV info yet (or a MV without a cover): the song's own cover keeps the screen alive.
        val url = snapshot.mvCover.ifEmpty { snapshot.song?.cover.orEmpty() }
        if (url.isEmpty() || url == mvCoverUrl) return
        mvCoverUrl = url
        // Through the backend's cover relay: 4.4's OkHttp cannot reach the CDN's TLS directly.
        MusicApi.cover(url) { bitmap, _ ->
            if (mvActive && mvCoverUrl == url && bitmap != null) mvBackdrop.setImageBitmap(bitmap)
        }
    }

    private fun showClock() {
        clockOnly = true
        video?.let { it.stopPlayback(); removeView(it) }
        video = null
        removeView(shade); removeView(caption); removeView(time)
        // The wallpaper's light under the clock, at 75% over black.
        if (wallpaper != null) {
            val view = ImageView(context).apply {
                setImageBitmap(wallpaper)
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0.75f
                visibility = if (mvActive) GONE else VISIBLE
            }
            wallpaperView = view
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val c = ClockView(context).apply { large = true; refresh(); weather = Weather.line(context) }
        clock = c
        addView(c, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        moveClock()
        if (mvSaver) showMvClock()
        installMusic()
    }

    /**
     * The MV mode's clock: the same large clock, half transparent, pinned to the bottom left corner
     * (the card takes the top right) and scaled down so the middle of the picture stays clear. The
     * pivot is its own bottom left corner, so it scales into the corner instead of towards the middle.
     */
    private fun showMvClock() {
        if (mvClock.parent != null) return
        addView(mvClock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.pxi(48f)
            bottomMargin = Ui.pxi(36f)
        })
        mvClock.pivotX = 0f
        mvClock.pivotY = Ui.px(200f)
        mvClock.scaleX = 0.62f
        mvClock.scaleY = 0.62f
    }

    /** The full app's ClockSaver: anywhere in the screen less a margin and the clock's own size; faded across on a GPU. */
    private fun moveClock() {
        val c = clock ?: return
        val x = Ui.px(64f + (960f - 128f - 420f) * Random.nextFloat())
        // The bottom-left corner belongs to the lyric line: the big clock stops a gap above it
        // instead of drifting onto it, so the two sit in one column without ever touching.
        val maxY = Ui.canvasH - LYRIC_BOTTOM - LYRIC_HEIGHT - LYRIC_GAP - CLOCK_HEIGHT
        val y = Ui.px(56f + (maxY - 56f).coerceAtLeast(0f) * Random.nextFloat())
        if (c.isHardwareAccelerated && c.alpha > 0f && c.translationX != 0f) {
            c.animate().alpha(0f).setDuration(1200).withLayerCompat().setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    c.animate().setListener(null)
                    c.translationX = x; c.translationY = y
                    c.animate().alpha(1f).setDuration(1200).start()
                }
            }).start()
        } else {
            c.translationX = x; c.translationY = y
        }
    }

    private fun android.view.ViewPropertyAnimator.withLayerCompat() = if (Build.VERSION.SDK_INT >= 16) withLayer() else this

    private fun showFilms() {
        val v = VideoView(context)
        video = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addWords()
        v.setOnPreparedListener { mp ->
            failures = 0
            handler.removeCallbacks(stalled)
            mp.setVolume(0f, 0f)
            mp.start()
        }
        v.setOnCompletionListener { play() }
        v.setOnErrorListener { _: MediaPlayer, what: Int, extra: Int ->
            Log.i("AerialLite", "film failed: $what/$extra")
            fail()
            true
        }
        play()
    }

    /** The shade, the time (when chosen) and the caption over the pictures. */
    private fun addWords() {
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (showTime) addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = Ui.pxi(40f); leftMargin = Ui.pxi(56f)
        })
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.pxi(56f); bottomMargin = Ui.pxi(44f)
        })
        installMusic()
    }

    private fun play() {
        val v = video ?: return
        val film = films[next % films.size]
        next++
        setCaption(film.caption ?: "")
        handler.removeCallbacks(stalled)
        handler.postDelayed(stalled, STALL_MS)
        v.setVideoURI(Uri.parse(if (local) film.url else proxy.local(film.url)))
    }

    // ---- photos ----


    private fun showPhotos() {
        if (pictures.isEmpty()) { showClock(); return }
        for (i in 0..1) {
            val f = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
            frames[i] = f
            addView(f, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addWords()
        nextPhoto()
    }

    /** The next picture, decoded off the main thread, faded in over the last. */
    private fun nextPhoto() {
        val wp = pictures[next % pictures.size]
        next++
        loader.execute {
            val b = runCatching { Wallpapers.picture(wp, Ui.screenW, Ui.screenH, whole = true) }.getOrNull()
            handler.post {
                if (b == null) { handler.postDelayed(::nextPhoto, 1000); return@post }
                val back = frames[front]
                front = 1 - front
                val f = frames[front] ?: return@post
                (f.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.takeIf { it !== wallpaper }?.recycle()
                f.setImageBitmap(b)
                setCaption(if (wp.kind == WallpaperKind.Daily) wp.title else "")
                val smooth = LiteSettings.full(isHardwareAccelerated)
                // Over the last picture, under the words.
                removeView(f)
                addView(f, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                if (smooth) {
                    // Ken Burns: from 1.04 to 1.12 over 20 s, drifting one way or the other.
                    val dir = if (Random.nextBoolean()) 1f else -1f
                    kenBurns?.cancel()
                    kenBurns = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                        f,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.04f, 1.12f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.04f, 1.12f),
                        android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -dir * Ui.px(20f), dir * Ui.px(20f)),
                    ).apply { duration = 20_000; interpolator = android.view.animation.LinearInterpolator(); start() }
                    f.alpha = 0f
                    f.animate().alpha(1f).setDuration(2000).start()
                    back?.animate()?.alpha(0f)?.setDuration(2000)?.start()
                } else {
                    f.alpha = 1f
                    back?.alpha = 0f
                }
                handler.postDelayed(::nextPhoto, PHOTO_MS)
            }
        }
    }

    /** One name after the other, as the full app's: the old fades out, then the new fades in. */
    private fun setCaption(text: String) {
        if (!caption.isHardwareAccelerated || caption.text.isNullOrEmpty()) { caption.text = text; return }
        caption.animate().alpha(0f).setDuration(400).setListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                caption.animate().setListener(null)
                caption.text = text
                caption.animate().alpha(1f).setStartDelay(0).setDuration(600).start()
            }
        }).start()
    }

    /** The full app's saverDrift: up to 24 across and 14 down, eased over six seconds. */
    private fun drift() {
        val dx = Ui.px(24f * (Random.nextFloat() * 2 - 1))
        val dy = Ui.px(14f * (Random.nextFloat() * 2 - 1))
        for ((v, sign) in listOf(time to 1f, caption to -1f)) {
            if (v.isHardwareAccelerated) v.animate().translationX(dx).translationY(sign * dy).setDuration(6000).start()
            else { v.translationX = dx; v.translationY = sign * dy }
        }
    }

    private fun fail() {
        handler.removeCallbacks(stalled)
        failures++
        if (failures >= 3) showClock() else play()
    }


    fun release() {
        // The MV's surface goes with the saver: the jukebox page (or the next saver) takes it back
        // and MusicPlayback rebuilds the decoder behind the new one.
        if (mvLive) {
            mvLive = false
            Log.i("AerialLite", "saver mv off: saver closing")
        }
        MusicPlayback.setSurface(null, MusicPlayback.SURFACE_SAVER)
        handler.removeCallbacksAndMessages(null)
        video?.stopPlayback()
        mvBackdrop.setImageBitmap(null)
        loader.shutdownNow()
        kenBurns?.cancel()
        for (f in frames) f?.animate()?.cancel()
    }

    /** Just enough shade under the words to read them over bright sky or snow. */
    private class Shade(context: Context) : View(context) {
        private val top = Paint()
        private val bottom = Paint()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            top.shader = LinearGradient(0f, 0f, 0f, h * 0.18f, 0x4D000000, 0, Shader.TileMode.CLAMP)
            bottom.shader = LinearGradient(0f, h * 0.7f, 0f, h.toFloat(), 0, 0x73000000, Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height * 0.18f, top)
            canvas.drawRect(0f, height * 0.7f, width.toFloat(), height.toFloat(), bottom)
        }
    }

    private companion object {
        const val STALL_MS = 20_000L
        const val PHOTO_MS = 18_000L
        /** The lyric's bottom-left box: 45% of the 960 wide canvas, one line tall, 52 off the bottom. */
        const val LYRIC_WIDTH = 432f
        const val LYRIC_HEIGHT = 34f
        const val LYRIC_BOTTOM = 52f
        /** What the clock keeps clear above the lyric. */
        const val LYRIC_GAP = 24f
        /** ClockView's own measured height in large mode. */
        const val CLOCK_HEIGHT = 200f
    }
}
