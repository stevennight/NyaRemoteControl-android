package app.nya.remote.input

import app.nya.remote.data.ControlMode
import kotlin.math.abs
import kotlin.math.hypot

/** What gestures turn into. Coordinates are remote, 0..1 across the display. */
interface RemoteInput {
    fun moveTo(rx: Float, ry: Float)
    /** 1 left, 2 right (NativeCore.BUTTON_*). */
    fun button(button: Int, down: Boolean)
    /** 1/120 notch units; dy > 0 scrolls up, dx > 0 right. */
    fun wheel(dx: Int, dy: Int)
    fun toggleKeyboard()
    /** Haptic tick (long press recognised). */
    fun feedback()
}

class GestureConfig(
    /** Movement (px) below which a touch is still a tap / press. */
    val touchSlop: Float,
    /** Finger travel (px) for one wheel notch. */
    val scrollPxPerNotch: Float,
    val longPressMs: Long = 450,
    /** Multi-finger taps (keyboard, right click) must end within this. */
    val multiTapMs: Long = 350,
    /** Mouse mode: one picture width of finger travel moves the cursor this many display widths. */
    val pointerSpeed: Float = 1.6f,
)

enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

data class Pt(val id: Int, val x: Float, val y: Float)

/**
 * Turns touches into mouse input.
 *
 * Touch mode (触屏式) acts where the finger is: tap = click, long press =
 * right click, long press then move = drag, one-finger slide = scroll,
 * pinch = zoom the picture, two fingers = pan it, three-finger tap = keyboard.
 *
 * Mouse mode (鼠标式) is a touchpad: one finger moves the cursor, tap =
 * click at the cursor, long press = right click, long press then move = drag,
 * two-finger slide = scroll, pinch = zoom, two-finger tap = right click,
 * three-finger tap = keyboard.
 *
 * Pure logic (no Android types) so it is unit tested; the long-press timer is
 * driven from outside through [longPressAt] / [timeout].
 */
