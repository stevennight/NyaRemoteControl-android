package app.nya.remote.input

import kotlin.math.max
import kotlin.math.min

/**
 * Where the remote picture sits on the phone screen: fitted (letterboxed) at
 * zoom 1, enlarged around a focus point when zoomed, and panned. Maps between
 * view pixels and remote coordinates (0..1 across the streamed display).
 *
 * [bottomInset] (the soft keyboard) shrinks the area the picture may be
 * panned into, so its lower part can be moved above the keyboard.
 */
class Viewport {
    var viewWidth = 1f; private set
    var viewHeight = 1f; private set
    var contentWidth = 16f; private set
    var contentHeight = 9f; private set
    var bottomInset = 0f; private set

    var zoom = 1f; private set
    /** Top-left of the displayed picture in view pixels. */
    var left = 0f; private set
    var top = 0f; private set

    val maxZoom = 5f

    /** Called after any change, e.g. to lay the video out again. */
    var onChange: (() -> Unit)? = null

    private val fitScale: Float get() = min(viewWidth / contentWidth, viewHeight / contentHeight)
    val width: Float get() = contentWidth * fitScale * zoom
    val height: Float get() = contentHeight * fitScale * zoom

    fun setView(w: Float, h: Float) {
        if (w <= 0f || h <= 0f || (w == viewWidth && h == viewHeight)) return
        viewWidth = w
        viewHeight = h
        reset()
    }

    fun setContent(w: Float, h: Float) {
        if (w <= 0f || h <= 0f || (w == contentWidth && h == contentHeight)) return
        contentWidth = w
        contentHeight = h
        reset()
    }

    fun setBottomInset(inset: Float) {
        if (inset == bottomInset) return
        bottomInset = inset.coerceIn(0f, viewHeight * 0.8f)
        clamp()
        onChange?.invoke()
    }

    fun reset() {
        zoom = 1f
        left = (viewWidth - width) / 2
        top = (viewHeight - height) / 2
        clamp()
        onChange?.invoke()
    }

    /** Scale by [factor] keeping the point under ([fx], [fy]) in place. */
    fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val z = (zoom * factor).coerceIn(1f, maxZoom)
        if (z == zoom) return
        val r = z / zoom
        left = fx - (fx - left) * r
        top = fy - (fy - top) * r
        zoom = z
        clamp()
        onChange?.invoke()
    }

    fun panBy(dx: Float, dy: Float) {
        left += dx
        top += dy
        clamp()
        onChange?.invoke()
    }

    /** Pan so the remote point ([rx], [ry]) is inside the visible area with [margin] px to spare. */
    fun ensureVisible(rx: Float, ry: Float, margin: Float) {
        val x = left + rx * width
        val y = top + ry * height
        val visibleH = viewHeight - bottomInset
        var dx = 0f
        var dy = 0f
        if (x < margin) dx = margin - x else if (x > viewWidth - margin) dx = viewWidth - margin - x
        if (y < margin) dy = margin - y else if (y > visibleH - margin) dy = visibleH - margin - y
        if (dx != 0f || dy != 0f) panBy(dx, dy)
    }

    /** Remote coordinates (clamped to 0..1) of a view point. */
    fun toRemote(x: Float, y: Float): Pair<Float, Float> =
        ((x - left) / width).coerceIn(0f, 1f) to ((y - top) / height).coerceIn(0f, 1f)

    fun toViewX(rx: Float) = left + rx * width
    fun toViewY(ry: Float) = top + ry * height

    /** View pixels per remote pixel. */
    val pixelScale: Float get() = width / contentWidth

    private fun clamp() {
        left = clampAxis(left, width, viewWidth, viewWidth)
        top = clampAxis(top, height, viewHeight, viewHeight - bottomInset)
    }

    /**
     * A picture smaller than the view stays centered; a larger one may not
     * leave a gap at either edge. With an inset, the far edge may come up to
     * the inset instead.
     */
    private fun clampAxis(pos: Float, size: Float, view: Float, visible: Float): Float {
        if (size <= visible) {
            // Fits the visible part: centered in the view, unless that hides it behind the inset.
            val centered = (view - size) / 2
            return if (centered + size <= visible) centered else max(0f, visible - size)
        }
        return pos.coerceIn(visible - size, 0f)
    }
}
