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
     * The best decoder per codec: hardware where there is one. Software
     * decoders are offered only for H.264 and only when nothing else exists
     * (emulators); software HEVC / AV1 is too slow on a phone.
     */
    fun detect(): List<Decoder> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val out = mutableListOf<Decoder>()
        for ((codec, mime) in mimes) {
            val candidates = infos.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            val best = candidates.firstOrNull { isHardware(it) } ?: candidates.firstOrNull() ?: continue
            val hw = isHardware(best)
            if (!hw && codec != "h264") continue
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
}
