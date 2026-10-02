package app.nya.remote

import app.nya.remote.data.ControlMode
import app.nya.remote.input.GestureConfig
import app.nya.remote.input.GestureEngine
import app.nya.remote.input.Pt
import app.nya.remote.input.RemoteInput
import app.nya.remote.input.TouchAction
import app.nya.remote.input.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureEngineTest {
    private val log = mutableListOf<String>()
    private val out = object : RemoteInput {
        override fun moveTo(rx: Float, ry: Float) {
            log += "move %.2f,%.2f".format(java.util.Locale.ROOT, rx, ry)
        }
        override fun button(button: Int, down: Boolean) {
            log += "btn$button ${if (down) "down" else "up"}"
        }
        override fun wheel(dx: Int, dy: Int) {
            log += "wheel $dx,$dy"
        }
        override fun toggleKeyboard() {
            log += "keyboard"
        }
        override fun feedback() {
            log += "buzz"
        }
    }
    private val viewport = Viewport()
    private lateinit var g: GestureEngine

    @Before
    fun setUp() {
        viewport.setView(1000f, 500f)
        viewport.setContent(1000f, 500f)
        g = GestureEngine(viewport, out, GestureConfig(touchSlop = 10f, scrollPxPerNotch = 40f))
    }

    private fun down(x: Float, y: Float, t: Long) = g.onTouch(TouchAction.DOWN, listOf(Pt(0, x, y)), 0, t)
    private fun move(x: Float, y: Float) = g.onTouch(TouchAction.MOVE, listOf(Pt(0, x, y)), 0, 0)
    private fun up(t: Long) = g.onTouch(TouchAction.UP, emptyList(), 0, t)

    @Test
    fun tapClicksWhereTheFingerIs() {
        down(250f, 125f, 0)
        up(100)
        assertEquals(listOf("move 0.25,0.25", "btn1 down", "btn1 up"), log)
    }

    @Test
    fun longPressIsRightClick() {
        down(500f, 250f, 0)
        g.timeout(g.longPressAt!!)
        up(800)
        assertEquals(listOf("move 0.50,0.50", "buzz", "btn2 down", "btn2 up"), log)
    }

    @Test
    fun longPressThenMoveDrags() {
        down(100f, 100f, 0)
        g.timeout(1000)
        move(200f, 100f)
        move(300f, 100f)
        up(2000)
        assertEquals(
            listOf("move 0.10,0.20", "buzz", "btn1 down", "move 0.20,0.20", "move 0.30,0.20", "btn1 up"),
            log,
        )
    }

    @Test
    fun timerBeforeItsTimeDoesNothing() {
        down(100f, 100f, 0)
        g.timeout(10)
        up(50)
        assertEquals("btn1 down", log[1])
    }

    @Test
    fun slideScrollsNaturally() {
        down(500f, 300f, 0)
        move(500f, 290f) // within slop: nothing yet
        move(500f, 280f) // starts scrolling here
        move(500f, 240f) // finger up 40 px = one notch: content follows, wheel down
        up(500)
        assertEquals(listOf("move 0.50,0.60", "wheel 0,-120"), log)
        assertEquals(null, g.longPressAt)
    }

    @Test
    fun threeFingerTapTogglesKeyboard() {
        val a = Pt(0, 100f, 100f)
        val b = Pt(1, 200f, 100f)
        val c = Pt(2, 300f, 100f)
        g.onTouch(TouchAction.DOWN, listOf(a), 0, 0)
        g.onTouch(TouchAction.POINTER_DOWN, listOf(a, b), 1, 10)
        g.onTouch(TouchAction.POINTER_DOWN, listOf(a, b, c), 2, 20)
        g.onTouch(TouchAction.POINTER_UP, listOf(a, b, c), 2, 120)
        g.onTouch(TouchAction.POINTER_UP, listOf(a, b), 1, 130)
        g.onTouch(TouchAction.UP, listOf(a), 0, 140)
        assertEquals(listOf("keyboard"), log)
    }

    @Test
    fun pinchZoomsAndTwoFingersPan() {
        g.onTouch(TouchAction.DOWN, listOf(Pt(0, 400f, 250f)), 0, 0)
        g.onTouch(TouchAction.POINTER_DOWN, listOf(Pt(0, 400f, 250f), Pt(1, 600f, 250f)), 1, 10)
        g.onTouch(TouchAction.MOVE, listOf(Pt(0, 300f, 250f), Pt(1, 700f, 250f)), 0, 50)
        assertEquals(2f, viewport.zoom, 0.01f)
        val before = viewport.left
        g.onTouch(TouchAction.MOVE, listOf(Pt(0, 350f, 250f), Pt(1, 750f, 250f)), 0, 60)
        assertEquals(before + 50f, viewport.left, 0.01f)
        g.onTouch(TouchAction.UP, emptyList(), 0, 500)
        assertTrue("zooming sends nothing to the host", log.isEmpty())
    }

    @Test
    fun mouseModeMovesCursorRelativelyAndTapsAtCursor() {
        g.mode = ControlMode.MOUSE
        g.hostCursor(0.5f, 0.5f)
        down(100f, 100f, 0)
        move(100f, 105f) // within slop
        move(200f, 100f) // 100 px * 1.6 / 1000 px = 0.16
        up(300)
        down(900f, 400f, 1000) // a tap anywhere clicks at the cursor
        up(1050)
        assertEquals(listOf("move 0.66,0.50", "btn1 down", "btn1 up"), log)
    }

    @Test
    fun mouseModeTwoFingerTapIsRightClickAndSlideScrolls() {
        g.mode = ControlMode.MOUSE
        val a = Pt(0, 400f, 250f)
        val b = Pt(1, 500f, 250f)
        g.onTouch(TouchAction.DOWN, listOf(a), 0, 0)
        g.onTouch(TouchAction.POINTER_DOWN, listOf(a, b), 1, 10)
        g.onTouch(TouchAction.UP, emptyList(), 0, 120)
        assertEquals(listOf("btn2 down", "btn2 up"), log)
        log.clear()
        g.onTouch(TouchAction.DOWN, listOf(a), 0, 1000)
        g.onTouch(TouchAction.POINTER_DOWN, listOf(a, b), 1, 1010)
        g.onTouch(TouchAction.MOVE, listOf(Pt(0, 400f, 300f), Pt(1, 500f, 300f)), 0, 1050)
        g.onTouch(TouchAction.UP, emptyList(), 0, 1100)
        assertEquals(listOf("wheel 0,150"), log)
        assertEquals(1f, viewport.zoom, 0f)
    }
}
