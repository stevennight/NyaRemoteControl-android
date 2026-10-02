package app.nya.remote.session

import app.nya.remote.core.StreamOptions
import app.nya.remote.core.VirtualScreen
import app.nya.remote.data.Resolution
import app.nya.remote.data.Settings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What to ask the host for, given the settings and the phone's screen
 * (physical pixels, any orientation; the session is always landscape).
 * [hdrCapable]: the screen shows HDR10 and a decoder handles HEVC Main10.
 * [displayId]: the host display picked in the panel (0 = primary).
 */
fun streamOptions(
    s: Settings,
    screenWidth: Int,
    screenHeight: Int,
    refreshHz: Int,
    hdrCapable: Boolean = false,
    displayId: Int = 0,
): StreamOptions {
    var w = max(screenWidth, screenHeight)
    var h = min(screenWidth, screenHeight)
    var scale = s.scalePercent
    if (s.resolution == Resolution.SCREEN_1080 && h > 1080) {
        // Same aspect ratio, fewer pixels; scaling shrinks with it so text keeps its size.
        val f = 1080f / h
        w = (w * f).roundToInt()
        h = 1080
        scale = max(100, ((scale * f) / 25f).roundToInt() * 25)
    }
    val screen = when (s.resolution) {
        Resolution.HOST -> null
        else -> VirtualScreen(width = w, height = h, refreshHz = min(refreshHz, s.maxFps).coerceAtLeast(30), scalePercent = scale)
    }
    return StreamOptions(
        codec = s.codec,
        bitrateKbps = s.bitrateKbps,
        game = s.gameMode,
        videoTransport = s.videoTransport,
        virtualScreen = screen,
        physicalOff = s.physicalOff && screen != null,
        displayId = displayId,
        bitratePolicy = s.bitratePolicy,
        hdr = s.hdr && hdrCapable,
    )
}
