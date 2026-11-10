package app.xingmu.remote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class JpegPreviewView(context: Context) : View(context) {
    private val decoder = Executors.newSingleThreadExecutor()
    private val latest = AtomicReference<ByteArray?>()
    private val draining = AtomicBoolean(false)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var frontFrame: Bitmap? = null

    @Volatile private var released = false

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun offer(jpeg: ByteArray) {
        if (released) return
        latest.set(jpeg)
        drain()
    }

    private fun drain() {
        if (released || !draining.compareAndSet(false, true)) return
        try {
            decoder.execute {
                try {
                    while (!released) {
                        val bytes = latest.getAndSet(null) ?: break
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            ?: continue
                        post {
                            if (released) {
                                decoded.recycle()
                            } else {
                                val previous = frontFrame
                                frontFrame = decoded
                                previous?.recycle()
                                invalidate()
                            }
                        }
                    }
                } finally {
                    draining.set(false)
                    if (latest.get() != null) drain()
                }
            }
        } catch (_: RejectedExecutionException) {
            draining.set(false)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bitmap = frontFrame ?: return
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val targetWidth = (bitmap.width * scale).toInt()
        val targetHeight = (bitmap.height * scale).toInt()
        val left = (width - targetWidth) / 2
        val top = (height - targetHeight) / 2
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            Rect(left, top, left + targetWidth, top + targetHeight),
            paint,
        )
    }

    fun release() {
        released = true
        latest.set(null)
        decoder.shutdownNow()
        frontFrame?.recycle()
        frontFrame = null
    }
}
