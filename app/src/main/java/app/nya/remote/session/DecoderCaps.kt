package app.nya.remote.session

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import app.nya.remote.core.DecoderCap

/** A video decoder on this phone. */
data class Decoder(
    val codec: String,
    val mime: String,
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    /** HEVC Main10: HDR10 possible. */
    val tenBit: Boolean = false,
) {
    fun cap() = DecoderCap(codec, hardware, maxWidth, maxHeight)

    /** The 10-bit variant, reported in addition (protocol: CodecCap.ten_bit). */
    fun tenBitCap() = DecoderCap(codec, hardware, maxWidth, maxHeight, tenBit = true)
}

object DecoderCaps {
    private val mimes = listOf("h264" to "video/avc", "hevc" to "video/hevc", "av1" to "video/av01")

    fun mimeFor(codec: Long): String? = when (codec) {
        app.nya.remote.core.NativeCore.CODEC_H264 -> "video/avc"
        app.nya.remote.core.NativeCore.CODEC_HEVC -> "video/hevc"
        app.nya.remote.core.NativeCore.CODEC_AV1 -> "video/av01"
        else -> null
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("ffmpeg") || n.contains(".sw."))
    }

    /**
     * The decoder per codec: hardware where there is one (unless [hardware] is
     * off). Software decoders are offered only for H.264 when hardware is
     * wanted and nothing else exists (emulators): software HEVC / AV1 is too
     * slow on most phones. With [hardware] off, software decoders are used
     * for every codec that has one (for phones whose hardware decoder misbehaves).
     */
    fun detect(hardware: Boolean = true): List<Decoder> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val out = mutableListOf<Decoder>()
        for ((codec, mime) in mimes) {
            val candidates = infos.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            val best = if (hardware) {
                candidates.firstOrNull { isHardware(it) } ?: candidates.firstOrNull()
            } else {
                candidates.firstOrNull { !isHardware(it) }
            } ?: continue
            val hw = isHardware(best)
            if (hardware && !hw && codec != "h264") continue
            val caps = try {
                best.getCapabilitiesForType(mime)
            } catch (_: Exception) {
                null
            }
            val vc = caps?.videoCapabilities
            val main10 = mime == "video/hevc" && caps?.profileLevels?.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            } == true
            out += Decoder(codec, mime, best.name, hw, vc?.supportedWidths?.upper ?: 0, vc?.supportedHeights?.upper ?: 0, main10 && hw)
        }
        if (out.isEmpty()) {
            // Nothing found at all: let MediaCodec pick at decode time.
            out += Decoder("h264", "video/avc", "", false, 0, 0)
        }
        return out
    }

    /** One line for the about page, e.g. "硬件解码：HEVC（10 bit）· H.264 · AV1". */
    fun summary(decoders: List<Decoder>): String {
        val hw = decoders.filter { it.hardware && it.name.isNotEmpty() }
        if (hw.isEmpty()) return "没有可用的硬件解码器，使用软件解码（画面可能卡顿）"
        val order = listOf("hevc", "h264", "av1")
        val names = hw.sortedBy { order.indexOf(it.codec) }.joinToString(" · ") { d ->
            label(d.codec) + (if (d.tenBit) "（10 bit）" else "") + (if (d.maxWidth > 0) " 最大 ${d.maxWidth}×${d.maxHeight}" else "")
        }
        return "硬件解码：$names"
    }

    fun label(codec: String) = when (codec) {
        "h264" -> "H.264"
        "hevc" -> "HEVC"
        "av1" -> "AV1"
        else -> codec
    }
}
