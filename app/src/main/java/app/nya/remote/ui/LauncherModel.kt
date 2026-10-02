package app.nya.remote.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nya.remote.BuildConfig
import app.nya.remote.data.AppConfig
import app.nya.remote.data.ConfigStore
import app.nya.remote.data.ConnSettings
import app.nya.remote.data.HostBook
import app.nya.remote.data.HostStore
import app.nya.remote.data.Updater
import app.nya.remote.session.DecoderCaps
import kotlin.concurrent.thread

/** Update state, as the Windows client reports it (common/web lib/UpdateCard.svelte). */
data class UpdateInfo(
    /** idle | checking | up_to_date | available | downloading | installing | failed */
    val state: String = "idle",
    val latest: String = "",
    val notes: String = "",
    val page: String = "",
    val progress: Int = 0,
    val message: String = "",
    val checkedAt: Long = 0,
    val release: Updater.Release? = null,
)

/** State of the launcher pages (devices, connection settings, about). */
class LauncherModel(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val hostStore = HostStore(app)
    private val configStore = ConfigStore(app)

    var config by mutableStateOf(configStore.load())
        private set
    var book by mutableStateOf(hostStore.book())
        private set
    var update by mutableStateOf(UpdateInfo())
        private set
    var decode by mutableStateOf("正在检测硬件解码…")
        private set

    init {
        thread(name = "nya-decoders") {
            val s = DecoderCaps.summary(DecoderCaps.detect())
            main.post { decode = s }
        }
    }

    /** Back from a session: hosts and settings may have changed there. */
    fun reload() {
        config = configStore.load()
        book = hostStore.book()
    }

    /** Change the hosts; returns an error message (bad name…) or null. */
    fun changeHosts(f: (HostBook) -> HostBook): String? = try {
        book = hostStore.change(f)
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "操作失败"
    }

    fun changeConfig(f: (AppConfig) -> AppConfig) {
        config = configStore.update(f)
    }

    /** Settings of [hostId] (null = the defaults); [s] null: the host goes back to the defaults. */
    fun saveSettings(hostId: String?, s: ConnSettings?) {
        when {
            hostId != null -> changeHosts { it.setSettings(hostId, s) }
            s != null -> changeConfig { it.copy(defaults = s) }
        }
    }

    // ------------------------------------------------------------------ update

    fun checkUpdate() {
        if (update.state == "checking" || update.state == "downloading") return
        update = update.copy(state = "checking", message = "")
        thread(name = "nya-update") {
            val r = runCatching { Updater.latest() }
            main.post {
                val now = System.currentTimeMillis()
                update = when {
                    r.isFailure || r.getOrNull() == null ->
                        UpdateInfo(state = "failed", message = "无法检查更新（网络或 GitHub 不可用）", checkedAt = now)
                    Updater.newer(r.getOrNull()!!.version, BuildConfig.VERSION_NAME) -> r.getOrNull()!!.let {
                        UpdateInfo(state = "available", latest = it.version, notes = it.notes, page = it.page, checkedAt = now, release = it)
                    }
                    else -> UpdateInfo(state = "up_to_date", latest = r.getOrNull()!!.version, checkedAt = now)
                }
            }
        }
    }

    /** Download, verify, hand to the system installer (the user confirms there). */
    fun installUpdate(activity: Activity) {
        val r = update.release ?: return
        if (update.state == "downloading") return
        update = update.copy(state = "downloading", progress = 0, message = "")
        thread(name = "nya-update") {
            try {
                val apk = Updater.download(app, r) { done, total ->
                    val p = if (total > 0) (done * 100 / total).toInt() else 0
                    main.post { update = update.copy(progress = p) }
                }
                main.post {
                    update = update.copy(state = "installing", message = "已下载并校验，请在系统安装界面确认")
                    Updater.install(activity, apk)
                    // Back to "available" if the user cancels the installer.
                    main.postDelayed({ if (update.state == "installing") update = update.copy(state = "available") }, 8000)
                }
            } catch (e: Exception) {
                main.post { update = update.copy(state = "failed", message = "更新失败：${e.message}") }
            }
        }
    }
}
