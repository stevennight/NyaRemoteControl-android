package app.nya.remote.session

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Host audio: Opus datagrams (48 kHz stereo) decoded by MediaCodec, then the
 * core's adaptive jitter buffer (the same one as the Windows client: 20–80 ms
 * depth following the measured jitter, clock drift absorbed by ±0.5 % speed,
 * short losses filled with silence) feeds a low-latency float AudioTrack.
 */
class AudioPlayer(private val session: RemoteSession) {
    @Volatile
    private var running = true
    private val worker = thread(start = false, name = "nya-audio") {
        try {
            run()
        } catch (e: Exception) {
            Log.w(TAG, "audio stopped", e)
        }
    }

    fun start() = worker.start()

    fun stop() {
        running = false
        worker.join(1000)
    }

    private fun opusHead(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray())
        put(1) // version
        put(CHANNELS.toByte())
        putShort(0) // pre-skip
        putInt(SAMPLE_RATE)
        putShort(0) // gain
        put(0) // channel mapping family
        flip()
    }

    private fun nanos(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }

    private fun run() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS).apply {
            setByteBuffer("csd-0", opusHead())
            setByteBuffer("csd-1", nanos(0))
            setByteBuffer("csd-2", nanos(80_000_000))
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, 0)
        codec.start()

        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAMES_PER_MS * DEVICE_TARGET_MS * 3 * CHANNELS * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val packet = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
        val info = MediaCodec.BufferInfo()
        // Packets in the decoder: presentation time (= sender time) -> sequence number.
        val inFlight = LinkedHashMap<Long, Int>()
        var pcm = ShortArray(0)
        val out = FloatArray(FRAMES_PER_MS * DEVICE_TARGET_MS * 2 * CHANNELS)
        var written = 0L // frames handed to the track
        try {
            while (running) {
                val n = session.nextAudio(packet, POLL_MS)
                if (n == -1) break
                if (n > HEADER) {
                    val seq = packet.getInt(1)
                    val ts = packet.getLong(5)
                    val idx = codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        packet.position(HEADER).limit(n)
                        ib.put(packet)
                        codec.queueInputBuffer(idx, 0, n - HEADER, ts, 0)
                        inFlight[ts] = seq
                        if (inFlight.size > 64) inFlight.clear()
                    }
                    packet.clear()
                }
                // Decoded packets go into the jitter buffer.
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val ob = codec.getOutputBuffer(o)
                    // Decoders that rewrite timestamps: outputs come in input order.
                    val seq = inFlight.remove(info.presentationTimeUs)
                        ?: inFlight.keys.firstOrNull()?.let { inFlight.remove(it) }
                    if (ob != null && info.size > 0 && seq != null) {
                        val samples = info.size / 2
                        if (pcm.size < samples) pcm = ShortArray(samples)
                        ob.position(info.offset)
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm, 0, samples)
                        session.audioPush(seq, info.presentationTimeUs, pcm, samples)
                    }
                    codec.releaseOutputBuffer(o, false)
                }
                // Keep about DEVICE_TARGET_MS in the device; the jitter buffer decides what that is.
                val queued = (written - (track.playbackHeadPosition.toLong() and 0xffffffffL)).coerceAtLeast(0).toInt()
                val want = FRAMES_PER_MS * DEVICE_TARGET_MS - queued
                if (want > 0) {
                    val frames = session.audioPull(want, queued, out)
                    if (frames > 0) {
                        val w = track.write(out, 0, frames * CHANNELS, AudioTrack.WRITE_NON_BLOCKING)
                        if (w > 0) written += w / CHANNELS
                    }
                }
            }
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
    }

    companion object {
        const val TAG = "NyaAudio"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val FRAMES_PER_MS = SAMPLE_RATE / 1000

        /** `u8 type, u32 seq, u64 capture_ts` before the Opus packet. */
        const val HEADER = 13

        /** What the device should hold (as on Windows). */
        const val DEVICE_TARGET_MS = 20
        const val POLL_MS = 3
    }
}
