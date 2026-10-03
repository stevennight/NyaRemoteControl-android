package app.nya.remote.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import app.nya.remote.core.ShareConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ControlMode { TOUCH, MOUSE }

/**
 * Connection settings: the defaults, or one host's own settings. The same
 * choices as the Windows client's `Defaults` (windows/app/src/config.rs),
 * minus what a phone has no use for (windows, full screen, keyboard capture,
 * 4:4:4), plus the phone's own (touch / mouse operation, keyboard kind).
 */
@Serializable
data class ConnSettings(
    /** "office" | "game". */
    val mode: String = "office",
    /** Host display to show; 0 = primary (the first virtual screen while there is one). */
    val display: Int = 0,
    /** 0 = the host decides. */
    val bitrateKbps: Int = 0,
    /** The top of the encoder's range (ignores [bitrateKbps]). */
    val unlimitedBitrate: Boolean = false,
    /** "auto" | "quality" | "balanced" | "smooth" | "fixed". */
    val bitratePolicy: String = "auto",
    /** "auto" | "stream" | "datagram". */
    val videoTransport: String = "auto",
    /** How the session travels: "auto" (UDP; TCP when UDP does not connect or loses too much) | "udp" | "tcp" (QUIC over TCP). */
    val transport: String = "auto",
    /** 0 = the screen's refresh rate. */
    val maxFps: Int = 60,
    /** Host encoder: "auto" | "nvenc" | "qsv" | "amf" | "software". */
    val encoder: String = "auto",
    /** "auto" | "h264" | "hevc" | "av1". */
    val codec: String = "auto",
    val audio: Boolean = true,
    /** Host clipboard (text, images, files) to the phone. */
    val clipboard: Boolean = true,
    /** Hardware decoders where the phone has them (otherwise software). */
    val hwDecode: Boolean = true,
    /** Virtual screens to create on the host (0 = none, up to 4). */
    val vdCount: Int = 1,
    /** Switch the host's physical displays off (with at least one virtual screen). */
    val physicalOff: Boolean = false,
    /** Block the host's own keyboard and mouse. */
    val blockInput: Boolean = false,
    /** Virtual screen size: "screen" (the phone's) | "screen1080" (same shape, at most 1080 lines) | "fixed". */
    val vdSize: String = "screen",
    val vdWidth: Int = 1920,
    val vdHeight: Int = 1080,
    /** Windows display scaling of the virtual screens in percent; 0 = leave as is. */
    val vdScale: Int = 150,
    /** Microphone on after connecting. */
    val mic: Boolean = false,
    /** Print jobs from the host: "ask" | "print" | "open" | "save". */
    val printMode: String = "ask",
    /** HDR10 when the phone's screen, its decoder and the host can. */
    val hdr: Boolean = true,
    val controlMode: ControlMode = ControlMode.TOUCH,
    /** The PC-layout on-screen keyboard instead of the phone's input method. */
    val pcKeyboard: Boolean = false,
    /** Phone folders shown on the host as a drive. */
    val sharedFolders: List<ShareConfig> = emptyList(),
) {
    val game: Boolean get() = mode == "game"

    /** (virtual screens, physical displays off, local input blocked). */
    val displayChoice: DisplayChoice get() = DisplayChoice(vdCount, physicalOff && vdCount > 0, blockInput)

    fun withDisplayChoice(c: DisplayChoice) = copy(
        vdCount = c.count.coerceIn(0, 4),
        physicalOff = c.physicalOff && c.count > 0,
        blockInput = c.blockInput,
        // The first virtual screen is the host's primary display while it exists.
        display = if (c.count != vdCount) 0 else display,
    )
}

data class DisplayChoice(val count: Int = 0, val physicalOff: Boolean = false, val blockInput: Boolean = false) {
    companion object {
        val ASIS = DisplayChoice()
        fun privacy(count: Int) = DisplayChoice(count.coerceAtLeast(1), physicalOff = true, blockInput = true)
    }
}

/** App-wide settings (not per host). */
@Serializable
data class AppConfig(
    /** This phone's name as hosts show it; empty = the model name. */
    val clientName: String = "",
    /** Look for a new version when the app starts (installing is the user's choice). */
    val checkUpdates: Boolean = true,
    val showGuideOnConnect: Boolean = true,
    val showStats: Boolean = false,
    val defaults: ConnSettings = ConnSettings(),
) {
    /** The name hosts see. */
    val effectiveClientName: String get() = clientName.trim().ifBlank { deviceName() }

    companion object {
        fun deviceName(): String {
            val maker = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().ifBlank { "Android" }
        }
    }
}

class ConfigStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppConfig {
        prefs.getString(KEY, null)?.let { text ->
            try {
                return json.decodeFromString<AppConfig>(text)
            } catch (_: Exception) {
            }
        }
        return migrate(prefs)
    }

    fun save(c: AppConfig) = prefs.edit { putString(KEY, json.encodeToString(c)) }

    fun update(f: (AppConfig) -> AppConfig): AppConfig = f(load()).also { save(it) }

    companion object {
        private const val KEY = "config"

        /** Settings of 0.1.x (one flat set of preferences, no per-host settings). */
        fun migrate(p: SharedPreferences): AppConfig {
            val d = ConnSettings()
            if (!p.contains("resolution") && !p.contains("gameMode")) return AppConfig()
            val resolution = p.getString("resolution", "SCREEN")
            val shares = p.getString("shares", null)?.let {
                try {
                    Json.decodeFromString<List<ShareConfig>>(it)
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList()
            val s = d.copy(
                mode = if (p.getBoolean("gameMode", false)) "game" else "office",
                vdCount = if (resolution == "HOST") 0 else 1,
                vdSize = if (resolution == "SCREEN_1080") "screen1080" else "screen",
                vdScale = p.getInt("scalePercent", d.vdScale),
                maxFps = p.getInt("maxFps", d.maxFps),
                codec = p.getString("codec", d.codec) ?: d.codec,
                bitrateKbps = p.getInt("bitrateKbps", d.bitrateKbps),
                audio = p.getBoolean("audio", d.audio),
                physicalOff = p.getBoolean("physicalOff", d.physicalOff) && resolution != "HOST",
                controlMode = if (p.getString("controlMode", null) == "MOUSE") ControlMode.MOUSE else ControlMode.TOUCH,
                clipboard = p.getBoolean("syncClipboard", d.clipboard),
                videoTransport = p.getString("videoTransport", d.videoTransport) ?: d.videoTransport,
                bitratePolicy = p.getString("bitratePolicy", d.bitratePolicy) ?: d.bitratePolicy,
                hdr = p.getBoolean("hdr", d.hdr),
                mic = p.getBoolean("mic", d.mic),
                pcKeyboard = p.getBoolean("pcKeyboard", d.pcKeyboard),
                sharedFolders = shares,
            )
            return AppConfig(
                showGuideOnConnect = p.getBoolean("showGuideOnConnect", true),
                showStats = p.getBoolean("showStats", false),
                defaults = s,
            )
        }
    }
}
