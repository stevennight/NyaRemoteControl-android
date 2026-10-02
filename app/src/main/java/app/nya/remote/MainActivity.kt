package app.nya.remote

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.nya.remote.data.Host
import app.nya.remote.data.HostStore
import app.nya.remote.data.SettingsStore
import app.nya.remote.data.Updater
import app.nya.remote.session.SessionActivity
import app.nya.remote.ui.HostsScreen
import app.nya.remote.ui.NyaTheme
import app.nya.remote.ui.SettingsScreen
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var hosts: HostStore
    private val hostList = mutableStateOf(emptyList<Host>())

    /** A newer release found on GitHub. */
    private val update = mutableStateOf<Updater.Release?>(null)

    /** Download progress text while updating; null otherwise. */
    private val updating = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hosts = HostStore(this)
        val settings = SettingsStore(this)
        setContent {
            NyaTheme {
                var showSettings by remember { mutableStateOf(false) }
                BackHandler(showSettings) { showSettings = false }
                Box {
                    if (showSettings) {
                        SettingsScreen(settings, onCheckUpdate = { checkUpdate(manual = true) }) { showSettings = false }
                    } else {
                        HostsScreen(
                            hosts = hostList.value,
                            onSave = { hosts.put(it); refresh() },
                            onDelete = { hosts.remove(it.id); refresh() },
                            onConnect = { host, code -> startActivity(SessionActivity.intent(this@MainActivity, host.id, code)) },
                            onSettings = { showSettings = true },
                        )
                    }
                    update.value?.let { r ->
                        AlertDialog(
                            onDismissRequest = { update.value = null },
                            title = { Text("发现新版本 ${r.version}") },
                            text = { Text(updating.value ?: r.notes.take(600).ifBlank { "当前版本 ${BuildConfig.VERSION_NAME}" }) },
                            confirmButton = {
                                TextButton(enabled = updating.value == null, onClick = { install(r) }) { Text("下载并安装") }
                            },
                            dismissButton = { TextButton(onClick = { update.value = null }) { Text("以后再说") } },
                        )
                    }
                }
            }
        }
        // At most once a day on its own.
        val prefs = getSharedPreferences("update", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) > 24 * 3600_000L) {
            prefs.edit { putLong("checked", System.currentTimeMillis()) }
            checkUpdate(manual = false)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        hostList.value = hosts.all().sortedByDescending { it.lastConnected }
    }

    private fun checkUpdate(manual: Boolean) {
        thread(name = "nya-update") {
            val r = try {
                Updater.latest()
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                when {
                    r != null && Updater.newer(r.version, BuildConfig.VERSION_NAME) -> update.value = r
                    manual && r == null -> Toast.makeText(this, "无法检查更新（网络或 GitHub 不可用）", Toast.LENGTH_SHORT).show()
                    manual -> Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun install(r: Updater.Release) {
        updating.value = "正在下载…"
        thread(name = "nya-update") {
            try {
                val apk = Updater.download(this, r) { done, total ->
                    val text = if (total > 0) "正在下载 ${done * 100 / total}%" else "正在下载 ${done / 1_000_000} MB"
                    runOnUiThread { updating.value = text }
                }
                runOnUiThread {
                    updating.value = null
                    update.value = null
                    Updater.install(this, apk)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updating.value = null
                    Toast.makeText(this, "更新失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
