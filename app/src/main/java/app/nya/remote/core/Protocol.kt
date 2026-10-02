package app.nya.remote.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// JSON exchanged with the Rust core (rust/src/options.rs, rust/src/events.rs).

val coreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class DecoderCap(val codec: String, val hardware: Boolean, val maxWidth: Int = 0, val maxHeight: Int = 0, val tenBit: Boolean = false)

@Serializable
data class ShareConfig(val name: String, val path: String, val readOnly: Boolean = false)

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
    /** Host display to show; 0 = primary (the virtual screen while there is one). */
    val displayId: Int = 0,
    /** "auto" / "quality" / "balanced" / "smooth" / "fixed". */
    val bitratePolicy: String = "auto",
    /** Ask for HDR10 (the phone's screen shows HDR). */
    val hdr: Boolean = false,
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
    val downloadDir: String? = null,
    val shares: List<ShareConfig> = emptyList(),
)

data class HostDisplay(val id: Int, val name: String, val width: Int, val height: Int, val primary: Boolean, val isVirtual: Boolean)

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
    val audioMs: Float = 0f,
    val audioTargetMs: Float = 0f,
    val audioUnderruns: Int = 0,
)

data class OfferedFile(val name: String, val size: Long, val isDir: Boolean)

sealed interface CoreEvent {
    data object Connecting : CoreEvent
    data object NeedPairing : CoreEvent
    data class Connected(
        val serverName: String,
        val serverVersion: String,
        val fingerprint: String,
        val fingerprintShort: String,
        val textInput: Boolean,
        val fileTransfer: Boolean,
        val gamepad: Boolean,
        val clipboardImage: Boolean = false,
        val clipboardFiles: Boolean = false,
        val microphone: Boolean = false,
        val folderMount: Boolean = false,
        val print: Boolean = false,
        val usb: Boolean = false,
        val hdr: Boolean = false,
    ) : CoreEvent
    data class Reconnecting(val message: String) : CoreEvent
    data class Disconnected(val message: String) : CoreEvent
    data class SessionInfo(
        val hostName: String,
        val virtualDisplayAvailable: Boolean,
        val displays: List<HostDisplay> = emptyList(),
        /** Host playback device for the microphone; empty = no virtual cable installed. */
        val micDevice: String = "",
        val usbAvailable: Boolean = false,
    ) : CoreEvent
    data class StreamStarted(
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val fps: Int,
        val codec: String,
        val encoder: String,
        val hdr: Boolean = false,
    ) : CoreEvent
    data class StreamError(val message: String) : CoreEvent
    data class Role(val controlling: Boolean, val controller: String, val viewers: List<String>) : CoreEvent
    class CursorShape(val id: Int, val width: Int, val height: Int, val hotX: Int, val hotY: Int, val rgbaBase64: String) : CoreEvent
    data class CursorState(val shapeId: Int, val visible: Boolean, val x: Int, val y: Int) : CoreEvent
    data class Clipboard(val text: String) : CoreEvent
    data class Stats(val line: StatsLine) : CoreEvent
    data class FileOffer(val id: String, val files: List<OfferedFile>, val totalBytes: Long) : CoreEvent
    data class Transfer(
        val id: String,
        val upload: Boolean,
        val name: String,
        val done: Long,
        val total: Long,
        val finished: Boolean,
        val ok: Boolean,
        val message: String,
    ) : CoreEvent
    data class FilesReceived(val id: String, val paths: List<String>) : CoreEvent
    data class Rumble(val index: Int, val large: Int, val small: Int) : CoreEvent
    data class ClipboardImage(val path: String) : CoreEvent
    data class PrintJob(val path: String) : CoreEvent
    data class FolderMount(val mounted: Boolean, val mountPoint: String, val message: String) : CoreEvent
    data class UsbStatus(val busid: String, val attached: Boolean, val message: String) : CoreEvent

    companion object {
        /** Null for event types this build doesn't know. */
        fun parse(json: String): CoreEvent? {
            val o = coreJson.parseToJsonElement(json).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            fun i(k: String) = o[k]?.jsonPrimitive?.int ?: 0
            fun f(k: String) = o[k]?.jsonPrimitive?.float ?: 0f
            fun b(k: String) = o[k]?.jsonPrimitive?.boolean ?: false
            fun l(k: String) = o[k]?.jsonPrimitive?.long ?: 0L
            return when (s("type")) {
                "connecting" -> Connecting
                "needPairing" -> NeedPairing
                "connected" -> Connected(
                    s("serverName"), s("serverVersion"), s("serverFingerprint"), s("serverFingerprintShort"),
                    b("textInput"), b("fileTransfer"), b("gamepad"),
                    b("clipboardImage"), b("clipboardFiles"), b("microphone"), b("folderMount"), b("print"), b("usb"), b("hdr"),
                )
                "reconnecting" -> Reconnecting(s("message"))
                "disconnected" -> Disconnected(s("message"))
                "sessionInfo" -> SessionInfo(
                    s("hostName"),
                    b("virtualDisplayAvailable"),
                    o["displays"]?.jsonArray?.map {
                        val d = it.jsonObject
                        fun ds(k: String) = d[k]?.jsonPrimitive?.content ?: ""
                        fun di(k: String) = d[k]?.jsonPrimitive?.int ?: 0
                        fun db(k: String) = d[k]?.jsonPrimitive?.boolean ?: false
                        HostDisplay(di("id"), ds("name"), di("width"), di("height"), db("primary"), db("isVirtual"))
                    } ?: emptyList(),
                    s("micDevice"),
                    b("usbAvailable"),
                )
                "streamStarted" -> StreamStarted(i("width"), i("height"), i("sourceWidth"), i("sourceHeight"), i("fps"), s("codec"), s("encoder"), b("hdr"))

                "streamError" -> StreamError(s("message"))
                "role" -> Role(b("controlling"), s("controller"), o["viewers"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
                "cursorShape" -> CursorShape(i("id"), i("width"), i("height"), i("hotX"), i("hotY"), s("rgba"))
                "cursorState" -> CursorState(i("shapeId"), b("visible"), i("x"), i("y"))
                "clipboard" -> Clipboard(s("text"))
                "stats" -> Stats(
                    StatsLine(
                        i("fps"), f("mbps"), f("rttMs"), f("latencyMs"), f("decodeMs"), i("serverFps"),
                        f("encodeMs"), i("targetKbps"), i("fecPercent"), i("framesLost"), i("framesDropped"),
                        f("audioMs"), f("audioTargetMs"), i("audioUnderruns"),
                    ),
                )
                "fileOffer" -> FileOffer(
                    s("id"),
                    o["files"]?.jsonArray?.map {
                        val f = it.jsonObject
                        OfferedFile(
                            f["name"]?.jsonPrimitive?.content ?: "",
                            f["size"]?.jsonPrimitive?.long ?: 0L,
                            f["isDir"]?.jsonPrimitive?.boolean ?: false,
                        )
                    } ?: emptyList(),
                    l("totalBytes"),
                )
                "transfer" -> Transfer(s("id"), b("upload"), s("name"), l("done"), l("total"), b("finished"), b("ok"), s("message"))
                "filesReceived" -> FilesReceived(s("id"), o["paths"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
                "rumble" -> Rumble(i("index"), i("large"), i("small"))
                "clipboardImage" -> ClipboardImage(s("path"))
                "printJob" -> PrintJob(s("path"))
                "folderMount" -> FolderMount(b("mounted"), s("mountPoint"), s("message"))
                "usbStatus" -> UsbStatus(s("busid"), b("attached"), s("message"))

                else -> null
            }
        }
    }
}
