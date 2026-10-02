package app.nya.remote.session

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.nya.remote.core.CoreEvent
import app.nya.remote.core.NativeCore
import app.nya.remote.core.StartConfig
import app.nya.remote.core.StreamOptions
import app.nya.remote.core.coreJson
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

/**
 * One native session. Every call holds a read lock on the handle, [close]
 * takes the write lock to free it, so no call can race the release.
 * Events are delivered on the main thread.
 */
class RemoteSession(dataDir: String, config: StartConfig, private val onEvent: (CoreEvent) -> Unit) {
    private val lock = ReentrantReadWriteLock(true)
    private var handle: Long = NativeCore.start(dataDir, coreJson.encodeToString(StartConfig.serializer(), config))
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    private val events = thread(name = "nya-events") {
        while (!closed) {
            val json = onHandle { NativeCore.pollEvent(it, 200) } ?: continue
            val e = try {
                CoreEvent.parse(json)
            } catch (ex: Exception) {
                Log.w(TAG, "bad event $json", ex)
                null
            } ?: continue
            main.post { if (!closed) onEvent(e) }
        }
    }

    private inline fun <T> onHandle(f: (Long) -> T): T? = lock.read { if (handle != 0L) f(handle) else null }

    /** See [NativeCore.nextVideo]; -1 once closed. */
    fun nextVideo(buf: ByteBuffer, meta: LongArray, timeoutMs: Int): Int =
        onHandle { NativeCore.nextVideo(it, buf, meta, timeoutMs) } ?: -1

    fun nextAudio(buf: ByteBuffer, timeoutMs: Int): Int = onHandle { NativeCore.nextAudio(it, buf, timeoutMs) } ?: -1

    fun providePairCode(code: String?) = onHandle { NativeCore.providePairCode(it, code) }

    /** rx, ry: 0..1 across the remote display. */
    fun mouseTo(rx: Float, ry: Float) = onHandle {
        NativeCore.mouseAbs(it, (rx.coerceIn(0f, 1f) * 65535).toInt(), (ry.coerceIn(0f, 1f) * 65535).toInt())
    }

    fun mouseButton(button: Int, down: Boolean) = onHandle { NativeCore.mouseButton(it, button, down) }
    fun wheel(dx: Int, dy: Int) = onHandle { NativeCore.wheel(it, dx, dy) }
    fun key(scancode: Int, extended: Boolean, down: Boolean) = onHandle { NativeCore.key(it, scancode, extended, down) }
    fun releaseAll() = onHandle { NativeCore.releaseAll(it) }
    fun updateStream(o: StreamOptions) = onHandle { NativeCore.updateStream(it, coreJson.encodeToString(StreamOptions.serializer(), o)) }
    fun setGameMode(game: Boolean) = onHandle { NativeCore.setMode(it, game) }
    fun sendSas() = onHandle { NativeCore.sendSas(it) }
    fun requestKeyframe() = onHandle { NativeCore.requestKeyframe(it) }
    fun takeControl(kick: Boolean) = onHandle { NativeCore.takeControl(it, kick) }
    fun sendClipboard(text: String) = onHandle { NativeCore.clipboardText(it, text) }
    fun setDecodeStats(decodeMs: Float, dropped: Int) = onHandle { NativeCore.setDecodeStats(it, decodeMs, dropped) }

    /** Disconnect politely; a Disconnected event follows. */
    fun stop() = onHandle { NativeCore.stop(it) }

    /** Disconnect and free the native session (blocks briefly; call off the main thread if possible). */
    fun close() {
        if (closed) return
        closed = true
        onHandle { NativeCore.stop(it) }
        events.join(1000)
        lock.write {
            if (handle != 0L) {
                NativeCore.free(handle)
                handle = 0L
            }
        }
    }

    companion object {
        const val TAG = "NyaSession"
    }
}
