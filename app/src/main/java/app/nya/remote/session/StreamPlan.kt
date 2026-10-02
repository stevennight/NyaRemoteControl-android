package app.nya.remote.session

import app.nya.remote.core.StreamOptions
import app.nya.remote.core.VirtualScreen
import app.nya.remote.data.ConnSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** "不限制": the top of the encoder's range; static office content still only uses what it needs. */
const val UNLIMITED_KBPS = 80_000

/** Frame rate to ask for: the setting, never above the screen's refresh rate. */
fun fpsLimit(s: ConnSettings, refreshHz: Int): Int = if (s.maxFps > 0) min(s.maxFps, refreshHz) else refreshHz

/**
 * What to ask the host for, given the settings and the phone's screen
 * (physical pixels, any orientation; the session is always landscape).
 * [hdrCapable]: the screen shows HDR10 and a decoder handles HEVC Main10.
 */
fun streamOptions(
    s: ConnSettings,
    screenWidth: Int,
    screenHeight: Int,
    refreshHz: Int,
    hdrCapable: Boolean = false,
): StreamOptions {
    var w = max(screenWidth, screenHeight)
    var h = min(screenWidth, screenHeight)
    var scale = s.vdScale
    when (s.vdSize) {
        "fixed" -> {
            w = s.vdWidth
            h = s.vdHeight
        }
        "screen1080" -> if (h > 1080) {
            // Same aspect ratio, fewer pixels; scaling shrinks with it so text keeps its size.
            val f = 1080f / h
            w = (w * f).roundToInt()
            h = 1080
            if (scale > 0) scale = max(100, ((scale * f) / 25f).roundToInt() * 25)
        }
    }
    val screen = if (s.vdCount > 0) {
        VirtualScreen(width = w, height = h, refreshHz = fpsLimit(s, refreshHz).coerceAtLeast(30), scalePercent = scale)
    } else {
        null
    }
    return StreamOptions(
        codec = s.codec,
        bitrateKbps = if (s.unlimitedBitrate) UNLIMITED_KBPS else s.bitrateKbps,
        game = s.game,
        videoTransport = s.videoTransport,
        virtualScreen = screen,
        virtualCount = s.vdCount.coerceIn(0, 4),
        physicalOff = s.physicalOff && screen != null,
        blockInput = s.blockInput,
        encoder = s.encoder,
        displayId = s.display,
        bitratePolicy = s.bitratePolicy,
        hdr = s.hdr && hdrCapable,
    )
}
