package app.nya.remote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class ControlMode { TOUCH, MOUSE }

/** How large the host's screen is made. */
enum class Resolution {
    /** A virtual screen with the phone's own resolution. */
    SCREEN,
    /** A virtual screen with the phone's aspect ratio, at most 1080 lines (lighter on network and decoder). */
    SCREEN_1080,
    /** No virtual screen: the host's displays as they are. */
    HOST,
}

data class Settings(
    val resolution: Resolution = Resolution.SCREEN,
    /** Windows display scaling of the virtual screen, in percent. */
    val scalePercent: Int = 150,
    val maxFps: Int = 60,
    val gameMode: Boolean = false,
    /** "auto" / "h264" / "hevc" / "av1". */
    val codec: String = "auto",
    /** 0 = the host decides. */
    val bitrateKbps: Int = 0,
    val audio: Boolean = true,
    val physicalOff: Boolean = false,
    val controlMode: ControlMode = ControlMode.TOUCH,
    val showGuideOnConnect: Boolean = true,
    val showStats: Boolean = false,
    /** Host clipboard text goes to the phone's clipboard. */
    val syncClipboard: Boolean = true,
)

class SettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    fun load(): Settings {
        val d = Settings()
        return Settings(
            resolution = enumOr(prefs.getString("resolution", null), d.resolution),
            scalePercent = prefs.getInt("scalePercent", d.scalePercent),
            maxFps = prefs.getInt("maxFps", d.maxFps),
            gameMode = prefs.getBoolean("gameMode", d.gameMode),
            codec = prefs.getString("codec", d.codec) ?: d.codec,
            bitrateKbps = prefs.getInt("bitrateKbps", d.bitrateKbps),
            audio = prefs.getBoolean("audio", d.audio),
            physicalOff = prefs.getBoolean("physicalOff", d.physicalOff),
            controlMode = enumOr(prefs.getString("controlMode", null), d.controlMode),
            showGuideOnConnect = prefs.getBoolean("showGuideOnConnect", d.showGuideOnConnect),
            showStats = prefs.getBoolean("showStats", d.showStats),
            syncClipboard = prefs.getBoolean("syncClipboard", d.syncClipboard),
        )
    }

    fun save(s: Settings) {
        prefs.edit {
            putString("resolution", s.resolution.name)
            putInt("scalePercent", s.scalePercent)
            putInt("maxFps", s.maxFps)
            putBoolean("gameMode", s.gameMode)
            putString("codec", s.codec)
            putInt("bitrateKbps", s.bitrateKbps)
            putBoolean("audio", s.audio)
            putBoolean("physicalOff", s.physicalOff)
            putString("controlMode", s.controlMode.name)
            putBoolean("showGuideOnConnect", s.showGuideOnConnect)
            putBoolean("showStats", s.showStats)
            putBoolean("syncClipboard", s.syncClipboard)
        }
    }

    fun update(f: (Settings) -> Settings) = save(f(load()))

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().find { it.name == name } ?: default
}
