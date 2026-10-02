package app.nya.remote

import android.view.KeyEvent
import app.nya.remote.core.CoreEvent
import app.nya.remote.input.KeyMap
import app.nya.remote.input.KeyboardController
import app.nya.remote.input.PadState
import app.nya.remote.input.ScanKey
import org.junit.Assert.assertEquals
import org.junit.Test

class InputFeaturesTest {
    private val log = mutableListOf<String>()
    private val keys = KeyboardController(
        send = { k, down -> log += "%02x%s".format(k.code, if (down) "v" else "^") },
        sendText = { log += "text:$it" },
        paste = { log += "paste:$it" },
    )

    @Test
    fun chineseOnAnOldHostGoesThroughItsClipboard() {
        keys.textInput = false
        keys.type("你好")
        assertEquals(listOf("paste:你好"), log)
    }

    @Test
    fun asciiWordOnAnOldHostIsTypedAsKeys() {
        keys.textInput = false
        keys.type("ok")
        assertEquals(listOf("18v", "18^", "25v", "25^"), log)
    }

    @Test
    fun dibRoundTrip() {
        val px = app.nya.remote.data.Dib.Pixels(2, 2, intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0x80123456.toInt()))
        val back = app.nya.remote.data.Dib.decode(app.nya.remote.data.Dib.encode(px))!!
        assertEquals(2, back.width)
        assertEquals(px.argb.toList(), back.argb.toList())
    }

    @Test
    fun opaqueDibWithZeroAlpha() {
        val dib = app.nya.remote.data.Dib.encode(app.nya.remote.data.Dib.Pixels(1, 1, intArrayOf(0x00102030)))
        assertEquals(0xff102030.toInt(), app.nya.remote.data.Dib.decode(dib)!!.argb[0])
    }

    @Test
    fun versionOrder() {
        val u = app.nya.remote.data.Updater
        assertEquals(true, u.newer("0.2.0", "0.1.9"))
        assertEquals(false, u.newer("0.1.0", "0.1.0"))
        assertEquals(true, u.newer("0.2.0", "0.2.0-beta.1"))
        assertEquals(false, u.newer("0.2.0-beta.1", "0.2.0"))
        assertEquals(true, u.newer("1.0.0", "0.10.3"))
    }


    @Test
    fun textModeSendsUnicodeAndKeepsLineBreaksAsKeys() {
        keys.textInput = true
        keys.type("你好\nok")
        assertEquals(listOf("text:你好", "1cv", "1c^", "text:ok"), log)
    }

    @Test
    fun latchedModifierUsesKeysEvenInTextMode() {
        keys.textInput = true
        keys.toggleSticky(KeyMap.LCTRL)
        keys.type("c")
        assertEquals(listOf("1dv", "2ev", "2e^", "1d^"), log)
        assertEquals(emptySet<ScanKey>(), keys.sticky)
    }

    @Test
    fun keyModeTypesUsLayout() {
        keys.type("A")
        assertEquals(listOf("2av", "1ev", "1e^", "2a^"), log)
    }

    @Test
    fun padMapping() {
        val p = PadState()
        assertEquals(32767, p.stick(1f))
        assertEquals(-32767, p.stick(1f, invert = true)) // Android down = XInput down (negative)
        assertEquals(0, p.stick(0.05f)) // dead zone
        assertEquals(255, p.trigger(1.2f))
        p.setHat(-1f, 1f)
        assertEquals(PadState.DPAD_LEFT or PadState.DPAD_DOWN, p.buttons)
        p.setHat(0f, 0f)
        assertEquals(0, p.buttons)
        assertEquals(PadState.A, PadState.buttonBit(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(0, PadState.buttonBit(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun parsesFileAndRumbleEvents() {
        val o = CoreEvent.parse(
            """{"type":"fileOffer","id":"18446744073709551610","files":[{"name":"a.txt","path":"a.txt","size":5,"isDir":false}],"totalBytes":5}""",
        ) as CoreEvent.FileOffer
        assertEquals("18446744073709551610", o.id)
        assertEquals(5L, o.files.single().size)
        val t = CoreEvent.parse(
            """{"type":"transfer","id":"1","upload":true,"name":"a","done":3,"total":10,"finished":false,"ok":true,"message":""}""",
        ) as CoreEvent.Transfer
        assertEquals(3L, t.done)
        val r = CoreEvent.parse("""{"type":"rumble","index":1,"large":200,"small":0}""") as CoreEvent.Rumble
        assertEquals(200, r.large)
        val c = CoreEvent.parse(
            """{"type":"connected","serverName":"pc","serverVersion":"0.6","serverFingerprint":"ab","serverFingerprintShort":"AB","textInput":true,"fileTransfer":true,"gamepad":false}""",
        ) as CoreEvent.Connected
        assertEquals(true, c.textInput)
        assertEquals(false, c.gamepad)
    }
}
