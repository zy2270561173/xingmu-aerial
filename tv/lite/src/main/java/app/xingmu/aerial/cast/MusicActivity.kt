package app.xingmu.aerial.cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.xingmu.aerial.Ui
import app.xingmu.aerial.Ui.textPx
import app.xingmu.aerial.Wallpapers
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 内置点歌台: a record player for the living room. A phone queues a song through the backend, the
 * agent's socket delivers it and this page shows the disc, the synced lyric and the queue. Plain
 * Views only (Compose needs Android 5.0) and ijkplayer for the audio, like [IjkCastPlayerActivity].
 */
class MusicActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout

    private lateinit var disc: DiscView
    private lateinit var titleView: TextView
    private lateinit var singerView: TextView
    private lateinit var albumView: TextView
    private lateinit var qualityBadge: TextView
    private lateinit var timeView: TextView
    private lateinit var statusView: TextView
    private lateinit var lyricBox: LinearLayout
    private lateinit var lyricScroll: ScrollView
    private lateinit var queueBox: LinearLayout
    private lateinit var nextView: TextView
    private lateinit var playButton: PillButton
    private lateinit var qualityButton: PillButton
    private lateinit var qrView: ImageView
    private lateinit var qrLabel: TextView
    private lateinit var backdrop: BackdropView

    @Volatile private var playing = false
    @Volatile private var currentMid = ""
    @Volatile private var currentQuality = ""
    /** The URL the current QR encodes; empty until one is generated. */
    private var qrUrl = ""
    private var qrCheckAt = 0L
    private var sheet: View? = null
    private val work = Executors.newSingleThreadExecutor()
    private val musicListener: (MusicSnapshot) -> Unit = { snapshot -> render(snapshot) }

    private val lyrics = ArrayList<LyricLine>()
    private val lyricViews = ArrayList<TextView>()
    private var lyricIndex = -2

    private val ticker = object : Runnable {
        override fun run() {
            if (playing) disc.rotateBy(0.8f)
            val position = MusicPlayback.positionMs()
            syncLyric(position)
            // The signed URL arrives right after auth; refresh the QR once it lands.
            val now = System.currentTimeMillis()
            if (now - qrCheckAt >= 2000L) {
                qrCheckAt = now
                updateQr()
            }
            val duration = MusicPlayback.durationMs()
            timeView.text = format(position) + " / " + format(if (duration > 0) duration else fallbackDuration)
            handler.postDelayed(this, 100L)
        }
    }

    /** The song's listed length, shown until the decoder reports the real one. */
    private var fallbackDuration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CastConfig.init(this)
        Ui.init(this)
        MusicPlayback.attach(this)
        Wallpapers.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = FrameLayout(this)
        buildUi()
        render(MusicPlayback.snapshot())
        updateQr()
        loadWallpaper()
    }

    override fun onStart() {
        super.onStart()
        MusicPlayback.addListener(musicListener)
        render(MusicPlayback.snapshot())
        updateQr()
        startTicker()
    }

    override fun onStop() {
        super.onStop()
        // Playback belongs to MusicPlayback/the service: leaving this page (or going to the
        // screensaver) must not pause it.
        MusicPlayback.removeListener(musicListener)
        stopTicker()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        MusicPlayback.removeListener(musicListener)
        work.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        render(MusicPlayback.snapshot())
    }

    override fun onBackPressed() {
        val overlay = sheet
        if (overlay != null) {
            closeSheet()
            return
        }
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { MusicPlayback.toggle(); true }
        KeyEvent.KEYCODE_MEDIA_NEXT -> { MusicPlayback.next(); true }
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { MusicPlayback.previous(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    // ---- layout ----

    private fun buildUi() {
        setContentView(root)
        root.setBackgroundColor(0xFF07070A.toInt())
        backdrop = BackdropView(this)
        root.addView(backdrop, frame(0f, 0f, 960f, 540f))

        disc = DiscView(this)
        place(disc, 26f, 110f, 334f, 334f)

        titleView = label(27f, 600, Ui.WHITE).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        place(titleView, 380f, 40f, 382f, 38f)

        singerView = label(17f, 500, 0xCCEBEBF5.toInt())
        place(singerView, 380f, 82f, 382f, 24f)

        albumView = label(13f, 500, 0x88EBEBF5.toInt())
        place(albumView, 380f, 108f, 382f, 20f)

        qualityBadge = label(12f, 600, Ui.WHITE).apply {
            gravity = Gravity.CENTER
            setBackgroundDrawable(rounded(0x527D6BFF, 0x667D6BFF, 9f))
        }
        place(qualityBadge, 380f, 134f, 118f, 26f)

        lyricScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        lyricBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lyricScroll.addView(lyricBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        place(lyricScroll, 380f, 176f, 382f, 234f)
        addLyricTop()

        timeView = label(11f, 500, 0x66EBEBF5.toInt())
        place(timeView, 380f, 414f, 200f, 18f)

        statusView = label(11f, 500, 0x80EBEBF5.toInt())
        place(statusView, 380f, 488f, 400f, 20f)

        val prev = PillButton(this, "上一首")
        place(prev, 380f, 438f, 66f, 46f)
        prev.setOnClickListener { MusicPlayback.previous() }

        playButton = PillButton(this, "播放")
        place(playButton, 454f, 438f, 100f, 46f)
        playButton.setOnClickListener { MusicPlayback.toggle() }

        val next = PillButton(this, "下一首")
        place(next, 562f, 438f, 66f, 46f)
        next.setOnClickListener { MusicPlayback.next() }

        qualityButton = PillButton(this, "音质")
        place(qualityButton, 636f, 438f, 78f, 46f)
        qualityButton.setOnClickListener { showQualitySheet() }

        val back = PillButton(this, "返回")
        place(back, 722f, 438f, 58f, 46f)
        back.setOnClickListener { finish() }

        // Explicit D-pad order: geometry alone puts the sheet and the disc into the search too.
        prev.id = ID_PREV
        playButton.id = ID_PLAY
        next.id = ID_NEXT
        qualityButton.id = ID_QUALITY
        back.id = ID_BACK
        prev.nextFocusRightId = playButton.id
        playButton.nextFocusRightId = next.id
        next.nextFocusRightId = qualityButton.id
        qualityButton.nextFocusRightId = back.id
        back.nextFocusRightId = prev.id
        back.nextFocusLeftId = qualityButton.id
        qualityButton.nextFocusLeftId = next.id
        next.nextFocusLeftId = playButton.id
        playButton.nextFocusLeftId = prev.id
        prev.nextFocusLeftId = back.id
        for (button in listOf(prev, playButton, next, qualityButton, back)) button.nextFocusUpId = playButton.id

        val hint = label(11f, 500, 0x4DEBEBF5)
        hint.text = "方向键选择 · OK 确认 · 返回键离开"
        hint.gravity = Gravity.CENTER_HORIZONTAL
        place(hint, 380f, 510f, 382f, 18f)

        // The right column: the phone's QR, then what the guests have queued.
        val qrFrame = FrameLayout(this).apply { setBackgroundDrawable(rounded(0xFFFFFFFF.toInt(), 0, 12f)) }
        place(qrFrame, 790f, 34f, 140f, 140f)
        qrView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        qrFrame.addView(qrView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        qrLabel = label(12f, 500, 0xCCEBEBF5.toInt()).apply { gravity = Gravity.CENTER }
        qrLabel.text = "手机扫码点歌"
        place(qrLabel, 780f, 178f, 160f, 20f)

        val queueTitle = label(13f, 600, 0x99EBEBF5.toInt())
        queueTitle.text = "已点歌单"
        place(queueTitle, 780f, 208f, 160f, 22f)

        queueBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        place(queueBox, 780f, 232f, 166f, 150f)

        nextView = label(11f, 500, 0x80EBEBF5.toInt()).apply {
            maxLines = 2
            setLineSpacing(Ui.px(3f), 1f)
        }
        place(nextView, 780f, 388f, 160f, 44f)

        root.post { playButton.requestFocus() }
    }

    private fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        root.addView(view, frame(x, y, w, h))
    }

    private fun frame(x: Float, y: Float, w: Float, h: Float): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(Ui.pxi(w), Ui.pxi(h)).apply {
            leftMargin = Ui.pxi(x)
            topMargin = Ui.pxi(y)
        }

    private fun label(size: Float, weight: Int, color: Int): TextView = TextView(this).apply {
        textPx(size)
        typeface = Ui.font(weight)
        setTextColor(color)
        includeFontPadding = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float, strokeWidth: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Ui.px(radius)
            setColor(fill)
            if (stroke != 0) setStroke(Ui.pxi(strokeWidth).coerceAtLeast(1), stroke)
        }

    private fun addLyricTop() {
        lyricBox.addView(View(this), LinearLayout.LayoutParams(1, Ui.pxi(100f)))
    }

    private fun addLyricBottom() {
        lyricBox.addView(View(this), LinearLayout.LayoutParams(1, Ui.pxi(100f)))
    }

    private class PillButton(context: Context, text: String) : TextView(context) {
        init {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(14f))
            typeface = Ui.font(600)
            setTextColor(Ui.WHITE)
            includeFontPadding = false
            isFocusable = true
            isClickable = true
            setBackgroundDrawable(buttonBackground(false))
            setOnFocusChangeListener { view, focused ->
                val button = view as TextView
                button.setTextColor(if (focused) Ui.ON_FOCUS else Ui.WHITE)
                button.setBackgroundDrawable(buttonBackground(focused))
                button.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f)
                    .setDuration(120L).start()
            }
        }

        private fun buttonBackground(focused: Boolean): GradientDrawable =
            if (focused) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = Ui.px(23f)
                    setColor(0xFFF5F5F7.toInt())
                }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = Ui.px(23f)
                    setColor(0x24FFFFFF)
                    setStroke(Ui.pxi(1f).coerceAtLeast(1), 0x33FFFFFF)
                }
            }
    }

    // ---- playback ----

    /** Paints one state of the service-owned player; the player itself never lives here. */
    private fun render(snapshot: MusicSnapshot) {
        playing = snapshot.playing
        val song = snapshot.song
        if (song == null) {
            if (currentMid.isNotEmpty() || titleView.text.isEmpty()) {
                titleView.text = "暂无点歌"
                singerView.text = "请扫描右侧二维码进行点歌吧"
                albumView.text = ""
                qualityBadge.text = "空闲"
                timeView.text = ""
            }
            currentMid = ""
            fallbackDuration = 0L
            refreshQueue(emptyList())
            updatePlayButton()
            statusView.text = snapshot.status
            return
        }
        showSong(song)
        fallbackDuration = song.interval * 1000L
        refreshQueue(snapshot.upcoming)
        updatePlayButton()
        statusView.text = snapshot.status
        if (snapshot.durationMs <= 0L && fallbackDuration > 0) {
            timeView.text = format(snapshot.positionMs) + " / " + format(fallbackDuration)
        }
    }

    /** Paints the song's metadata; loading its lyric and cover happens once per song. */
    private fun showSong(song: MusicSong) {
        titleView.text = song.name
        singerView.text = song.singer.ifEmpty { "未知歌手" }
        albumView.text = if (song.album.isEmpty()) "内置点歌台" else song.album
        currentQuality = song.quality
        updateQualityBadge(song.quality)
        if (song.mid == currentMid) return
        currentMid = song.mid
        disc.setCover(null)
        loadCover(song)
        loadLyrics(song)
    }

    private fun loadCover(song: MusicSong) {
        MusicApi.cover(song.cover) { bitmap, error ->
            if (bitmap != null && MusicQueue.current()?.mid == song.mid) {
                disc.setCover(bitmap)
            } else if (bitmap != null) {
                bitmap.recycle()
            }
            if (error != null) Log.i(TAG, "cover: $error")
        }
    }

    private fun loadLyrics(song: MusicSong) {
        lyrics.clear()
        lyricViews.clear()
        lyricIndex = -2
        lyricBox.removeAllViews()
        addLyricTop()
        lyricScroll.scrollTo(0, 0)
        val loading = label(15f, 500, 0x66EBEBF5.toInt()).apply {
            text = "正在加载歌词…"
            gravity = Gravity.CENTER
        }
        lyricBox.addView(loading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(30f)))
        MusicApi.lyric(song.mid) { lines, error ->
            if (MusicQueue.current()?.mid != song.mid) return@lyric
            renderLyrics(lines, error)
        }
    }

    private fun renderLyrics(lines: List<LyricLine>, error: String?) {
        lyrics.clear()
        lyrics.addAll(lines)
        lyricViews.clear()
        lyricBox.removeAllViews()
        addLyricTop()
        lyricIndex = -2
        if (lyrics.isEmpty()) {
            val empty = label(14f, 500, 0x66EBEBF5.toInt()).apply {
                text = if (error != null) "歌词加载失败" else "暂无歌词"
                gravity = Gravity.CENTER
            }
            lyricBox.addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(30f)))
            addLyricBottom()
            return
        }
        for (line in lyrics) {
            val view = label(15f, 500, 0x66EBEBF5.toInt()).apply {
                text = line.text
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                setPadding(0, Ui.pxi(7f), 0, Ui.pxi(7f))
            }
            lyricBox.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            lyricViews.add(view)
        }
        addLyricBottom()
    }

    private fun syncLyric(positionMs: Long) {
        if (lyrics.isEmpty()) return
        var active = -1
        for (index in lyrics.indices) {
            if (lyrics[index].timeMs <= positionMs + 120L) active = index else break
        }
        if (active == lyricIndex) return
        if (lyricIndex in lyricViews.indices) styleLyric(lyricViews[lyricIndex], false)
        lyricIndex = active
        if (active in lyricViews.indices) {
            val view = lyricViews[active]
            styleLyric(view, true)
            val target = view.top + view.height / 2 - lyricScroll.height / 2
            lyricScroll.smoothScrollTo(0, max(0, target))
        }
    }

    private fun styleLyric(view: TextView, active: Boolean) {
        view.setTextColor(if (active) Ui.WHITE else 0x66EBEBF5.toInt())
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(if (active) 20f else 15f))
        view.typeface = Ui.font(if (active) 600 else 500)
        view.alpha = if (active) 1f else 0.72f
    }

    private fun updatePlayButton() {
        playButton.text = if (playing) "暂停" else "播放"
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    /** The same wallpaper Home bakes, dimmed behind the record player, so the page belongs to Aerial. */
    private fun loadWallpaper() {
        work.execute {
            val home = runCatching { Wallpapers.load() }.getOrNull()?.home ?: return@execute
            handler.post {
                if (!isFinishing) backdrop.setWallpaper(home)
            }
        }
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    // ---- queue and quality ----

    private fun refreshQueue(upcoming: List<MusicSong>) {
        queueBox.removeAllViews()
        if (upcoming.isEmpty()) {
            val empty = label(12f, 500, 0x4DEBEBF5).apply { text = "暂无已点歌曲" }
            queueBox.addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(24f)))
        } else {
            for ((index, song) in upcoming.take(6).withIndex()) {
                val row = label(12f, 500, 0xCCEBEBF5.toInt()).apply {
                    text = (index + 1).toString() + "  " + song.label
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                queueBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(23f)))
            }
        }
        val next = upcoming.firstOrNull()
        nextView.text = if (next == null) "下一首：暂无" else "下一首：\n" + next.label
    }

    private fun showQualitySheet() {
        if (sheet != null) return
        val song = MusicPlayback.snapshot().song ?: return
        setStatus("正在读取音质…")
        MusicApi.qualities(song.mid, song.quality) { list, error ->
            setStatus("")
            if (error != null) {
                setStatus("音质列表失败：" + error)
                return@qualities
            }
            openQualitySheet(song, list)
        }
    }

    private fun openQualitySheet(song: MusicSong, list: List<MusicQuality>) {
        if (sheet != null) return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xC0000000.toInt())
            isClickable = true
            setOnClickListener { closeSheet() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundDrawable(rounded(0xF2232328.toInt(), 0x33FFFFFF, 18f))
            setPadding(Ui.pxi(22f), Ui.pxi(18f), Ui.pxi(22f), Ui.pxi(16f))
            isClickable = true
        }
        val heading = label(17f, 600, Ui.WHITE).apply { text = "选择音质" }
        panel.addView(heading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(30f)))
        val subtitle = label(11f, 500, 0x66EBEBF5.toInt()).apply { text = song.label }
        panel.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(22f)))

        var selected: TextView? = null
        for (quality in list) {
            val row = TextView(this).apply {
                text = (if (quality.key == song.quality) "✓  " else "     ") + quality.label
                textPx(15f)
                typeface = Ui.font(if (quality.key == song.quality) 600 else 500)
                setTextColor(if (quality.key == song.quality) 0xFFCFC7FF.toInt() else 0xCCEBEBF5.toInt())
                includeFontPadding = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(Ui.pxi(12f), 0, Ui.pxi(12f), 0)
                isFocusable = true
                isClickable = true
                setBackgroundDrawable(rounded(0x00FFFFFF, 0, 12f))
                setOnFocusChangeListener { view, focused ->
                    val text = view as TextView
                    text.setTextColor(if (focused) Ui.ON_FOCUS else if (quality.key == song.quality) 0xFFCFC7FF.toInt() else 0xCCEBEBF5.toInt())
                    text.setBackgroundDrawable(if (focused) rounded(0xFFF5F5F7.toInt(), 0, 12f) else rounded(0x00FFFFFF, 0, 12f))
                }
                setOnClickListener { chooseQuality(quality.key) }
            }
            panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(44f)))
            if (quality.key == song.quality) selected = row
        }
        val cancel = TextView(this).apply {
            text = "取消"
            textPx(14f)
            typeface = Ui.font(600)
            setTextColor(0x99EBEBF5.toInt())
            includeFontPadding = false
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundDrawable(rounded(0x24FFFFFF, 0x33FFFFFF, 22f))
            setOnFocusChangeListener { view, focused ->
                val text = view as TextView
                text.setTextColor(if (focused) Ui.ON_FOCUS else 0x99EBEBF5.toInt())
                text.setBackgroundDrawable(if (focused) rounded(0xFFF5F5F7.toInt(), 0, 22f) else rounded(0x24FFFFFF, 0x33FFFFFF, 22f))
            }
            setOnClickListener { closeSheet() }
        }
        panel.addView(cancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(44f)).apply { topMargin = Ui.pxi(10f) })

        overlay.addView(panel, FrameLayout.LayoutParams(Ui.pxi(330f), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, frame(0f, 0f, 960f, 540f))
        sheet = overlay
        (selected ?: cancel).requestFocus()
    }

    private fun closeSheet() {
        val overlay = sheet ?: return
        sheet = null
        root.removeView(overlay)
        playButton.requestFocus()
    }

    private fun chooseQuality(quality: String) {
        closeSheet()
        val song = MusicPlayback.snapshot().song ?: return
        if (song.quality == quality) return
        updateQualityBadge(quality)
        setStatus("正在切换音质…")
        MusicPlayback.changeQuality(quality)
    }

    private fun updateQualityBadge(quality: String) {
        qualityBadge.text = qualityShort(quality)
    }

    private fun qualityShort(key: String): String = when {
        key.startsWith("MP3_") -> "MP3 " + key.removePrefix("MP3_") + "K"
        key.startsWith("ACC_") -> "AAC " + key.removePrefix("ACC_") + "K"
        key.startsWith("OGG_") -> "OGG " + key.removePrefix("OGG_") + "K"
        key == "FLAC" -> "FLAC 无损"
        key == "MASTER" -> "母带"
        else -> key
    }

    // ---- QR ----

    private fun updateQr() {
        val base = CastConfig.backendUrl
        val device = CastConfig.deviceId
        // Prefer the signed URL the backend pushed: the bare path is refused with 403 now.
        val signed = CastAgent.musicPageUrl
        val url = when {
            signed.isNotEmpty() -> signed
            base.isNotEmpty() && device.isNotEmpty() -> base + "/music/" + device
            else -> {
                if (qrUrl.isNotEmpty()) {
                    qrView.setImageBitmap(null)
                    qrLabel.text = "未连接后端"
                    qrUrl = ""
                }
                return
            }
        }
        if (url == qrUrl) return
        if (signed.isEmpty()) {
            Log.w(TAG, "未收到签名 URL，二维码退回未签名地址（手机打开会 403）")
        }
        qrUrl = url
        handler.post {
            val bitmap = runCatching { qrBitmap(url) }.getOrNull()
            if (bitmap != null) {
                qrView.setImageBitmap(bitmap)
                qrLabel.text = "手机扫码点歌"
            } else {
                qrLabel.text = "二维码生成失败"
            }
        }
    }

    private fun qrBitmap(content: String): Bitmap {
        val pixels = Ui.pxi(272f).coerceIn(160, 512)
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        hints[EncodeHintType.MARGIN] = 1
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK }
        for (x in 0 until pixels) {
            for (y in 0 until pixels) {
                if (matrix[x, y]) canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
            }
        }
        return bitmap
    }

    private fun format(ms: Long): String {
        val total = max(0L, ms) / 1000L
        return String.format(Locale.US, "%02d:%02d", total / 60L, total % 60L)
    }

    // ---- drawing ----

    /** The dark room behind the player: Aerial's own wallpaper, dimmed, plus a warm glow off the disc. */
    private class BackdropView(context: Context) : View(context) {
        private var background: Paint? = null
        private var glow: Paint? = null
        private var scrim: Paint? = null
        private var wallpaper: Bitmap? = null
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()

        fun setWallpaper(bitmap: Bitmap?) {
            wallpaper = bitmap
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            background = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    0xFF191322.toInt(), 0xFF060608.toInt(), Shader.TileMode.CLAMP)
            }
            glow = Paint().apply {
                shader = RadialGradient(w * 0.20f, h * 0.52f, w * 0.42f,
                    0x337D6BFF, 0x007D6BFF, Shader.TileMode.CLAMP)
            }
            scrim = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    0x6607070A, 0xD907070A.toInt(), Shader.TileMode.CLAMP)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val picture = wallpaper
            if (picture != null) {
                rect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(picture, null, rect, bitmapPaint)
                scrim?.let { canvas.drawRect(rect, it) }
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background ?: Paint().apply { color = 0xFF08080C.toInt() })
            }
            glow?.let { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it) }
        }
    }

    /** The record: black vinyl, the album cover as its label, rotating only while it plays. */
    private class DiscView(context: Context) : View(context) {
        private var cover: Bitmap? = null
        private var shader: BitmapShader? = null
        private var angle = 0f
        private val vinyl = Paint(Paint.ANTI_ALIAS_FLAG)
        private val groove = Paint(Paint.ANTI_ALIAS_FLAG)
        private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hole = Paint(Paint.ANTI_ALIAS_FLAG)
        private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
        private val note = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setCover(bitmap: Bitmap?) {
            cover = bitmap
            shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            coverPaint.shader = shader
            updateMatrix()
            invalidate()
        }

        fun rotateBy(degrees: Float) {
            angle = (angle + degrees) % 360f
            invalidate()
        }

        /** The cover fills the label circle: its shader is scaled and centred on the disc. */
        private fun updateMatrix() {
            val bitmap = cover ?: return
            if (width == 0 || height == 0) return
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - Ui.px(4f)
            val coverRadius = radius * 0.63f
            val scale = (coverRadius * 2f) / min(bitmap.width, bitmap.height).coerceAtLeast(1)
            shader?.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - bitmap.width * scale / 2f, cy - bitmap.height * scale / 2f)
            })
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) / 2f - Ui.px(4f)
            vinyl.shader = RadialGradient(cx, cy, r, intArrayOf(0xFF2B2B30.toInt(), 0xFF0A0A0C.toInt()), floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP)
            groove.style = Paint.Style.STROKE
            groove.strokeWidth = Ui.px(0.7f)
            groove.color = 0x16FFFFFF
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = Ui.px(1.4f)
            ring.color = 0x26FFFFFF
            hole.color = 0xFF07070A.toInt()
            placeholder.color = 0xFF34303F.toInt()
            note.color = 0x99FFFFFF.toInt()
            note.textAlign = Paint.Align.CENTER
            note.typeface = Typeface.DEFAULT_BOLD
            shadow.color = 0x44000000
            updateMatrix()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f - Ui.px(4f)
            val coverRadius = r * 0.63f
            canvas.drawCircle(cx, cy + Ui.px(8f), r, shadow)
            canvas.drawCircle(cx, cy, r, vinyl)
            for (i in 0..7) canvas.drawCircle(cx, cy, r * (0.79f + 0.026f * i), groove)
            canvas.drawCircle(cx, cy, r, ring)

            canvas.save()
            canvas.rotate(angle, cx, cy)
            val bitmap = cover
            if (bitmap != null && shader != null) {
                canvas.drawCircle(cx, cy, coverRadius, coverPaint)
                canvas.drawCircle(cx, cy, coverRadius, ring)
            } else if (bitmap == null) {
                canvas.drawCircle(cx, cy, coverRadius, placeholder)
                note.textSize = coverRadius * 1.15f
                canvas.drawText("♪", cx, cy + coverRadius * 0.38f, note)
            }
            canvas.drawCircle(cx, cy, coverRadius * 0.055f, hole)
            canvas.restore()
        }

        override fun onDetachedFromWindow() {
            setCover(null)
            super.onDetachedFromWindow()
        }
    }

    companion object {
        private const val TAG = "AerialMusic"
        private const val ID_PREV = 1001
        private const val ID_PLAY = 1002
        private const val ID_NEXT = 1003
        private const val ID_QUALITY = 1004
        private const val ID_BACK = 1005

        /** Opens the jukebox from Home or from an agent command (a worker thread may call this). */
        fun start(context: Context) {
            val intent = Intent(context, MusicActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "could not open the jukebox: " + it.javaClass.simpleName) }
        }
    }
}
