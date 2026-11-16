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
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
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
    private lateinit var mvView: TextureView
    private lateinit var mvBadge: TextView
    private lateinit var mvTitleView: TextView
    private lateinit var controlsRow: LinearLayout
    private lateinit var mvTopScrim: View
    private lateinit var mvBottomScrim: View
    private lateinit var mvBiliButton: PillButton
    private lateinit var qrFrame: FrameLayout
    private lateinit var queueTitle: TextView
    private lateinit var hintView: TextView
    /** The bottom bar's buttons, in D-pad order, for the focus check while a MV is full-screen. */
    private var barButtons: List<PillButton> = emptyList()
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
    private lateinit var mvButton: PillButton
    private lateinit var sourceButton: PillButton
    /** 返回 is the bar's last button in audio mode; with the B站 list on the bar it comes after. */
    private lateinit var backButton: PillButton
    private lateinit var qrView: ImageView
    private lateinit var qrLabel: TextView
    private lateinit var backdrop: BackdropView

    /** A MV owns the screen: the picture is full-screen and the controls fade out. */
    private var mvMode = false
    /** The bottom controls are on screen right now. */
    private var controlsVisible = false
    /** The video's own pixel size, for the fill-the-screen crop; 0 until the decoder reports it. */
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastTimeLabel = ""
    private var lastStatusLine = ""

    /** The MV bar's idle timer: a visible bar tucks itself away again after a few quiet seconds. */
    private val hideControlsTask = Runnable { hideMvControls() }

    @Volatile private var playing = false
    @Volatile private var currentMid = ""
    @Volatile private var currentQuality = ""
    /** The URL the current QR encodes; empty until one is generated. */
    private var qrUrl = ""
    private var qrCheckAt = 0L
    private var sheet: View? = null
    /**
     * The B站 result picker; [biliSheetAuto] means the three second countdown opened it and may
     * still pick the first hit for the viewer. Any key, tap or Back turns that off (see
     * [cancelBiliAutoPlay]) and the sheet stays up until the viewer presses OK.
     */
    private var biliSheet: View? = null
    private var biliSheetAuto = false
    private var biliSheetHint: TextView? = null
    private var biliSheetHeading: TextView? = null
    /** The search a sheet was already opened for, so closing it is not undone by the next tick. */
    private var shownBiliToken = -1
    /** The lyric list is INVISIBLE (not gone) while a MV plays: it keeps its scroll and highlight. */
    private var lyricsHidden = false
    private val work = Executors.newSingleThreadExecutor()
    private val musicListener: (MusicSnapshot) -> Unit = { snapshot -> render(snapshot) }

    private val lyrics = ArrayList<LyricLine>()
    private val lyricViews = ArrayList<TextView>()
    private var lyricIndex = -2

    private val ticker = object : Runnable {
        override fun run() {
            // The record only spins in audio mode: behind a full-screen MV it is not on screen.
            if (playing && !mvMode) disc.rotateBy(0.8f)
            val position = MusicPlayback.positionMs()
            syncLyric(position)
            // The signed URL arrives right after auth; refresh the QR once it lands.
            val now = System.currentTimeMillis()
            if (now - qrCheckAt >= 2000L && !mvMode) {
                qrCheckAt = now
                updateQr()
            }
            val duration = MusicPlayback.durationMs()
            val label = format(position) + " / " + format(if (duration > 0) duration else fallbackDuration)
            // Ten ticks a second must not relayout a TextView whose text did not change.
            if (label != lastTimeLabel) {
                lastTimeLabel = label
                timeView.text = label
            }
            // The status line carries a notice with a deadline; poll it so it really goes away.
            val line = MusicPlayback.statusLine()
            if (line != lastStatusLine) {
                lastStatusLine = line
                statusView.text = line
            }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A freshly opened page (or one coming back from the screensaver) can end up with no
        // focused view at all, and then the first D-pad press goes nowhere.
        if (hasFocus && currentFocus == null && !isFinishing) {
            if (mvMode) showMvControls() else playButton.requestFocus()
        }
    }

    override fun onBackPressed() {
        // The B站 list: Back is the viewer's own answer, so the countdown must not fire once it is
        // gone (closeBiliSheet cancels it) and whatever plays now keeps playing.
        if (biliSheet != null) {
            closeBiliSheet()
            return
        }
        val overlay = sheet
        if (overlay != null) {
            closeSheet()
            return
        }
        // Full-screen MV with the bar up: the first Back only tucks the bar away. With the bar
        // hidden Back leaves the page exactly as it always did.
        if (mvMode && controlsVisible) {
            hideMvControls()
            return
        }
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any key while the countdown is still armed is the viewer choosing a MV themselves: the
        // countdown is stopped here, before the D-pad moves the focus, so the first hit can never
        // start under a focus the viewer is already moving.
        if (biliSheet != null && biliSheetAuto && event.action == KeyEvent.ACTION_DOWN) cancelBiliAutoPlay()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A tap anywhere on the sheet (a row, the buttons, the backdrop) is the same answer.
        if (biliSheet != null && biliSheetAuto && ev.actionMasked == MotionEvent.ACTION_DOWN) cancelBiliAutoPlay()
        if (mvMode) {
            if (!controlsVisible && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                showMvControls()
                return true
            }
            if (controlsVisible) touchMvControls()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                MusicPlayback.toggle()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                MusicPlayback.next()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                MusicPlayback.previous()
                return true
            }
        }
        if (mvMode) {
            if (controlsVisible) {
                // Any key is an action: the bar's idle five seconds start over.
                touchMvControls()
            } else if (isWakeKey(keyCode)) {
                // The key that brings the bar back only brings it back; it does not also move focus.
                showMvControls()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** The remote keys that bring a hidden MV control bar back. */
    private fun isWakeKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_TAB,
        -> true
        else -> false
    }

    // ---- layout ----

    private fun buildUi() {
        setContentView(root)
        root.setBackgroundColor(0xFF07070A.toInt())
        backdrop = BackdropView(this)
        root.addView(backdrop, frame(0f, 0f, 960f, 540f))

        disc = DiscView(this)
        place(disc, 26f, 110f, 334f, 334f)

        // The MV owns the whole screen, so the video target covers the page and stays attached at
        // alpha 0 in audio mode (the record shows through). A TextureView (not SurfaceView)
        // because the surface has to exist before the player is built: ijk's MediaCodec wants its
        // output surface at configure time on this 4.4 box. Its transform crops the picture to fill
        // the screen (see [applyVideoFill]).
        mvView = TextureView(this).apply {
            alpha = 0f
            isClickable = false
            isFocusable = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    MusicPlayback.setSurface(Surface(texture))
                }

                override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    applyVideoFill()
                }

                override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                    // Back or the screensaver: the video keeps decoding into nothing, the audio goes on.
                    MusicPlayback.setSurface(null)
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
            }
        }
        place(mvView, 0f, 0f, 960f, 540f)
        mvView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoFill() }

        // The MV's overlays: a top gradient behind the title/badge and a bottom one behind the bar.
        mvTopScrim = View(this).apply {
            setBackgroundDrawable(scrim(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000)))
            visibility = View.GONE
        }
        place(mvTopScrim, 0f, 0f, 960f, 200f)
        mvBottomScrim = View(this).apply {
            setBackgroundDrawable(scrim(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE0000000.toInt(), 0x00000000)))
            visibility = View.GONE
        }
        place(mvBottomScrim, 0f, 420f, 960f, 120f)

        // Only on screen while a MV plays, floating over the picture's top-left corner.
        mvBadge = label(11f, 600, Ui.WHITE).apply {
            gravity = Gravity.CENTER
            text = "MV·QM"
            setBackgroundDrawable(rounded(0xCC6D5BFF.toInt(), 0x66FFFFFF, 9f))
            visibility = View.GONE
        }
        place(mvBadge, 48f, 108f, 86f, 22f)

        mvTitleView = label(11f, 500, 0xCCEBEBF5.toInt()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        place(mvTitleView, 144f, 108f, 700f, 22f)

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
        prev.setOnClickListener { MusicPlayback.previous() }

        playButton = PillButton(this, "播放")
        playButton.setOnClickListener { MusicPlayback.toggle() }

        val next = PillButton(this, "下一首")
        next.setOnClickListener { MusicPlayback.next() }

        qualityButton = PillButton(this, "音质")
        qualityButton.setOnClickListener { showQualitySheet() }

        mvButton = PillButton(this, "MV 开")
        mvButton.setOnClickListener { MusicPlayback.setMvEnabled(!MusicPlayback.mvEnabled()) }

        sourceButton = PillButton(this, "源 QM")
        sourceButton.setOnClickListener {
            val nextSource = if (MusicPlayback.mvSource() == CastConfig.MV_SOURCE_BILI) {
                CastConfig.MV_SOURCE_QM
            } else {
                CastConfig.MV_SOURCE_BILI
            }
            MusicPlayback.setMvSource(nextSource)
        }

        val back = PillButton(this, "返回")
        back.setOnClickListener { finish() }
        backButton = back

        // 播放中换 B站 MV：结果列表的第二个入口，只存在于 MV 全屏的那条控件里。
        mvBiliButton = PillButton(this, "B站列表")
        mvBiliButton.visibility = View.GONE
        mvBiliButton.setOnClickListener { openBiliSheet(MusicPlayback.snapshot(), auto = false) }

        // One row serves both modes: placed at (380,438) with its fixed widths it is exactly the
        // record player's old bar; in MV mode the row spans the page and centres the buttons at the
        // bottom of the full-screen picture (and fades out with them).
        controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        barButtons = listOf(prev, playButton, next, qualityButton, mvButton, sourceButton, back, mvBiliButton)
        val barWidths = floatArrayOf(58f, 84f, 58f, 58f, 64f, 74f, 40f, 82f)
        for ((index, button) in barButtons.withIndex()) {
            controlsRow.addView(
                button,
                LinearLayout.LayoutParams(Ui.pxi(barWidths[index]), Ui.pxi(46f)).apply {
                    if (index > 0) leftMargin = Ui.pxi(6f)
                },
            )
        }
        place(controlsRow, 380f, 438f, 472f, 46f)

        // Explicit D-pad order: geometry alone puts the sheet and the disc into the search too.
        prev.id = ID_PREV
        playButton.id = ID_PLAY
        next.id = ID_NEXT
        qualityButton.id = ID_QUALITY
        mvButton.id = ID_MV
        sourceButton.id = ID_SOURCE
        back.id = ID_BACK
        mvBiliButton.id = ID_BILI_LIST
        prev.nextFocusRightId = playButton.id
        playButton.nextFocusRightId = next.id
        next.nextFocusRightId = qualityButton.id
        qualityButton.nextFocusRightId = mvButton.id
        mvButton.nextFocusRightId = sourceButton.id
        sourceButton.nextFocusRightId = back.id
        back.nextFocusRightId = prev.id
        back.nextFocusLeftId = sourceButton.id
        sourceButton.nextFocusLeftId = mvButton.id
        mvButton.nextFocusLeftId = qualityButton.id
        qualityButton.nextFocusLeftId = next.id
        next.nextFocusLeftId = playButton.id
        playButton.nextFocusLeftId = prev.id
        prev.nextFocusLeftId = back.id
        for (button in barButtons) {
            button.nextFocusUpId = playButton.id
        }


        hintView = label(11f, 500, 0x4DEBEBF5)
        hintView.text = "方向键选择 · OK 确认 · 返回键离开"
        hintView.gravity = Gravity.CENTER_HORIZONTAL
        place(hintView, 380f, 510f, 382f, 18f)

        // The right column: the phone's QR, then what the guests have queued. Both step aside for
        // a full-screen MV and come back with the record player.
        qrFrame = FrameLayout(this).apply { setBackgroundDrawable(rounded(0xFFFFFFFF.toInt(), 0, 12f)) }
        place(qrFrame, 790f, 34f, 140f, 140f)
        qrView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        qrFrame.addView(qrView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        qrLabel = label(12f, 500, 0xCCEBEBF5.toInt()).apply { gravity = Gravity.CENTER }
        qrLabel.text = "手机扫码点歌"
        place(qrLabel, 780f, 178f, 160f, 20f)

        queueTitle = label(13f, 600, 0x99EBEBF5.toInt())
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

    /** Adds a view to the page the first time and only re-frames it afterwards (the MV re-layout). */
    private fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        val params = frame(x, y, w, h)
        if (view.parent == null) root.addView(view, params) else view.layoutParams = params
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

    /** A one-way fade for the MV overlays: invisible at the bottom, opaque at the other end. */
    private fun scrim(orientation: GradientDrawable.Orientation, colors: IntArray): GradientDrawable =
        GradientDrawable(orientation, colors)

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
            applyMvState(snapshot)
            updateStatus(snapshot)
            return
        }
        showSong(song)
        fallbackDuration = song.interval * 1000L
        refreshQueue(snapshot.upcoming)
        updatePlayButton()
        applyMvState(snapshot)
        updateStatus(snapshot)
        if (snapshot.durationMs <= 0L && fallbackDuration > 0) {
            timeView.text = format(snapshot.positionMs) + " / " + format(fallbackDuration)
        }
    }

    private fun updateStatus(snapshot: MusicSnapshot) {
        val line = snapshot.notice.ifEmpty { snapshot.status }
        lastStatusLine = line
        statusView.text = line
    }

    /**
     * Paints the MV-dependent parts of the page. A MV that really owns the surface switches the
     * page into its full-screen layout; anything else is the record player as it always was.
     */
    private fun applyMvState(snapshot: MusicSnapshot) {
        val mvActive = snapshot.mvActive
        val bili = snapshot.mvSource == CastConfig.MV_SOURCE_BILI
        // alpha, not GONE: the TextureView's surface must live on so the next MV has a target.
        mvView.alpha = if (mvActive) 1f else 0f
        mvBadge.text = if (bili) "MV·B站" else "MV·QM"
        mvTitleView.text = when {
            !mvActive -> ""
            bili && snapshot.mvTitle.isNotEmpty() -> snapshot.mvTitle
            bili -> "B 站 MV"
            else -> ""
        }
        mvButton.text = if (snapshot.mvEnabled) "MV 开" else "MV 关"
        sourceButton.text = if (bili) "源 B站" else "源 QM"

        // The B站 picker's second entrance rides in the MV bar; audio mode has no video to change.
        val biliListShown = mvActive && bili
        mvBiliButton.visibility = if (biliListShown) View.VISIBLE else View.GONE
        mvBiliButton.isFocusable = biliListShown
        // The D-pad chain: 返回 is the last button in audio mode, but with 「B站列表」 on the bar the
        // remote has to be able to reach it (and to wrap back to 上一首 from there).
        backButton.nextFocusRightId = if (biliListShown) ID_BILI_LIST else ID_PREV
        mvBiliButton.nextFocusRightId = ID_PREV
        mvBiliButton.nextFocusLeftId = ID_BACK

        if (snapshot.mvVideoWidth > 0 && snapshot.mvVideoHeight > 0 &&
            (snapshot.mvVideoWidth != videoWidth || snapshot.mvVideoHeight != videoHeight)
        ) {
            videoWidth = snapshot.mvVideoWidth
            videoHeight = snapshot.mvVideoHeight
            applyVideoFill()
        }
        if (mvActive != mvMode) {
            mvMode = mvActive
            applyMvLayout(mvActive)
        }

        if (mvActive && biliSheet != null && biliSheetAuto) closeBiliSheet()
        if (!bili) closeBiliSheet()
        // A new search (next song, re-search) invalidates the rows the open sheet is showing.
        if (biliSheet != null && snapshot.biliToken != shownBiliToken) closeBiliSheet()
        refreshBiliSheet(snapshot)
        maybeOpenBiliSheet(snapshot)
    }

    /**
     * Full-screen MV: the picture covers the page and only the song's name, the badge, the position
     * and a bottom control bar float over it. The record, the lyric list, the queue and the QR step
     * aside (the lyric list is INVISIBLE, not rebuilt, so audio mode gets its scroll back). Every
     * one of them is put back exactly where the record player had it when the MV ends.
     */
    private fun applyMvLayout(mv: Boolean) {
        backdrop.visibility = if (mv) View.GONE else View.VISIBLE
        root.setBackgroundColor(if (mv) 0xFF000000.toInt() else 0xFF07070A.toInt())
        disc.visibility = if (mv) View.GONE else View.VISIBLE
        mvTopScrim.visibility = if (mv) View.VISIBLE else View.GONE
        mvBottomScrim.visibility = if (mv) View.VISIBLE else View.GONE
        mvBadge.visibility = if (mv) View.VISIBLE else View.GONE
        mvTitleView.visibility = if (mv) View.VISIBLE else View.GONE
        albumView.visibility = if (mv) View.GONE else View.VISIBLE
        qualityBadge.visibility = if (mv) View.GONE else View.VISIBLE
        qrFrame.visibility = if (mv) View.GONE else View.VISIBLE
        qrLabel.visibility = if (mv) View.GONE else View.VISIBLE
        queueTitle.visibility = if (mv) View.GONE else View.VISIBLE
        queueBox.visibility = if (mv) View.GONE else View.VISIBLE
        nextView.visibility = if (mv) View.GONE else View.VISIBLE
        hintView.visibility = if (mv) View.GONE else View.VISIBLE
        lyricsHidden = mv
        lyricScroll.visibility = if (mv) View.INVISIBLE else View.VISIBLE
        if (!mv) {
            // Back on audio: jump straight to the line the song is on, no list rebuild.
            handler.post { syncLyric(MusicPlayback.positionMs(), force = true) }
        }

        if (mv) {
            place(titleView, 48f, 32f, 660f, 42f)
            place(singerView, 48f, 76f, 660f, 26f)
            place(statusView, 48f, 140f, 700f, 20f)
            // The position sits in the same top-left block: the picture's own watermark lives in
            // the top-right corner, so nothing is written over it.
            place(timeView, 48f, 168f, 320f, 22f)
            timeView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            timeView.textPx(12f)
            timeView.setTextColor(0xCCEBEBF5.toInt())
            place(controlsRow, 0f, 468f, 960f, 46f)
            for (button in barButtons) button.nextFocusUpId = button.id
            showMvControls(CONTROLS_START_MS)
        } else {
            dropMvControls()
            place(titleView, 380f, 40f, 382f, 38f)
            place(singerView, 380f, 82f, 382f, 24f)
            place(statusView, 380f, 488f, 400f, 20f)
            place(timeView, 380f, 414f, 200f, 18f)
            timeView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            timeView.textPx(11f)
            timeView.setTextColor(0x66EBEBF5.toInt())
            place(controlsRow, 380f, 438f, 472f, 46f)
            for (button in barButtons) button.nextFocusUpId = playButton.id
            controlsRow.visibility = View.VISIBLE
            controlsRow.alpha = 1f
            mvBottomScrim.alpha = 1f
        }
        applyVideoFill()
    }

    /**
     * The TextureView stretches its texture to its own bounds; scale it back so the picture fills
     * the screen at its own aspect ratio, cropping the overflow — no black bars, no squeeze. 16:9
     * is assumed until the decoder reports the real size.
     */
    private fun applyVideoFill() {
        val width = mvView.width
        val height = mvView.height
        if (width <= 0 || height <= 0) return
        val srcWidth = if (videoWidth > 0) videoWidth else 16
        val srcHeight = if (videoHeight > 0) videoHeight else 9
        val fill = max(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        val matrix = Matrix()
        matrix.setScale(fill * srcWidth / width, fill * srcHeight / height, width / 2f, height / 2f)
        runCatching { mvView.setTransform(matrix) }
    }

    /** Brings the MV bar back (and starts its idle countdown) after a key, a tap or playback. */
    private fun showMvControls(autoHideMs: Long = CONTROLS_IDLE_MS) {
        if (!mvMode) return
        handler.removeCallbacks(hideControlsTask)
        val wasHidden = !controlsVisible
        controlsVisible = true
        controlsRow.animate().cancel()
        controlsRow.visibility = View.VISIBLE
        if (wasHidden) controlsRow.alpha = 0f
        controlsRow.animate().alpha(1f).setDuration(160L).start()
        mvBottomScrim.animate().cancel()
        mvBottomScrim.animate().alpha(1f).setDuration(160L).start()
        if (wasHidden && currentFocus !is PillButton) playButton.requestFocus()
        handler.postDelayed(hideControlsTask, autoHideMs)
    }

    /**
     * Fades the MV bar out. It stays VISIBLE (at alpha 0) so the focused button keeps its focus:
     * a key press then brings the same button back instead of the D-pad starting over.
     */
    private fun hideMvControls() {
        handler.removeCallbacks(hideControlsTask)
        if (!controlsVisible) return
        controlsVisible = false
        controlsRow.animate().cancel()
        controlsRow.animate().alpha(0f).setDuration(220L).start()
        mvBottomScrim.animate().cancel()
        mvBottomScrim.animate().alpha(0f).setDuration(220L).start()
    }

    /** Any key or tap while the bar is up pushes its idle countdown back. */
    private fun touchMvControls() {
        handler.removeCallbacks(hideControlsTask)
        if (controlsVisible) handler.postDelayed(hideControlsTask, CONTROLS_IDLE_MS)
    }

    /** Leaving full-screen MV: the bar goes back to being a permanent part of the record player. */
    private fun dropMvControls() {
        handler.removeCallbacks(hideControlsTask)
        controlsVisible = false
        controlsRow.animate().cancel()
        mvBottomScrim.animate().cancel()
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

    private fun syncLyric(positionMs: Long, force: Boolean = false) {
        if (lyrics.isEmpty()) return
        var active = -1
        for (index in lyrics.indices) {
            if (lyrics[index].timeMs <= positionMs + 120L) active = index else break
        }
        if (active == lyricIndex && !force) return
        if (lyricIndex in lyricViews.indices) styleLyric(lyricViews[lyricIndex], false)
        lyricIndex = active
        if (active in lyricViews.indices) {
            val view = lyricViews[active]
            styleLyric(view, true)
            // While the MV owns the screen the list is INVISIBLE; the highlight keeps tracking and
            // the scroll catches up the moment audio mode brings it back.
            if (lyricScroll.visibility == View.VISIBLE && lyricScroll.height > 0) {
                val target = view.top + view.height / 2 - lyricScroll.height / 2
                lyricScroll.smoothScrollTo(0, max(0, target))
            }
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

    // ---- B站 MV picker ----

    /**
     * The search results for the playing song. Opened automatically while the countdown runs (so
     * the remote can pick another hit before the first one starts) and again from 「B站列表」.
     */
    private fun maybeOpenBiliSheet(snapshot: MusicSnapshot) {
        if (!snapshot.mvEnabled || snapshot.mvSource != CastConfig.MV_SOURCE_BILI) return
        if (snapshot.biliResults.isEmpty() || snapshot.biliToken <= 0) return
        // Only a live countdown opens the list by itself: a page opened again while the chosen MV
        // is already playing must not flash the old results over the picture.
        if (snapshot.biliCountdown <= 0) return
        if (snapshot.biliToken == shownBiliToken) return
        if (biliSheet != null || sheet != null || isFinishing) return
        openBiliSheet(snapshot, auto = true)
    }

    private fun openBiliSheet(snapshot: MusicSnapshot, auto: Boolean) {
        if (biliSheet != null || snapshot.biliResults.isEmpty()) return
        // Set before the hint is written: it reads differently while the countdown may still pick.
        biliSheetAuto = auto
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xC0000000.toInt())
            isClickable = true
            setOnClickListener { closeBiliSheet() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundDrawable(rounded(0xF2232328.toInt(), 0x33FFFFFF, 18f))
            setPadding(Ui.pxi(22f), Ui.pxi(16f), Ui.pxi(22f), Ui.pxi(14f))
            isClickable = true
        }
        val heading = label(17f, 600, Ui.WHITE).apply {
            text = if (auto) BILI_AUTO_TITLE else BILI_TITLE
        }
        panel.addView(heading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(30f)))
        biliSheetHeading = heading
        val hint = label(12f, 500, 0x99EBEBF5.toInt()).apply {
            text = biliHint(snapshot)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        panel.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(24f)))
        biliSheetHint = hint

        val rows = ArrayList<TextView>()
        for ((index, mv) in snapshot.biliResults.take(5).withIndex()) {
            val row = TextView(this).apply {
                text = biliRowLabel(index, mv)
                textPx(13f)
                typeface = Ui.font(if (index == snapshot.biliSelected) 600 else 500)
                setTextColor(if (index == snapshot.biliSelected) 0xFFCFC7FF.toInt() else 0xCCEBEBF5.toInt())
                includeFontPadding = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Ui.pxi(12f), 0, Ui.pxi(12f), 0)
                isFocusable = true
                isClickable = true
                setBackgroundDrawable(rounded(0x00FFFFFF, 0, 12f))
                setOnFocusChangeListener { view, focused ->
                    val text = view as TextView
                    text.setTextColor(if (focused) Ui.ON_FOCUS else if (index == snapshot.biliSelected) 0xFFCFC7FF.toInt() else 0xCCEBEBF5.toInt())
                    text.setBackgroundDrawable(if (focused) rounded(0xFFF5F5F7.toInt(), 0, 12f) else rounded(0x00FFFFFF, 0, 12f))
                }
                setOnClickListener {
                    Log.i(TAG, "bili picker: OK on result #" + (index + 1) + " " + mv.title)
                    MusicPlayback.selectBili(index)
                    closeBiliSheet()
                }
            }
            panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(48f)).apply { topMargin = Ui.pxi(3f) })
            rows.add(row)
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val research = TextView(this).apply {
            text = "重新搜索"
            textPx(13f)
            typeface = Ui.font(600)
            setTextColor(0xCCEBEBF5.toInt())
            includeFontPadding = false
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundDrawable(rounded(0x24FFFFFF, 0x33FFFFFF, 18f))
            setOnFocusChangeListener { view, focused ->
                val text = view as TextView
                text.setTextColor(if (focused) Ui.ON_FOCUS else 0xCCEBEBF5.toInt())
                text.setBackgroundDrawable(if (focused) rounded(0xFFF5F5F7.toInt(), 0, 18f) else rounded(0x24FFFFFF, 0x33FFFFFF, 18f))
            }
            setOnClickListener {
                closeBiliSheet()
                MusicPlayback.setMvSource(CastConfig.MV_SOURCE_BILI)
            }
        }
        val close = TextView(this).apply {
            text = "关闭"
            textPx(13f)
            typeface = Ui.font(600)
            setTextColor(0x99EBEBF5.toInt())
            includeFontPadding = false
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundDrawable(rounded(0x24FFFFFF, 0x33FFFFFF, 18f))
            setOnFocusChangeListener { view, focused ->
                val text = view as TextView
                text.setTextColor(if (focused) Ui.ON_FOCUS else 0x99EBEBF5.toInt())
                text.setBackgroundDrawable(if (focused) rounded(0xFFF5F5F7.toInt(), 0, 18f) else rounded(0x24FFFFFF, 0x33FFFFFF, 18f))
            }
            setOnClickListener { closeBiliSheet() }
        }
        actions.addView(research, LinearLayout.LayoutParams(0, Ui.pxi(38f), 1f).apply { rightMargin = Ui.pxi(8f) })
        actions.addView(close, LinearLayout.LayoutParams(0, Ui.pxi(38f), 1f))
        panel.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(38f)).apply { topMargin = Ui.pxi(8f) })

        overlay.addView(panel, FrameLayout.LayoutParams(Ui.pxi(430f), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, frame(0f, 0f, 960f, 540f))
        biliSheet = overlay
        shownBiliToken = snapshot.biliToken
        (rows.firstOrNull() ?: close).requestFocus()
        Log.i(
            TAG,
            "bili picker open auto=" + auto + " results=" + snapshot.biliResults.size +
                " countdown=" + snapshot.biliCountdown + " focus=first",
        )
    }

    private fun closeBiliSheet() {
        val overlay = biliSheet ?: return
        // Closing the list is the viewer's answer too: nothing may start on its own afterwards.
        if (biliSheetAuto) MusicPlayback.cancelBiliCountdown()
        biliSheet = null
        biliSheetAuto = false
        biliSheetHint = null
        biliSheetHeading = null
        root.removeView(overlay)
        playButton.requestFocus()
        Log.i(TAG, "bili picker closed")
    }

    /**
     * The countdown is only a convenience: the moment the viewer touches the list (a D-pad key, a
     * tap, OK or Back) the pick is theirs, the title loses its countdown and the hint says what to
     * do. Playback goes on (the song resumes as audio if the search had stopped it).
     */
    private fun cancelBiliAutoPlay() {
        if (!biliSheetAuto) return
        biliSheetAuto = false
        biliSheetHeading?.text = BILI_TITLE
        biliSheetHint?.text = BILI_HINT_PICK
        MusicPlayback.cancelBiliCountdown()
        Log.i(TAG, "bili picker: interaction, auto-play cancelled")
    }

    private fun refreshBiliSheet(snapshot: MusicSnapshot) {
        val heading = biliSheetHeading ?: return
        val hint = biliSheetHint ?: return
        heading.text = if (autoPlayPending(snapshot)) BILI_AUTO_TITLE else BILI_TITLE
        hint.text = biliHint(snapshot)
    }

    /** True while the three second countdown may still pick the first result for the viewer. */
    private fun autoPlayPending(snapshot: MusicSnapshot): Boolean =
        biliSheetAuto && snapshot.biliCountdown > 0

    private fun biliHint(snapshot: MusicSnapshot): String {
        val pick = snapshot.biliResults.getOrNull(snapshot.biliSelected)
        return when {
            autoPlayPending(snapshot) && pick != null ->
                snapshot.biliCountdown.toString() + " 秒后自动播放：" + pick.title
            snapshot.mvActive && pick != null -> "正在播放：" + pick.title
            else -> BILI_HINT_PICK
        }
    }

    private fun biliRowLabel(index: Int, mv: BiliMv): String {
        val minutes = mv.duration / 60
        val seconds = mv.duration % 60
        val length = String.format(Locale.US, "%d:%02d", minutes, seconds)
        return (index + 1).toString() + ". " + mv.title + "\n" +
            mv.author + " · " + length + " · " + playCount(mv.play)
    }

    private fun playCount(count: Int): String = when {
        count >= 10_000 -> (count / 10_000).toString() + " 万播放"
        count > 0 -> count.toString() + " 播放"
        else -> "B站"
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
        private const val ID_MV = 1006
        private const val ID_SOURCE = 1007
        private const val ID_BILI_LIST = 1008
        /** A MV starts with the controls up; they fade once playback has settled. */
        private const val CONTROLS_START_MS = 3_000L
        /** Every key or tap after that gives the controls another five seconds. */
        private const val CONTROLS_IDLE_MS = 5_000L
        /** The B站 picker: its plain title, and the one that says the countdown is still running. */
        private const val BILI_TITLE = "B站 MV"
        private const val BILI_AUTO_TITLE = "B站 MV · 3 秒后自动播放第一项"
        private const val BILI_HINT_PICK = "请选择要播放的 MV"

        /** Opens the jukebox from Home or from an agent command (a worker thread may call this). */
        fun start(context: Context) {
            val intent = Intent(context, MusicActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "could not open the jukebox: " + it.javaClass.simpleName) }
        }
    }
}
