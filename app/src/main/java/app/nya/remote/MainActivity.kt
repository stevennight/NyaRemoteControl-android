package app.nya.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.nya.remote.data.HostStore
import app.nya.remote.data.SettingsStore
import app.nya.remote.session.SessionActivity
import app.nya.remote.ui.HostsScreen
import app.nya.remote.ui.NyaTheme
import app.nya.remote.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private lateinit var hosts: HostStore
    private val hostList = mutableStateOf(emptyList<app.nya.remote.data.Host>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hosts = HostStore(this)
        val settings = SettingsStore(this)
        setContent {
            NyaTheme {
                var showSettings by remember { mutableStateOf(false) }
                BackHandler(showSettings) { showSettings = false }
                if (showSettings) {
                    SettingsScreen(settings) { showSettings = false }
                } else {
                    HostsScreen(
                        hosts = hostList.value,
                        onSave = { hosts.put(it); refresh() },
                        onDelete = { hosts.remove(it.id); refresh() },
                        onConnect = { host, code -> startActivity(SessionActivity.intent(this, host.id, code)) },
                        onSettings = { showSettings = true },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        hostList.value = hosts.all().sortedByDescending { it.lastConnected }
    }
}
