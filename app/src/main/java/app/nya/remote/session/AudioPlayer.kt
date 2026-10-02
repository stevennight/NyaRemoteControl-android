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
 * Host audio: Opus datagrams (48 kHz stereo) decoded by MediaCodec, played by
 * a low-latency AudioTrack. Keeps the device queue short: when more than
 * [MAX_QUEUED_MS] is waiting (a burst after a network stall), audio is dropped
 * rather than played late. Lost packets are not concealed.
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

        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
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
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, BYTES_PER_MS * 2 * MAX_QUEUED_MS))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val packet = ByteBuffer.allocateDirect(4096)
        val info = MediaCodec.BufferInfo()
        var pcm = ByteArray(0)
        var written = 0L // frames
        var pts = 0L
        try {
            while (running) {
                val n = session.nextAudio(packet, 100)
                if (n == -1) break
                if (n > HEADER) {
                    val idx = codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        packet.position(HEADER).limit(n)
                        ib.put(packet)
                        packet.clear()
                        codec.queueInputBuffer(idx, 0, n - HEADER, pts, 0)
                        pts += 10_000
                    }
                }
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val ob = codec.getOutputBuffer(o)
                    if (ob != null && info.size > 0) {
                        val queued = written - track.playbackHeadPosition.toLong()
                        if (queued * 1000 / SAMPLE_RATE <= MAX_QUEUED_MS) {
                            if (pcm.size < info.size) pcm = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(pcm, 0, info.size)
                            val w = track.write(pcm, 0, info.size, AudioTrack.WRITE_NON_BLOCKING)
                            if (w > 0) written += w / (2 * CHANNELS)
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
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
        const val BYTES_PER_MS = SAMPLE_RATE / 1000 * CHANNELS * 2

        /** `u8 type, u32 seq, u64 capture_ts` before the Opus packet. */
        const val HEADER = 13
        const val MAX_QUEUED_MS = 120
    }
}
