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
import app.xingmu.aerial.cast.MusicActivity

/**
 * The jukebox's fixed Home tile. It is not an installed app, so [AppCatalog] must not see it:
 * [HomeActivity] puts it in front of the arranged grid after every load, which keeps it from being
 * moved into a folder or hidden like the apps around it.
 */
object MusicTile {
    const val KEY = "app.xingmu.aerial:music"

    fun entry(context: Context): AppEntry {
        val art = noteTile()
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
            label = "点歌台",
            component = ComponentName(context.packageName, MusicActivity::class.java.name),
            leanback = false,
            removable = false,
            tile = face,
        )
    }

    /**
     * Aerial's own tile look: a white glyph over a diagonal gradient with light from the top left.
     * A music note rather than the library's play glyph, so the tile reads as 点歌 without its name
     * (Home hides names on the plate by default).
     */
    private fun noteTile(): Bitmap {
        val w = TileFactory.W
        val h = TileFactory.H
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), 0xFF7D6BFF.toInt(), 0xFF392A9E.toInt(), Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w * 0.25f, -h * 0.2f, w * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = h * 0.8f
            setShadowLayer(h * 0.035f, 0f, h * 0.025f, 0x66000000)
        }
        canvas.drawText("♪", w / 2f, h / 2f + h * 0.29f, note)
        return bitmap
    }
}
