package app.nya.remote.input

import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/** One controller in XInput terms (what the host's virtual Xbox 360 pad gets). */
class PadState {
    var buttons = 0
    var leftTrigger = 0
    var rightTrigger = 0
    var lx = 0
    var ly = 0
    var rx = 0
    var ry = 0

    /** Stick axis (-1..1, Android: down positive) to XInput (-32768..32767, up positive when [invert]). */
    fun stick(v: Float, invert: Boolean = false): Int {
        val x = if (abs(v) < DEADZONE) 0f else v
        val s = if (invert) -x else x
        return (s * 32767f).roundToInt().coerceIn(-32768, 32767)
    }

    fun trigger(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    fun setButton(bit: Int, down: Boolean) {
        buttons = if (down) buttons or bit else buttons and bit.inv()
    }

    /** The d-pad as a hat (-1..1 per axis). */
    fun setHat(x: Float, y: Float) {
        setButton(DPAD_LEFT, x < -0.5f)
        setButton(DPAD_RIGHT, x > 0.5f)
        setButton(DPAD_UP, y < -0.5f)
        setButton(DPAD_DOWN, y > 0.5f)
    }

    companion object {
        const val DEADZONE = 0.08f
        const val DPAD_UP = 0x0001
        const val DPAD_DOWN = 0x0002
        const val DPAD_LEFT = 0x0004
        const val DPAD_RIGHT = 0x0008
        const val START = 0x0010
        const val BACK = 0x0020
        const val LEFT_THUMB = 0x0040
        const val RIGHT_THUMB = 0x0080
        const val LEFT_SHOULDER = 0x0100
        const val RIGHT_SHOULDER = 0x0200
        const val GUIDE = 0x0400
        const val A = 0x1000
        const val B = 0x2000
        const val X = 0x4000
        const val Y = 0x8000

        /** XINPUT_GAMEPAD_* bit of an Android gamepad key, 0 if none. */
        fun buttonBit(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> A
            KeyEvent.KEYCODE_BUTTON_B -> B
            KeyEvent.KEYCODE_BUTTON_X -> X
            KeyEvent.KEYCODE_BUTTON_Y -> Y
            KeyEvent.KEYCODE_BUTTON_L1 -> LEFT_SHOULDER
            KeyEvent.KEYCODE_BUTTON_R1 -> RIGHT_SHOULDER
            KeyEvent.KEYCODE_BUTTON_THUMBL -> LEFT_THUMB
            KeyEvent.KEYCODE_BUTTON_THUMBR -> RIGHT_THUMB
            KeyEvent.KEYCODE_BUTTON_START -> START
            KeyEvent.KEYCODE_BUTTON_SELECT -> BACK
            KeyEvent.KEYCODE_BUTTON_MODE -> GUIDE
            KeyEvent.KEYCODE_DPAD_UP -> DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> DPAD_RIGHT
            else -> 0
        }
    }
}

/**
 * Game controllers on the phone (USB / Bluetooth) as host pads 0..3, in the
 * order they are first used. Rumble from the host goes to the controller's
 * vibrator when it has one.
 */
class Gamepads(private val send: (index: Int, connected: Boolean, state: PadState) -> Unit) {
    private class Pad(val index: Int, val state: PadState = PadState())

    private val pads = LinkedHashMap<Int, Pad>()

    /** Called when the first controller is seen (e.g. to tell the user). */
    var onFirstPad: ((String) -> Unit)? = null

    /** Called when controllers come or go, with how many there are. */
    var onCount: ((Int) -> Unit)? = null

    private fun isPad(source: Int) =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private fun pad(device: InputDevice?): Pad? {
        val d = device ?: return null
        pads[d.id]?.let { return it }
        val free = (0..3).firstOrNull { i -> pads.values.none { it.index == i } } ?: return null
        if (pads.isEmpty()) onFirstPad?.invoke(d.name)
        return Pad(free).also {
            pads[d.id] = it
            onCount?.invoke(pads.size)
        }
    }

    /** A gamepad key; true if consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isPad(event.source)) return false
        val bit = PadState.buttonBit(event.keyCode)
        if (bit == 0) return false
        val p = pad(event.device) ?: return false
        if (event.repeatCount > 0) return true
        p.state.setButton(bit, event.action == KeyEvent.ACTION_DOWN)
        send(p.index, true, p.state)
        return true
    }

    /** Sticks, triggers and the d-pad hat; true if consumed. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) return false
        val p = pad(event.device) ?: return false
        val s = p.state
        fun axis(a: Int) = event.getAxisValue(a)
        s.lx = s.stick(axis(MotionEvent.AXIS_X))
        s.ly = s.stick(axis(MotionEvent.AXIS_Y), invert = true)
        // Right stick: Z/RZ on most controllers, RX/RY on some.
        val hasZ = event.device?.getMotionRange(MotionEvent.AXIS_Z) != null
        s.rx = s.stick(axis(if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX))
        s.ry = s.stick(axis(if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY), invert = true)
        s.leftTrigger = s.trigger(maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)))
        s.rightTrigger = s.trigger(maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)))
        if (event.device?.getMotionRange(MotionEvent.AXIS_HAT_X) != null) {
            s.setHat(axis(MotionEvent.AXIS_HAT_X), axis(MotionEvent.AXIS_HAT_Y))
        }
        send(p.index, true, s)
        return true
    }

    /** A controller was unplugged. */
    fun removed(deviceId: Int) {
        val p = pads.remove(deviceId) ?: return
        send(p.index, false, PadState())
        onCount?.invoke(pads.size)
    }

    /** Unplug every pad on the host (session ends). */
    fun clear() {
        pads.values.forEach { send(it.index, false, PadState()) }
        pads.clear()
        onCount?.invoke(0)
    }

    /** Host force feedback for pad [index]. */
    @Suppress("DEPRECATION")
    fun rumble(index: Int, large: Int, small: Int, devices: (Int) -> InputDevice?) {
        val id = pads.entries.firstOrNull { it.value.index == index }?.key ?: return
        val device = devices(id) ?: return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) device.vibratorManager.defaultVibrator else device.vibrator
        if (!vibrator.hasVibrator()) return
        val strength = maxOf(large, small).coerceIn(0, 255)
        if (strength == 0) {
            vibrator.cancel()
        } else {
            // The host reports changes only: run until it says 0 (capped, should that message get lost).
            val amplitude = if (vibrator.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(5_000, amplitude))
        }
    }
}
