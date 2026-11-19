package app.xingmu.aerial

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import app.xingmu.aerial.cast.DouyinActivity

/**
 * 抖音's fixed Home tile, built like [MusicTile]: not an installed app, added to Home's list on
 * every load, so the shared LayoutStore can move, fold or hide it like any other tile.
 */
object DouyinTile {
    const val KEY = "app.xingmu.aerial:douyin"

    fun entry(context: Context): AppEntry {
        val art = markTile()
        val face = Shapes.face(
            art,
            AppCatalog.faceW,
            AppCatalog.faceH,
            Ui.px(12f * HomeView.FOCUS_SCALE),
            Ui.px(0.5f * HomeView.FOCUS_SCALE),
        )
        art.recycle()
        return AppEntry(
            key = KEY,
            label = context.getString(R.string.douyin_tile),
            component = ComponentName(context.packageName, DouyinActivity::class.java.name),
            leanback = false,
            removable = false,
            tile = face,
        )
    }

    /**
     * Aerial's tile look — a white glyph over a gradient — in 抖音's colours: near-black with the
     * brand's cyan and pink as light from two corners. "抖" instead of a logo, as the icon library
     * has no 抖音 picture for a tile that is not an installed app.
     */
    private fun markTile(): Bitmap {
        val w = TileFactory.W
        val h = TileFactory.H
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF232327.toInt(), 0xFF070709.toInt(), Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w * 0.08f, -h * 0.1f, w * 0.62f, 0x5525F4EE, 0x0025F4EE, Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w * 0.96f, h * 1.05f, w * 0.58f, 0x55FE2C55, 0x00FE2C55, Shader.TileMode.CLAMP)
        })
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = h * 0.62f
            setShadowLayer(h * 0.045f, 0f, h * 0.03f, 0x77000000)
        }
        val metrics = mark.fontMetrics
        canvas.drawText("抖", w / 2f, h / 2f - (metrics.ascent + metrics.descent) / 2f, mark)
        return bitmap
    }
}
