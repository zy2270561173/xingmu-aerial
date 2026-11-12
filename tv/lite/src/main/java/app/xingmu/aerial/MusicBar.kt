package app.xingmu.aerial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import app.xingmu.aerial.cast.MusicSnapshot

/**
 * The small now-playing card on Home: cover, song, singer, and the play/pause, next and open
 * actions. HomeView owns its focus (`MUSIC`) and draws the selected action, so a remote can reach
 * it with Up from the top row; it stays a picture otherwise, not a focusable window of its own.
 */
class MusicBar(context: Context) : View(context) {
    private var snapshot: MusicSnapshot? = null
    private var cover: Bitmap? = null
    private var focused = false
    private var selected = 0

    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.px(1.2f)
        color = 0x33FFFFFF
    }
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(16f)
        typeface = Ui.font(600)
        color = Ui.WHITE
    }
    private val singerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(12f)
        typeface = Ui.font(500)
        color = 0x99EBEBF5.toInt()
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(11f)
        typeface = Ui.font(600)
        textAlign = Paint.Align.CENTER
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = Ui.px(30f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = 0xAAFFFFFF.toInt()
    }
    private val coverRect = RectF()
    private val buttonRect = RectF()
    private val cardRect = RectF()

    fun update(snapshot: MusicSnapshot?, cover: Bitmap?, focused: Boolean, selected: Int) {
        this.snapshot = snapshot
        this.cover = cover
        this.focused = focused
        this.selected = selected
        invalidate()
    }

    fun updateCover(bitmap: Bitmap?) {
        cover = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val song = snapshot?.song ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        cardRect.set(0f, 0f, w, h)
        card.color = if (focused) 0xEE26242e.toInt() else 0xCC1b1a20.toInt()
        canvas.drawRoundRect(cardRect, Ui.px(17f), Ui.px(17f), card)
        edge.color = if (focused) 0xCCFFFFFF.toInt() else 0x33FFFFFF
        canvas.drawRoundRect(cardRect, Ui.px(17f), Ui.px(17f), edge)

        val size = Ui.px(58f)
        val left = Ui.px(13f)
        val top = (h - size) / 2f
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
            canvas.drawRoundRect(coverRect, Ui.px(13f), Ui.px(13f), coverPaint)
            coverPaint.shader = null
        } else {
            card.color = 0x33FFFFFF
            canvas.drawRoundRect(coverRect, Ui.px(13f), Ui.px(13f), card)
            canvas.drawText("♪", coverRect.centerX(), coverRect.centerY() + Ui.px(10f), notePaint)
        }

        val textLeft = left + size + Ui.px(13f)
        // The three action pills begin at x=214; the name must stop short of them.
        val maxText = Ui.px(204f) - textLeft
        val name = fit(song.name, titlePaint, maxText)
        canvas.drawText(name, textLeft, Ui.px(35f), titlePaint)
        val who = fit(if (song.singer.isEmpty()) "未知歌手" else song.singer, singerPaint, maxText)
        canvas.drawText(who, textLeft, Ui.px(55f), singerPaint)
        if (snapshot?.playing == true) {
            buttonPaint.color = 0xFF30D158.toInt()
            canvas.drawCircle(w - Ui.px(18f), Ui.px(17f), Ui.px(3.5f), buttonPaint)
        }

        drawButton(canvas, 0, Ui.px(214f), if (snapshot?.playing == true) "暂停" else "播放", Ui.px(52f))
        drawButton(canvas, 1, Ui.px(272f), "下一首", Ui.px(60f))
        drawButton(canvas, 2, Ui.px(338f), "打开", Ui.px(50f))
    }

    private fun drawButton(canvas: Canvas, index: Int, x: Float, label: String, width: Float) {
        val top = Ui.px(44f)
        val bottom = top + Ui.px(27f)
        buttonRect.set(x, top, x + width, bottom)
        val on = focused && selected == index
        buttonPaint.color = if (on) 0xFFF5F5F7.toInt() else 0x2EFFFFFF
        canvas.drawRoundRect(buttonRect, Ui.px(13.5f), Ui.px(13.5f), buttonPaint)
        buttonText.color = if (on) Ui.ON_FOCUS else 0xCCEBEBF5.toInt()
        canvas.drawText(label, buttonRect.centerX(), bottom - Ui.px(8f), buttonText)
    }

    /** Fits a name inside its column with an ellipsis, the way the tiles do. */
    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var cut = text
        while (cut.length > 1 && paint.measureText(cut + "…") > maxWidth) cut = cut.substring(0, cut.length - 1)
        return cut + "…"
    }
}
