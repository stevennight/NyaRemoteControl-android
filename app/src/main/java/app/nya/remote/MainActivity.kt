package app.nya.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.nya.remote.data.Host
import app.nya.remote.session.SessionActivity
import app.nya.remote.ui.AboutScreen
import app.nya.remote.ui.ConnSettingsScreen
import app.nya.remote.ui.DeviceActions
import app.nya.remote.ui.DevicesScreen
import app.nya.remote.ui.LauncherModel
import app.nya.remote.ui.NyaTheme

/** The launcher: devices, connection settings, about (the Windows client's main page, for a phone). */
class MainActivity : ComponentActivity(), DeviceActions {
    private lateinit var model: LauncherModel

    private enum class Page(val label: String, val icon: ImageVector) {
        DEVICES("设备", Icons.Filled.Home),
        SETTINGS("连接设置", Icons.Filled.Settings),
        ABOUT("关于与诊断", Icons.Filled.Info),
    }

    /** Page and settings scope, set from a device's menu. */
    private val page = mutableStateOf(Page.DEVICES)
    private val scope = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = LauncherModel(this)
        savedInstanceState?.getString("page")?.let { p -> Page.entries.find { it.name == p }?.let { page.value = it } }
        scope.value = savedInstanceState?.getString("scope")
        setContent {
            NyaTheme {
                BackHandler(page.value != Page.DEVICES) { page.value = Page.DEVICES }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 720.dp
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            NavigationRail(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))) {
                                Page.entries.forEach { p ->
                                    NavigationRailItem(
                                        selected = page.value == p,
                                        onClick = { open(p) },
                                        icon = { Icon(p.icon, null) },
                                        label = { Text(p.label) },
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Box(Modifier.widthIn(max = 1100.dp)) { Content(PaddingValues()) }
                            }
                        }
                    } else {
                        Scaffold(
                            containerColor = MaterialTheme.colorScheme.background,
                            bottomBar = {
                                NavigationBar {
                                    Page.entries.forEach { p ->
                                        NavigationBarItem(
                                            selected = page.value == p,
                                            onClick = { open(p) },
                                            icon = { Icon(p.icon, null) },
                                            label = { Text(p.label) },
                                        )
                                    }
                                }
                            },
                        ) { pad ->
                            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                                Content(pad)
                            }
                        }
                    }
                }
            }
        }
        if (model.config.checkUpdates) model.checkUpdate()
    }

    @androidx.compose.runtime.Composable
    private fun Content(pad: PaddingValues) {
        Box(Modifier.fillMaxSize().padding(top = 0.dp)) {
            when (page.value) {
                Page.DEVICES -> DevicesScreen(model, this@MainActivity, pad)
                Page.SETTINGS -> ConnSettingsScreen(model, scope.value, { scope.value = it }, pad)
                Page.ABOUT -> AboutScreen(model, ::installUpdate, pad)
            }
        }
    }

    private fun open(p: Page) {
        if (p == Page.SETTINGS && page.value != Page.SETTINGS) scope.value = null
        page.value = p
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.value.name)
        outState.putString("scope", scope.value)
    }

    override fun onResume() {
        super.onResume()
        model.reload()
    }

    // ------------------------------------------------------------ DeviceActions

    override fun connect(address: String, name: String?) {
        if (address.isBlank()) return
        // A saved host may also be picked by its name.
        val host = model.book.hosts.find { it.address == address || it.name == address }
        startActivity(SessionActivity.intent(this, host?.address ?: address, if (host == null) name else null))
    }

    override fun hostSettings(h: Host) {
        scope.value = h.id
        page.value = Page.SETTINGS
    }

    override fun copyAddress(h: Host) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("地址", h.address))
        Toast.makeText(this, "已复制地址", Toast.LENGTH_SHORT).show()
    }

    override fun installUpdate() = model.installUpdate(this)
}