class GestureEngine(
    private val viewport: Viewport,
    private val out: RemoteInput,
    private val cfg: GestureConfig,
) {
    var mode = ControlMode.TOUCH

    /** Cursor position (remote). Mouse mode moves it; the host reports it. */
    var cursorX = 0.5f; private set
    var cursorY = 0.5f; private set

    /** When [timeout] should be called for a long press; null = no timer. */
    var longPressAt: Long? = null; private set

    private enum class Phase { IDLE, PENDING, LONG_PRESSED, SCROLL, MOVE_CURSOR, DRAG, MULTI, DONE }

    private enum class MultiKind { UNDECIDED, ZOOM, SCROLL }

    private var phase = Phase.IDLE
    private var primary = -1
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var maxPointers = 0
    /** Every finger that touched during this gesture (a quick three-finger tap is often staggered). */
    private val touched = HashSet<Int>()

    private var multiKind = MultiKind.UNDECIDED
    private var multiMoved = false
    private var startSpan = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var lastSpan = 0f
    private var lastCx = 0f
    private var lastCy = 0f

    private var wheelAccX = 0f
    private var wheelAccY = 0f

    /** True while a gesture moves the cursor itself (host reports would fight it). */
    val ownsCursor: Boolean get() = phase == Phase.MOVE_CURSOR || phase == Phase.DRAG

    /** The host moved the cursor (e.g. an app warped it). */
    fun hostCursor(rx: Float, ry: Float) {
        if (!ownsCursor) {
            cursorX = rx.coerceIn(0f, 1f)
            cursorY = ry.coerceIn(0f, 1f)
        }
    }

    fun onTouch(action: TouchAction, pointers: List<Pt>, changedId: Int, t: Long) {
        when (action) {
            TouchAction.DOWN -> down(pointers.first { it.id == changedId }, t)
            TouchAction.POINTER_DOWN -> {
                touched += changedId
                pointerDown(pointers)
            }
            TouchAction.MOVE -> move(pointers)
            TouchAction.POINTER_UP -> pointerUp(pointers, changedId)
            TouchAction.UP -> up(t)
            TouchAction.CANCEL -> cancel(t)
        }
    }

    /** The long-press timer fired. */
    fun timeout(t: Long) {
        val at = longPressAt ?: return
        if (t < at || phase != Phase.PENDING) return
        longPressAt = null
        phase = Phase.LONG_PRESSED
        if (mode == ControlMode.TOUCH) placeCursor(downX, downY)
        out.feedback()
    }

    private fun down(p: Pt, t: Long) {
        phase = Phase.PENDING
        primary = p.id
        downX = p.x
        downY = p.y
        lastX = p.x
        lastY = p.y
        downT = t
        maxPointers = 1
        touched.clear()
        touched += p.id
        wheelAccX = 0f
        wheelAccY = 0f
        longPressAt = t + cfg.longPressMs
    }

    private fun pointerDown(pointers: List<Pt>) {
        maxPointers = maxOf(maxPointers, pointers.size)
        when (phase) {
            Phase.PENDING, Phase.LONG_PRESSED, Phase.SCROLL, Phase.MOVE_CURSOR -> {
                phase = Phase.MULTI
                longPressAt = null
                multiKind = MultiKind.UNDECIDED
                multiMoved = false
                startMulti(pointers)
                startSpan = lastSpan
                startCx = lastCx
                startCy = lastCy
            }
            // Another finger: measure from the new set so nothing jumps.
            Phase.MULTI -> rebase(pointers)
            else -> {}
        }
    }

    /** The set of fingers changed: movement counts from here (what moved before still counts). */
    private fun rebase(pointers: List<Pt>) {
        startMulti(pointers)
        startSpan = lastSpan
        startCx = lastCx
        startCy = lastCy
    }

    private fun startMulti(pointers: List<Pt>) {
        val (a, b) = pointers.take(2).let { if (it.size < 2) return else it[0] to it[1] }
        lastSpan = hypot(a.x - b.x, a.y - b.y)
        lastCx = (a.x + b.x) / 2
        lastCy = (a.y + b.y) / 2
    }

    private fun move(pointers: List<Pt>) {
        if (phase == Phase.MULTI) {
            multiMove(pointers)
            return
        }
        val p = pointers.find { it.id == primary } ?: return
        val far = hypot(p.x - downX, p.y - downY) > cfg.touchSlop
        when (phase) {
            Phase.PENDING -> if (far) {
                longPressAt = null
                if (mode == ControlMode.TOUCH) {
                    // Scroll what is under the finger.
                    placeCursor(downX, downY)
                    phase = Phase.SCROLL
                    lastX = p.x
                    lastY = p.y
                } else {
                    phase = Phase.MOVE_CURSOR
                    moveCursorBy(p.x - lastX, p.y - lastY)
                    lastX = p.x
                    lastY = p.y
                }
            }
            Phase.SCROLL -> {
                scroll(p.x - lastX, p.y - lastY)
                lastX = p.x
                lastY = p.y
            }
            Phase.MOVE_CURSOR -> {
                moveCursorBy(p.x - lastX, p.y - lastY)
                lastX = p.x
                lastY = p.y
            }
            Phase.LONG_PRESSED -> if (far) {
                out.button(BUTTON_LEFT, true)
                phase = Phase.DRAG
                dragTo(p)
            }
            Phase.DRAG -> dragTo(p)
            else -> {}
        }
    }

    private fun dragTo(p: Pt) {
        if (mode == ControlMode.TOUCH) {
            placeCursor(p.x, p.y)
        } else {
            moveCursorBy(p.x - lastX, p.y - lastY)
        }
        lastX = p.x
        lastY = p.y
    }

    private fun multiMove(pointers: List<Pt>) {
        if (pointers.size < 2 || maxPointers > 2 || touched.size > 2) return
        val a = pointers[0]
        val b = pointers[1]
        val span = hypot(a.x - b.x, a.y - b.y)
        val cx = (a.x + b.x) / 2
        val cy = (a.y + b.y) / 2
        val spanChange = abs(span - startSpan)
        val travel = hypot(cx - startCx, cy - startCy)
        if (spanChange > cfg.touchSlop || travel > cfg.touchSlop) multiMoved = true
        if (multiKind == MultiKind.UNDECIDED) {
            multiKind = when {
                mode == ControlMode.TOUCH && multiMoved -> MultiKind.ZOOM
                spanChange > cfg.touchSlop * 1.5f -> MultiKind.ZOOM
                travel > cfg.touchSlop -> MultiKind.SCROLL
                else -> MultiKind.UNDECIDED
            }
        }
        when (multiKind) {
            // Zoom and pan together: the picture follows the fingers.
            MultiKind.ZOOM -> {
                if (lastSpan > 1f && span > 1f) viewport.zoomBy(span / lastSpan, cx, cy)
                viewport.panBy(cx - lastCx, cy - lastCy)
            }
            MultiKind.SCROLL -> scroll(cx - lastCx, cy - lastCy)
            MultiKind.UNDECIDED -> {}
        }
        lastSpan = span
        lastCx = cx
        lastCy = cy
    }

    private fun pointerUp(pointers: List<Pt>, changedId: Int) {
        val rest = pointers.filter { it.id != changedId }
        when (phase) {
            Phase.MULTI -> rebase(rest)

            Phase.DRAG -> if (changedId == primary) {
                out.button(BUTTON_LEFT, false)
                phase = Phase.DONE
            }
            else -> {}
        }
    }

    private fun up(t: Long) {
        when (phase) {
            Phase.PENDING -> {
                if (mode == ControlMode.TOUCH) placeCursor(downX, downY)
                click(BUTTON_LEFT)
            }
            Phase.LONG_PRESSED -> click(BUTTON_RIGHT)
            Phase.DRAG -> out.button(BUTTON_LEFT, false)
            Phase.MULTI -> if (isMultiTap(t)) {
                if (touched.size >= 3) out.toggleKeyboard() else click(BUTTON_RIGHT)
            }
            else -> {}
        }
        reset()
    }

    private fun cancel(t: Long) {
        if (phase == Phase.DRAG) out.button(BUTTON_LEFT, false)
        // Many phones watch three fingers for their screenshot gesture and take
        // the touches away (CANCEL) as soon as the third one lands: still a tap.
        if (phase == Phase.MULTI && touched.size >= 3 && isMultiTap(t)) out.toggleKeyboard()
        reset()
    }

    private fun isMultiTap(t: Long) = !multiMoved && t - downT <= cfg.multiTapMs + cfg.longPressMs


    private fun reset() {
        phase = Phase.IDLE
        longPressAt = null
        primary = -1
    }

    private fun click(button: Int) {
        out.button(button, true)
        out.button(button, false)
    }

    /** Touch mode: the cursor goes to the finger. */
    private fun placeCursor(x: Float, y: Float) {
        val (rx, ry) = viewport.toRemote(x, y)
        cursorX = rx
        cursorY = ry
        out.moveTo(rx, ry)
    }

    /** Mouse mode: move like a touchpad; the picture follows when zoomed. */
    private fun moveCursorBy(dx: Float, dy: Float) {
        val w = viewport.width
        val h = viewport.height
        if (w <= 0f || h <= 0f) return
        cursorX = (cursorX + dx * cfg.pointerSpeed / w).coerceIn(0f, 1f)
        // Same speed on both axes: the picture keeps the display's aspect ratio.
        cursorY = (cursorY + dy * cfg.pointerSpeed / h).coerceIn(0f, 1f)
        out.moveTo(cursorX, cursorY)
        viewport.ensureVisible(cursorX, cursorY, cfg.touchSlop * 3)
    }

    /** Natural scrolling: the content follows the finger. */
    private fun scroll(dx: Float, dy: Float) {
        wheelAccY += dy * 120f / cfg.scrollPxPerNotch
        wheelAccX += -dx * 120f / cfg.scrollPxPerNotch
        val wy = wheelAccY.toInt()
        val wx = wheelAccX.toInt()
        wheelAccY -= wy
        wheelAccX -= wx
        if (wx != 0 || wy != 0) out.wheel(wx, wy)
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 2
    }
}
