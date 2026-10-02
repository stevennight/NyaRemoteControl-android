package app.nya.remote.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Process
import android.view.Display
import androidx.core.content.FileProvider
import app.nya.remote.BuildConfig
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The about page's diagnostics (the Windows client's `diag::report`): what
 * this phone can decode and play, its screen and network, for bug reports.
 * Also the app's own log, to share.
 */
object Diagnostics {
    /** Blocking (MediaCodecList can take a moment): call off the main thread. */
    fun report(context: Context): String = buildString {
        appendLine("NyaRemoteControl Android ${BuildConfig.VERSION_NAME}")
        appendLine("设备       ${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）")
        appendLine("系统       Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()

        appendLine("[屏幕]")
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        for (d in dm.displays) {
            val mode = d.mode
            val rates = d.supportedModes.map { it.refreshRate.toInt() }.distinct().sorted().joinToString("/")
            @Suppress("DEPRECATION")
            val hdr = d.hdrCapabilities?.supportedHdrTypes?.joinToString { hdrName(it) }.orEmpty().ifBlank { "无" }
            appendLine("  #${d.displayId} ${mode.physicalWidth}×${mode.physicalHeight} @ ${"%.0f".format(mode.refreshRate)} Hz（支持 $rates Hz）HDR：$hdr")
        }
        appendLine()

        appendLine("[视频解码器]")
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        for ((label, mime) in listOf("H.264" to "video/avc", "HEVC" to "video/hevc", "AV1" to "video/av01")) {
            val list = infos.filter { i -> i.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            if (list.isEmpty()) {
                appendLine("  $label：无")
                continue
            }
            for (i in list) {
                val caps = runCatching { i.getCapabilitiesForType(mime) }.getOrNull()
                val vc = caps?.videoCapabilities
                val size = vc?.let { " 最大 ${it.supportedWidths.upper}×${it.supportedHeights.upper}" }.orEmpty()
                val tenBit = caps?.profileLevels?.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                } == true
                val kind = if (Build.VERSION.SDK_INT >= 29) {
                    if (i.isHardwareAccelerated) "硬件" else "软件"
                } else {
                    "?"
                }
                val lowLatency = Build.VERSION.SDK_INT >= 30 && caps?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) == true
                appendLine("  $label  ${i.name}  $kind$size${if (tenBit) " · 10 bit" else ""}${if (lowLatency) " · 低延迟" else ""}")
            }
        }
        appendLine()

        appendLine("[声音]")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "?"
        val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "?"
        val minBuf = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        appendLine("  输出 $rate Hz，每块 $frames 帧；48 kHz 立体声最小缓冲 ${if (minBuf > 0) minBuf / 8 else minBuf} 帧")
        val mic = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val micPerm = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        appendLine("  麦克风 ${if (mic) "有" else "无"}，权限 ${if (micPerm) "已授予" else "未授予（打开麦克风时会询问）"}")
        appendLine()

        appendLine("[其他]")
        appendLine("  文件访问（共享文件夹）：${if (Shares.accessGranted(context)) "已授予" else "未授予"}")
        appendLine("  USB 主机（OTG 设备共享）：${if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) "支持" else "不支持"}")
        appendLine()

        appendLine("[网络接口]")
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .forEach { n ->
                    val v4 = n.inetAddresses.toList().filterIsInstance<Inet4Address>().joinToString { it.hostAddress.orEmpty() }
                    if (v4.isNotEmpty()) appendLine("  ${n.name}  $v4")
                }
        }
    }

    private fun hdrName(t: Int) = when (t) {
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "#$t"
    }

    /** This app's log (its own process only) as a file in the cache. Blocking. */
    fun logFile(context: Context): File {
        val text = try {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        } catch (e: Exception) {
            "无法读取日志：${e.message}"
        }
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return File(dir, "NyaRemoteControl-Android-$stamp.log").apply { writeText(text) }
    }

    /** The system share sheet for [file]. */
    fun share(context: Context, file: File, mime: String = "text/plain") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "分享日志"))
    }
}
