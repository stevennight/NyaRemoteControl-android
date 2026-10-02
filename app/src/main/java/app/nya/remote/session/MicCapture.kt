package app.nya.remote.session

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * The phone's microphone to the host (FEATURE_MICROPHONE): 48 kHz mono
 * capture, duplicated to stereo, Opus by MediaCodec (Android 10+), one MIC
 * datagram per packet. The host plays it into its virtual audio cable.
 */
class MicCapture(private val session: RemoteSession, private val onError: (String) -> Unit) {
    @Volatile
    private var running = true
    private val worker = thread(start = false, name = "nya-mic") {
        try {
            run()
        } catch (e: Exception) {
            Log.w(TAG, "microphone stopped", e)
            onError("麦克风出错：${e.message}")
        }
    }

    fun start() = worker.start()

    fun stop() {
        running = false
        worker.join(1000)
    }

    @SuppressLint("MissingPermission") // asked for before starting
    private fun run() {
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        } catch (_: Exception) {
            onError("这台手机没有 Opus 编码器（需要 Android 10 以上），无法使用麦克风")
            return
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME * 2 * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            codec.release()
            onError("无法打开麦克风")
            return
        }
        rec.startRecording()
        val mono = ShortArray(FRAME)
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        try {
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = rec.read(mono, got, FRAME - got)
                    if (n < 0) throw IllegalStateException("AudioRecord.read $n")
                    got += n
                }
                val idx = codec.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    val ib = codec.getInputBuffer(idx)!!
                    ib.clear()
                    val sb = ib.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (s in mono) {
                        sb.put(s)
                        sb.put(s)
                    }
                    codec.queueInputBuffer(idx, 0, FRAME * 4, pts, 0)
                    pts += FRAME * 1_000_000L / RATE
                }
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        val packet = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(packet)
                        session.mic(packet)
                    }
                    codec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            rec.stop()
            rec.release()
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
    }

    companion object {
        const val TAG = "NyaMic"
        const val RATE = 48_000

        /** 20 ms. */
        const val FRAME = 960
    }
}
