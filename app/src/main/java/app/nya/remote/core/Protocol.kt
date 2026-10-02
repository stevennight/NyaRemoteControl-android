package app.nya.remote.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// JSON exchanged with the Rust core (rust/src/options.rs, rust/src/events.rs).

val coreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class DecoderCap(val codec: String, val hardware: Boolean, val maxWidth: Int = 0, val maxHeight: Int = 0)

@Serializable
data class VirtualScreen(val width: Int, val height: Int, val refreshHz: Int = 60, val scalePercent: Int = 0)

@Serializable
data class StreamOptions(
    val codec: String = "auto",
    val bitrateKbps: Int = 0,
    val game: Boolean = false,
    val videoTransport: String = "auto",
    val virtualScreen: VirtualScreen? = null,
    val physicalOff: Boolean = false,
)

@Serializable
data class StartConfig(
    val address: String,
    val pinned: String? = null,
    val pairCode: String? = null,
    val clientName: String,
    val clientVersion: String,
    val decoders: List<DecoderCap>,
    val maxFps: Int,
    val stream: StreamOptions,
)

data class StatsLine(
    val fps: Int,
    val mbps: Float,
    val rttMs: Float,
    val latencyMs: Float,
    val decodeMs: Float,
    val serverFps: Int,
    val encodeMs: Float,
    val targetKbps: Int,
    val fecPercent: Int,
    val framesLost: Int,
    val framesDropped: Int,
)

sealed interface CoreEvent {
    data object Connecting : CoreEvent
    data object NeedPairing : CoreEvent
    data class Connected(val serverName: String, val serverVersion: String, val fingerprint: String, val fingerprintShort: String) : CoreEvent
    data class Reconnecting(val message: String) : CoreEvent
    data class Disconnected(val message: String) : CoreEvent
    data class SessionInfo(val hostName: String, val virtualDisplayAvailable: Boolean) : CoreEvent
    data class StreamStarted(
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val fps: Int,
        val codec: String,
        val encoder: String,
    ) : CoreEvent
    data class StreamError(val message: String) : CoreEvent
    data class Role(val controlling: Boolean, val controller: String, val viewers: List<String>) : CoreEvent
    class CursorShape(val id: Int, val width: Int, val height: Int, val hotX: Int, val hotY: Int, val rgbaBase64: String) : CoreEvent
    data class CursorState(val shapeId: Int, val visible: Boolean, val x: Int, val y: Int) : CoreEvent
    data class Clipboard(val text: String) : CoreEvent
    data class Stats(val line: StatsLine) : CoreEvent

    companion object {
        /** Null for event types this build doesn't know. */
        fun parse(json: String): CoreEvent? {
            val o = coreJson.parseToJsonElement(json).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            fun i(k: String) = o[k]?.jsonPrimitive?.int ?: 0
            fun f(k: String) = o[k]?.jsonPrimitive?.float ?: 0f
            fun b(k: String) = o[k]?.jsonPrimitive?.boolean ?: false
            return when (s("type")) {
                "connecting" -> Connecting
                "needPairing" -> NeedPairing
                "connected" -> Connected(s("serverName"), s("serverVersion"), s("serverFingerprint"), s("serverFingerprintShort"))
                "reconnecting" -> Reconnecting(s("message"))
                "disconnected" -> Disconnected(s("message"))
                "sessionInfo" -> SessionInfo(s("hostName"), b("virtualDisplayAvailable"))
                "streamStarted" -> StreamStarted(i("width"), i("height"), i("sourceWidth"), i("sourceHeight"), i("fps"), s("codec"), s("encoder"))
                "streamError" -> StreamError(s("message"))
                "role" -> Role(b("controlling"), s("controller"), o["viewers"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
                "cursorShape" -> CursorShape(i("id"), i("width"), i("height"), i("hotX"), i("hotY"), s("rgba"))
                "cursorState" -> CursorState(i("shapeId"), b("visible"), i("x"), i("y"))
                "clipboard" -> Clipboard(s("text"))
                "stats" -> Stats(
                    StatsLine(
                        i("fps"), f("mbps"), f("rttMs"), f("latencyMs"), f("decodeMs"), i("serverFps"),
                        f("encodeMs"), i("targetKbps"), i("fecPercent"), i("framesLost"), i("framesDropped"),
                    ),
                )
                else -> null
            }
        }
    }
}
