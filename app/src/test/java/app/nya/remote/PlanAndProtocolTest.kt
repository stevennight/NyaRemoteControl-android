package app.nya.remote

import app.nya.remote.core.CoreEvent
import app.nya.remote.data.ConnSettings
import app.nya.remote.input.KeyMap
import app.nya.remote.input.ScanKey
import app.nya.remote.session.streamOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanAndProtocolTest {
    @Test
    fun virtualScreenFollowsThePhoneInLandscape() {
        val o = streamOptions(ConnSettings(), 1080, 2400, 120)
        val vs = o.virtualScreen!!
        assertEquals(2400, vs.width)
        assertEquals(1080, vs.height)
        assertEquals(60, vs.refreshHz) // capped by maxFps
        assertEquals(150, vs.scalePercent)
    }

    @Test
    fun at1080TheScaleShrinksWithTheResolution() {
        val o = streamOptions(ConnSettings(vdSize = "screen1080", vdScale = 200), 3200, 1440, 60)
        val vs = o.virtualScreen!!
        assertEquals(1080, vs.height)
        assertEquals(2400, vs.width)
        assertEquals(150, vs.scalePercent)
    }

    @Test
    fun hostResolutionMeansNoVirtualScreen() {
        val o = streamOptions(ConnSettings(vdCount = 0, physicalOff = true), 2400, 1080, 60)
        assertNull(o.virtualScreen)
        assertEquals(false, o.physicalOff)
    }

    @Test
    fun privacyScreensFixedSizeAndBitrate() {
        val s = ConnSettings(
            vdCount = 3, physicalOff = true, blockInput = true, vdSize = "fixed", vdWidth = 1920, vdHeight = 1200,
            unlimitedBitrate = true, maxFps = 0, encoder = "qsv",
        )
        val o = streamOptions(s, 1080, 2400, 120)
        assertEquals(3, o.virtualCount)
        assertEquals(true, o.physicalOff && o.blockInput)
        assertEquals(1920 to 1200, o.virtualScreen!!.width to o.virtualScreen.height)
        assertEquals(120, o.virtualScreen.refreshHz) // 0 = follow the screen
        assertEquals(80_000, o.bitrateKbps)
        assertEquals("qsv", o.encoder)
        // Fewer virtual screens than before: back to the primary display.
        val one = s.copy(display = 5).withDisplayChoice(s.displayChoice.copy(count = 1))
        assertEquals(0, one.display)
        assertEquals(1, one.vdCount)
        // None left: the physical displays can't stay off.
        assertEquals(false, s.withDisplayChoice(s.displayChoice.copy(count = 0)).physicalOff)
    }

    @Test
    fun usLayoutStrokes() {
        assertEquals(ScanKey(0x1E), KeyMap.stroke('a')!!.key)
        assertEquals(true, KeyMap.stroke('A')!!.shift)
        assertEquals(ScanKey(0x03), KeyMap.stroke('@')!!.key) // Shift+2
        assertEquals(ScanKey(0x1C), KeyMap.stroke('\n')!!.key)
        assertNull(KeyMap.stroke('中'))
        assertEquals(ScanKey(0x44), KeyMap.function(10))
        assertEquals(ScanKey(0x58), KeyMap.function(12))
    }

    @Test
    fun parsesCoreEvents() {
        val e = CoreEvent.parse("""{"type":"stats","fps":60,"mbps":12.5,"rttMs":3.2,"latencyMs":20,"decodeMs":4,"serverFps":60,"encodeMs":2,"targetKbps":20000,"fecPercent":20,"framesLost":0,"framesDropped":1,"extra":1}""")
        assertEquals(60, (e as CoreEvent.Stats).line.fps)
        assertEquals(1, e.line.framesDropped)
        val r = CoreEvent.parse("""{"type":"role","controlling":false,"controller":"PC","viewers":["a","b"]}""") as CoreEvent.Role
        assertEquals(listOf("a", "b"), r.viewers)
        assertNull(CoreEvent.parse("""{"type":"somethingNew"}"""))
        assertEquals(CoreEvent.NeedPairing, CoreEvent.parse("""{"type":"needPairing"}"""))
        assertEquals(CoreEvent.VerifyFingerprint("AB12-CD34"), CoreEvent.parse("""{"type":"verifyFingerprint","fingerprint":"AB12-CD34"}"""))
        assertEquals(CoreEvent.PinChanged("x"), CoreEvent.parse("""{"type":"pinChanged","message":"x"}"""))
        val d = CoreEvent.parse(
            """{"type":"sessionInfo","hostName":"pc","virtualDisplayAvailable":true,"displays":[{"id":3,"name":"","width":2400,"height":1080,"primary":true,"isVirtual":true,"hdr":false,"refreshHz":60,"virtualIndex":1}]}""",
        ) as CoreEvent.SessionInfo
        assertEquals(1, d.displays.single().virtualIndex)
    }
}
