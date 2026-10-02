package app.nya.remote

import app.nya.remote.input.Viewport
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {
    @Test
    fun fitsAndCentersAWiderPicture() {
        val v = Viewport()
        v.setView(2000f, 1000f)
        v.setContent(1920f, 1080f) // 16:9 on a 2:1 screen: pillarboxed
        assertEquals(1000f, v.height, 0.01f)
        assertEquals((2000f - v.width) / 2, v.left, 0.01f)
        val (rx, ry) = v.toRemote(v.left, 500f)
        assertEquals(0f, rx, 0.001f)
        assertEquals(0.5f, ry, 0.001f)
    }

    @Test
    fun zoomKeepsTheFocusPointInPlaceAndClamps() {
        val v = Viewport()
        v.setView(1000f, 500f)
        v.setContent(1000f, 500f)
        val before = v.toRemote(250f, 125f)
        v.zoomBy(2f, 250f, 125f)
        assertEquals(before, v.toRemote(250f, 125f))
        v.panBy(10_000f, 0f)
        assertEquals(0f, v.left, 0f) // no gap at the left edge
        v.zoomBy(100f, 0f, 0f)
        assertEquals(v.maxZoom, v.zoom, 0f)
        v.reset()
        assertEquals(1f, v.zoom, 0f)
    }

    @Test
    fun keyboardInsetLetsTheBottomPanAboveIt() {
        val v = Viewport()
        v.setView(1000f, 500f)
        v.setContent(1000f, 500f)
        v.setBottomInset(200f)
        v.ensureVisible(0.5f, 0.95f, 10f) // near the bottom: must end up above the keyboard
        val y = v.toViewY(0.95f)
        assertEquals(true, y <= 300f - 10f + 0.01f)
        v.setBottomInset(0f)
        assertEquals(0f, v.top, 0.01f)
    }
}
