package app.nya.remote.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.BuildConfig
import app.nya.remote.core.ShareConfig
import app.nya.remote.data.ControlMode
import app.nya.remote.data.Resolution
import app.nya.remote.data.Settings
import app.nya.remote.data.SettingsStore
import app.nya.remote.data.Shares

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, onCheckUpdate: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(store.load()) }
    fun set(f: (Settings) -> Settings) {
        s = f(s)
        store.save(s)
    }
    var access by remember { mutableStateOf(Shares.accessGranted(context)) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = it }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { Shares.pathOf(it) }
        if (uri != null && path == null) {
            Toast.makeText(context, "只能共享手机存储里的文件夹", Toast.LENGTH_LONG).show()
        } else if (path != null && s.shares.none { it.path == path }) {
            val name = Shares.uniqueName(path.substringAfterLast('/'), s.shares.map { it.name })
            set { it.copy(shares = it.shares + ShareConfig(name, path, readOnly = false)) }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        containerColor = PanelBg,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("画面") {
                Options(
                    "远程分辨率",
                    listOf(Resolution.SCREEN to "跟随手机屏幕", Resolution.SCREEN_1080 to "跟随屏幕（≤1080p）", Resolution.HOST to "电脑原分辨率"),
                    s.resolution,
                    hint = "跟随屏幕：在电脑上建一块和手机屏幕一样大的虚拟显示器（需要被控端装有虚拟显示器驱动，否则用电脑原来的显示器）。",
                ) { v -> set { it.copy(resolution = v) } }
                if (s.resolution != Resolution.HOST) {
                    Options("电脑缩放比例", listOf(100, 125, 150, 175, 200, 250).map { it to "$it%" }, s.scalePercent) { v -> set { it.copy(scalePercent = v) } }
                    Toggle("连接时关闭电脑的显示器（隐私）", s.physicalOff) { v -> set { it.copy(physicalOff = v) } }
                }
                Options("帧率上限", listOf(30, 60, 90, 120).map { it to "$it" }, s.maxFps) { v -> set { it.copy(maxFps = v) } }
                Options("画面模式", listOf(false to "办公（清晰）", true to "游戏（流畅）"), s.gameMode) { v -> set { it.copy(gameMode = v) } }
                Options("编码", listOf("auto" to "自动", "h264" to "H.264", "hevc" to "HEVC", "av1" to "AV1"), s.codec) { v -> set { it.copy(codec = v) } }
                Options(
                    "码率",
                    listOf(0 to "自动", 5_000 to "5M", 10_000 to "10M", 20_000 to "20M", 40_000 to "40M"),
                    s.bitrateKbps,
                ) { v -> set { it.copy(bitrateKbps = v) } }
                Options(
                    "码率调整",
                    listOf("auto" to "自动", "quality" to "画质优先", "balanced" to "均衡", "smooth" to "流畅优先", "fixed" to "固定"),
                    s.bitratePolicy,
                    hint = "网络变差时被控端怎样降码率；自动 = 办公画质优先、游戏均衡。",
                ) { v -> set { it.copy(bitratePolicy = v) } }
                Options(
                    "视频传输",
                    listOf("auto" to "自动", "stream" to "可靠流", "datagram" to "数据报 + 纠错"),
                    s.videoTransport,
                    hint = "自动 = 游戏模式走数据报 + 纠错（弱网不卡顿），办公模式走可靠流（不丢帧）。",
                ) { v -> set { it.copy(videoTransport = v) } }
                Toggle("HDR（手机屏幕支持 HDR10 且电脑开着 HDR 时）", s.hdr) { v -> set { it.copy(hdr = v) } }
            }
            Section("操作") {
                Options("默认操作方式", listOf(ControlMode.TOUCH to "触屏式", ControlMode.MOUSE to "鼠标式"), s.controlMode) { v ->
                    set { it.copy(controlMode = v) }
                }
                Toggle("连接时显示手势指引", s.showGuideOnConnect) { v -> set { it.copy(showGuideOnConnect = v) } }
            }
            Section("共享文件夹到电脑") {
                Text("连接后这些文件夹出现在电脑上的一个盘符里（需要被控端装有 WinFsp）。", color = Color.Gray, fontSize = 12.sp)
                if (!access) {
                    Text("需要先允许本应用访问手机文件。", fontSize = 13.sp)
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            context.startActivity(Shares.accessSettings(context))
                        } else {
                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }) { Text("授予文件访问权限") }
                    TextButton(onClick = { access = Shares.accessGranted(context) }) { Text("我已授予，刷新") }
                }
                s.shares.forEach { share ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(share.name, fontSize = 14.sp)
                            Text(share.path, fontSize = 11.sp, color = Color.Gray)
                        }
                        TextButton(onClick = {
                            set { st -> st.copy(shares = st.shares.map { if (it == share) it.copy(readOnly = !it.readOnly) else it }) }
                        }) { Text(if (share.readOnly) "只读" else "可写") }
                        TextButton(onClick = { set { st -> st.copy(shares = st.shares - share) } }) { Text("移除") }
                    }
                }
                TextButton(onClick = { pickFolder.launch(null) }) { Text("添加文件夹") }
            }
            Section("其他") {
                Toggle("播放电脑声音", s.audio) { v -> set { it.copy(audio = v) } }
                Toggle("电脑复制的文字和图片自动复制到手机", s.syncClipboard) { v -> set { it.copy(syncClipboard = v) } }
                Toggle("显示统计信息", s.showStats) { v -> set { it.copy(showStats = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("版本 ${BuildConfig.VERSION_NAME}", Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = onCheckUpdate) { Text("检查更新") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Options(label: String, options: List<Pair<T, String>>, value: T, hint: String? = null, onPick: (T) -> Unit) {
    Column {
        Text(label, fontSize = 14.sp)
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, text) -> FilterChip(selected = v == value, onClick = { onPick(v) }, label = { Text(text) }) }
        }
        if (hint != null) Text(hint, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
