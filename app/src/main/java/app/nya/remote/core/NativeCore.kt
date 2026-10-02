package app.nya.remote.core

import java.nio.ByteBuffer

/**
 * JNI surface of the Rust core (`rust/src/lib.rs`). A handle is one session;
 * [free] must be the last call on it, after every thread using it stopped.
 */
object NativeCore {
    init {
        System.loadLibrary("nya_android")
    }

    /** Starts connecting at once; progress arrives as events. Throws on bad config. */
    @JvmStatic external fun start(dataDir: String, configJson: String): Long

    /** Next event as JSON, or null after [timeoutMs]. */
    @JvmStatic external fun pollEvent(handle: Long, timeoutMs: Int): String?

    /**
     * Copies the next frame's payload into [buf] (direct) and fills [meta] with
     * `length, flags, frameId, captureTsUs, width, height, codec`.
     * Returns the length; 0 on timeout; -1 when closed; -2 when [buf] is too small (meta[0] = needed).
     */
    @JvmStatic external fun nextVideo(handle: Long, buf: ByteBuffer, meta: LongArray, timeoutMs: Int): Int

    /** Next audio datagram into [buf] (direct): `u8 type, u32 seq, u64 ts, Opus`. */
    @JvmStatic external fun nextAudio(handle: Long, buf: ByteBuffer, timeoutMs: Int): Int

    /** Answer to a `needPairing` event; null cancels. */
    @JvmStatic external fun providePairCode(handle: Long, code: String?)

    @JvmStatic external fun mouseAbs(handle: Long, x: Int, y: Int)
    @JvmStatic external fun mouseRel(handle: Long, dx: Int, dy: Int)
    @JvmStatic external fun mouseButton(handle: Long, button: Int, down: Boolean)
    @JvmStatic external fun wheel(handle: Long, dx: Int, dy: Int)
    @JvmStatic external fun key(handle: Long, scancode: Int, extended: Boolean, down: Boolean)
    @JvmStatic external fun releaseAll(handle: Long)

    /** Text typed as Unicode on the host (only when the host offers text input). */
    @JvmStatic external fun text(handle: Long, text: String)

    /** XInput state of pad [index] (0..3): buttons are XINPUT_GAMEPAD_* bits, triggers 0..255, sticks -32768..32767. */
    @JvmStatic external fun gamepad(
        handle: Long, index: Int, connected: Boolean, buttons: Int,
        leftTrigger: Int, rightTrigger: Int, lx: Int, ly: Int, rx: Int, ry: Int,
    )

    /** A decoded audio packet: [samples] 16-bit values, interleaved stereo. False if late / duplicate. */
    @JvmStatic external fun audioPush(handle: Long, seq: Int, senderUs: Long, pcm: ShortArray, samples: Int): Boolean

    /** Up to [maxFrames] frames of interleaved float stereo into [out]; returns frames written. */
    @JvmStatic external fun audioPull(handle: Long, maxFrames: Int, deviceQueued: Int, out: FloatArray): Int

    /** Download the files of a fileOffer event. */
    @JvmStatic external fun requestFiles(handle: Long, offerId: String)

    /** `[{"fd":..,"name":..,"size":..}]`; the core takes ownership of the (detached) descriptors. */
    @JvmStatic external fun sendFiles(handle: Long, json: String)

    @JvmStatic external fun updateStream(handle: Long, streamJson: String)
    @JvmStatic external fun setMode(handle: Long, game: Boolean)
    @JvmStatic external fun sendSas(handle: Long)
    @JvmStatic external fun requestKeyframe(handle: Long)
    @JvmStatic external fun takeControl(handle: Long, kick: Boolean)
    @JvmStatic external fun clipboardText(handle: Long, text: String)
    @JvmStatic external fun setDecodeStats(handle: Long, decodeMs: Float, dropped: Int)

    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun free(handle: Long)

    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2
    const val BUTTON_MIDDLE = 3

    const val FLAG_KEYFRAME = 1L
    const val CODEC_H264 = 1L
    const val CODEC_HEVC = 2L
    const val CODEC_AV1 = 3L
}
