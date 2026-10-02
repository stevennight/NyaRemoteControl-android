package app.nya.remote.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.view.View
import app.nya.remote.input.Viewport

/**
 * The host's mouse pointer, drawn over the video from the cursor stream (the
 * host doesn't paint it into the picture). Scaled with the picture, but never
 * smaller than a finger-friendly minimum.
 */
class CursorView(context: Context, private val viewport: Viewport) : View(context) {
    private class Shape(val bitmap: Bitmap, val hotX: Int, val hotY: Int)

    private val shapes = HashMap<Int, Shape>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var shapeId = 0
    private var visible = false

    /** Hotspot in source display pixels. */
    private var x = 0
    private var y = 0
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val minScale = resources.displayMetrics.density * 0.6f

    init {
        isClickable = false
        isFocusable = false
    }

    fun setSource(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
        invalidate()
    }

    fun addShape(id: Int, width: Int, height: Int, hotX: Int, hotY: Int, rgbaBase64: String) {
        val rgba = Base64.decode(rgbaBase64, Base64.DEFAULT)
        if (width <= 0 || height <= 0 || rgba.size < width * height * 4) return
        val pixels = IntArray(width * height) { i ->
            val o = i * 4
            (rgba[o + 3].toInt() and 0xff shl 24) or (rgba[o].toInt() and 0xff shl 16) or
                (rgba[o + 1].toInt() and 0xff shl 8) or (rgba[o + 2].toInt() and 0xff)
        }
        val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (shapes.size > 64) shapes.clear()
        shapes[id] = Shape(bmp, hotX, hotY)
        if (id == shapeId) invalidate()
    }

    fun setState(shapeId: Int, visible: Boolean, x: Int, y: Int) {
        this.shapeId = shapeId
        this.visible = visible
        this.x = x
        this.y = y
        invalidate()
    }

    /** We moved the pointer: show it there now instead of a round trip later. */
    fun moveLocal(rx: Float, ry: Float) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        x = (rx * sourceWidth).toInt()
        y = (ry * sourceHeight).toInt()
        invalidate()
    }

    /** Remote position (0..1) of the hotspot, or null before the host told us. */
    fun remotePosition(): Pair<Float, Float>? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        return x.toFloat() / sourceWidth to y.toFloat() / sourceHeight
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible || sourceWidth <= 0 || sourceHeight <= 0) return
        val shape = shapes[shapeId] ?: return
        val scale = maxOf(viewport.width / sourceWidth, minScale)
        val px = viewport.toViewX(x.toFloat() / sourceWidth)
        val py = viewport.toViewY(y.toFloat() / sourceHeight)
        dst.set(px - shape.hotX * scale, py - shape.hotY * scale, 0f, 0f)
        dst.right = dst.left + shape.bitmap.width * scale
        dst.bottom = dst.top + shape.bitmap.height * scale
        canvas.drawBitmap(shape.bitmap, null, dst, paint)
    }
}
