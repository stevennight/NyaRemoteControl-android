package app.nya.remote.session

import android.media.MediaCodec
import android.media.MediaCodecInfo

import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import app.nya.remote.core.NativeCore
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Hardware decoding into the SurfaceView's surface. One thread feeds frames
 * from the core into MediaCodec; another renders each output at once (no
 * frame pacing: the newest picture is shown as soon as it is decoded).
 *
 * Lives as long as the surface; a new one asks for a keyframe.
 */
class VideoDecoder(
    private val session: RemoteSession,
    private val surface: Surface,
    private val decoders: List<Decoder>,
    /** Decoded size (on the decoder thread). */
    private val onVideoSize: (Int, Int) -> Unit,
) {
    @Volatile
    private var running = true
    private val input = thread(start = false, name = "nya-video-in") { feed() }

    /** The stream is HDR10 (from StreamStarted): the decoder is set up for BT.2020 / PQ. */
    @Volatile
    var hdr = false

    private var codec: MediaCodec? = null
    private var configuredHdr = false
    private var output: Thread? = null

    @Volatile
    private var outputRunning = false
    private var mime = ""
    private var width = 0
    private var height = 0

    private val queuedAt = ConcurrentHashMap<Long, Long>()
    private val decodeMs = FloatArray(256)

    @Volatile
    private var decodeN = 0

    @Volatile
    private var dropped = 0
    private var statsAt = System.nanoTime()

    fun start() = input.start()

    fun stop() {
        running = false
        input.join(2000)
    }

    private fun feed() {
        var buf = ByteBuffer.allocateDirect(4 shl 20)
        val meta = LongArray(7)
        session.requestKeyframe()
        try {
            while (running) {
                val n = session.nextVideo(buf, meta, 100)
                when {
                    n == 0 -> {
                        publishStats()
                        continue
                    }
                    n == -1 -> break
                    n == -2 -> {
                        buf = ByteBuffer.allocateDirect((meta[0] * 3 / 2).toInt())
                        continue
                    }
                }
                val key = meta[1] and NativeCore.FLAG_KEYFRAME != 0L
                val m = DecoderCaps.mimeFor(meta[6]) ?: continue
                val w = meta[4].toInt()
                val h = meta[5].toInt()
                if (codec == null || m != mime || w != width || h != height || configuredHdr != hdr) {
                    if (!key) {
                        session.requestKeyframe()
                        continue
                    }
                    release()
                    if (!configure(m, w, h)) {
                        session.requestKeyframe()
                        continue
                    }
                }
                queue(buf, n, meta[3], key)
                publishStats()
            }
        } finally {
            release()
        }
    }

    private fun queue(buf: ByteBuffer, n: Int, ptsUs: Long, key: Boolean) {
        val c = codec ?: return
        try {
            var idx = -1
            val deadline = System.nanoTime() + 40_000_000
            while (running && idx < 0 && System.nanoTime() < deadline) {
                idx = c.dequeueInputBuffer(10_000)
            }
            if (idx < 0) {
                // The decoder is stuck behind; this frame is lost, resync on a keyframe.
                dropped++
                session.requestKeyframe()
                return
            }
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            buf.position(0).limit(n)
            ib.put(buf)
            buf.clear()
            queuedAt[ptsUs] = System.nanoTime()
            c.queueInputBuffer(idx, 0, n, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        } catch (e: Exception) {
            Log.w(TAG, "decoder failed; waiting for a keyframe", e)
            release()
            session.requestKeyframe()
        }
    }

    private fun format(m: String, w: Int, h: Int, lowLatencyExtras: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(m, w, h).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            if (hdr && m == "video/hevc") {
                // HDR10: the surface gets BT.2020 PQ buffers; the system shows them in HDR.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            if (lowLatencyExtras) {
                // Vendor switches for decoders that hold frames back otherwise; unknown keys are ignored.
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                setInteger("vendor.low-latency.enable", 1)
                setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
            }
        }

    private fun create(m: String): MediaCodec {
        val name = decoders.firstOrNull { it.mime == m }?.name
        return if (!name.isNullOrEmpty()) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(m)
    }

    private fun configure(m: String, w: Int, h: Int): Boolean {
        for (extras in listOf(true, false)) {
            var c: MediaCodec? = null
            try {
                c = create(m)
                c.configure(format(m, w, h, extras), surface, null, 0)
                c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                c.start()
                codec = c
                configuredHdr = hdr
                mime = m
                width = w
                height = h
                Log.i(TAG, "decoding $m ${w}x$h with ${c.name}${if (extras) "" else " (no vendor low-latency keys)"}")
                onVideoSize(w, h)
                startOutput(c)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "configure $m ${w}x$h (extras=$extras) failed", e)
                try {
                    c?.release()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun startOutput(c: MediaCodec) {
        outputRunning = true
        output = thread(name = "nya-video-out") {
            val info = MediaCodec.BufferInfo()
            while (outputRunning) {
                val idx = try {
                    c.dequeueOutputBuffer(info, 20_000)
                } catch (_: Exception) {
                    break
                }
                if (idx >= 0) {
                    queuedAt.remove(info.presentationTimeUs)?.let { t0 ->
                        val n = decodeN
                        decodeMs[n % decodeMs.size] = (System.nanoTime() - t0) / 1e6f
                        decodeN = n + 1
                    }
                    try {
                        c.releaseOutputBuffer(idx, true)
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
    }

    private fun release() {
        outputRunning = false
        output?.join(500)
        output = null
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        queuedAt.clear()
    }

    /** Median decode time and drops of the last second, for ClientStats and the overlay. */
    private fun publishStats() {
        val now = System.nanoTime()
        if (now - statsAt < 1_000_000_000) return
        statsAt = now
        val n = decodeN.coerceAtMost(decodeMs.size)
        val median = if (n == 0) 0f else decodeMs.copyOf(n).sorted()[n / 2]
        decodeN = 0
        session.setDecodeStats(median, dropped)
        dropped = 0
    }

    companion object {
        const val TAG = "NyaVideo"
    }
}
