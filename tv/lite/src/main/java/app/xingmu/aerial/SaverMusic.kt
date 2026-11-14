package app.xingmu.aerial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import app.xingmu.aerial.cast.CastConfig
import app.xingmu.aerial.cast.LyricLine
import app.xingmu.aerial.cast.MusicApi
import app.xingmu.aerial.cast.MusicSnapshot
import app.xingmu.aerial.cast.MusicSong

/**
 * What is playing, over the screensaver: a small translucent card with the cover, the song and a
 * few lyric lines. It has no actions and takes no focus — the screensaver must stay a picture, not
 * a remote control. SaverView feeds it the player's state; it only draws.
 *
 * In MV mode the card shows the MV's cover and a 「MV·QM」/「MV·B站」 badge instead of the lyric
 * lines, and SaverView paints that cover behind everything.
 */
class SaverMusic(context: Context) : View(context) {
    private var song: MusicSong? = null
    private var playing = false
    private var cover: Bitmap? = null
    /** The URL the current [cover] came from: the MV's cover, else the song's album art. */
    private var coverKey = ""
    private var lyrics: List<LyricLine> = emptyList()
    private var lyricMid = ""
    private var lyricIndex = -1
    private var position = 0L
    private var mvActive = false
    private var mvSource = CastConfig.MV_SOURCE_QM

    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.px(1f)
        color = 0x1AFFFFFF
    }
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val note = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(28f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = 0x66FFFFFF
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(15f)
        typeface = Ui.font(600)
        color = 0xE6FFFFFF.toInt()
        setShadowLayer(Ui.px(4f), 0f, 0f, 0x99000000.toInt())
    }
    private val singer = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(11.5f)
        typeface = Ui.font(500)
        color = 0x8AFFFFFF.toInt()
        setShadowLayer(Ui.px(3f), 0f, 0f, 0x99000000.toInt())
    }
    private val lyricCurrent = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(16f)
        typeface = Ui.font(600)
        color = 0xC0FFFFFF.toInt()
        setShadowLayer(Ui.px(4f), 0f, 0f, 0x99000000.toInt())
    }
    private val lyricOther = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(12.5f)
        typeface = Ui.font(500)
        color = 0x4DFFFFFF
        setShadowLayer(Ui.px(3f), 0f, 0f, 0x99000000.toInt())
    }
    private val cardRect = RectF()
    private val coverRect = RectF()
    private val badgeRect = RectF()
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(10.5f)
        typeface = Ui.font(600)
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
    }

    init {
        isFocusable = false
        isClickable = false
        visibility = GONE
    }

    /** Called on the main thread by SaverView's ticker and by MusicPlayback's listener. */
    fun update(snapshot: MusicSnapshot, positionMs: Long) {
        position = positionMs
        val now = snapshot.song
        if (now == null) {
            song = null
            playing = false
            cover = null
            coverKey = ""
            lyrics = emptyList()
            lyricMid = ""
            lyricIndex = -1
            mvActive = false
            visibility = GONE
            return
        }
        song = now
        playing = snapshot.playing
        mvActive = snapshot.mvActive
        mvSource = snapshot.mvSource
        if (visibility != VISIBLE) visibility = VISIBLE
        // 屏保跟随 MV：MV 正在播放时用 MV 封面，没有则退回歌曲封面。
        val target = if (mvActive && snapshot.mvCover.isNotEmpty()) snapshot.mvCover else now.cover
        if (target != coverKey) {
            coverKey = target
            cover = null
            loadCover(target)
        }
        if (now.mid != lyricMid) {
            lyricMid = now.mid
            lyrics = emptyList()
            lyricIndex = -1
            loadLyrics(now)
        }
        val active = indexAt(positionMs)
        if (active != lyricIndex) lyricIndex = active
        invalidate()
    }

    private fun loadCover(url: String) {
        MusicApi.cover(url) { bitmap, _ ->
            if (url == coverKey && bitmap != null) {
                cover = bitmap
                invalidate()
            }
        }
    }

    private fun loadLyrics(song: MusicSong) {
        MusicApi.lyric(song.mid) { lines, _ ->
            if (song.mid != lyricMid) return@lyric
            lyrics = lines
            lyricIndex = indexAt(position)
            invalidate()
        }
    }

    private fun indexAt(ms: Long): Int {
        if (lyrics.isEmpty()) return -1
        var found = -1
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= ms + 120L) found = i else break
        }
        return found
    }

    override fun onDraw(canvas: Canvas) {
        val song = song ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        cardRect.set(0f, 0f, w, h)
        card.color = 0x2A000000
        canvas.drawRoundRect(cardRect, Ui.px(16f), Ui.px(16f), card)
        canvas.drawRoundRect(cardRect, Ui.px(16f), Ui.px(16f), cardEdge)

        val size = Ui.px(52f)
        val left = Ui.px(10f)
        val top = (h - size) / 2f - Ui.px(12f)
        coverRect.set(left, top, left + size, top + size)
        val bitmap = cover
        if (bitmap != null) {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = size / minOf(bitmap.width, bitmap.height).coerceAtLeast(1)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(left - bitmap.width * scale / 2f + size / 2f, top - bitmap.height * scale / 2f + size / 2f)
            })
            coverPaint.shader = shader
            canvas.drawRoundRect(coverRect, Ui.px(12f), Ui.px(12f), coverPaint)
            coverPaint.shader = null
        } else {
            card.color = 0x33FFFFFF
            canvas.drawRoundRect(coverRect, Ui.px(12f), Ui.px(12f), card)
            canvas.drawText("♪", coverRect.centerX(), coverRect.centerY() + Ui.px(9f), note)
        }

        val textLeft = left + size + Ui.px(12f)
        // The badge takes the corner the playing dot uses, so the MV state reads at a glance.
        val maxText = if (mvActive) w - textLeft - Ui.px(92f) else w - textLeft - Ui.px(12f)
        canvas.drawText(fit(song.name, title, maxText), textLeft, Ui.px(31f), title)
        val who = if (song.singer.isEmpty()) "未知歌手" else song.singer
        canvas.drawText(fit(who, singer, maxText), textLeft, Ui.px(50f), singer)

        if (mvActive) {
            badgeRect.set(w - Ui.px(88f), Ui.px(12f), w - Ui.px(12f), Ui.px(34f))
            badge.color = if (mvSource == CastConfig.MV_SOURCE_BILI) 0xCCB23BEA.toInt() else 0xCC5B4BF5.toInt()
            canvas.drawRoundRect(badgeRect, Ui.px(11f), Ui.px(11f), badge)
            val label = if (mvSource == CastConfig.MV_SOURCE_BILI) "MV·B站" else "MV·QM"
            canvas.drawText(label, badgeRect.centerX(), badgeRect.centerY() + Ui.px(4f), badgeText)
            // MV mode: no lyric rows on the saver, only cover + song + badge.
            return
        }

        if (playing) {
            card.color = 0xCC6EE7A0.toInt()
            canvas.drawCircle(w - Ui.px(18f), Ui.px(20f), Ui.px(3f), card)
        }

        val lines = lyrics
        if (lines.isEmpty()) {
            canvas.drawText("♪ 正在播放", textLeft, Ui.px(101f), lyricOther)
            return
        }
        val at = if (lyricIndex >= 0) lyricIndex else 0
        if (at > 0) {
            canvas.drawText(fit(lines[at - 1].text, lyricOther, maxText), textLeft, Ui.px(78f), lyricOther)
        }
        val current = lines.getOrNull(at)
        if (current != null) {
            canvas.drawText(fit(current.text, lyricCurrent, maxText), textLeft, Ui.px(103f), lyricCurrent)
        }
        val next = lines.getOrNull(at + 1)
        if (next != null) {
            canvas.drawText(fit(next.text, lyricOther, maxText), textLeft, Ui.px(125f), lyricOther)
        }
    }

    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var cut = text
        while (cut.length > 1 && paint.measureText(cut + "…") > maxWidth) cut = cut.substring(0, cut.length - 1)
        return cut + "…"
    }
}
