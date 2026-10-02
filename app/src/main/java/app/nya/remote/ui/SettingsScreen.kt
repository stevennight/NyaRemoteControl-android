package app.nya.remote.ui

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.BuildConfig
import app.nya.remote.data.ControlMode
import app.nya.remote.data.Resolution
import app.nya.remote.data.Settings
import app.nya.remote.data.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    var s by remember { mutableStateOf(store.load()) }
    fun set(f: (Settings) -> Settings) {
        s = f(s)
        store.save(s)
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
            }
            Section("操作") {
                Options("默认操作方式", listOf(ControlMode.TOUCH to "触屏式", ControlMode.MOUSE to "鼠标式"), s.controlMode) { v ->
                    set { it.copy(controlMode = v) }
                }
                Toggle("连接时显示手势指引", s.showGuideOnConnect) { v -> set { it.copy(showGuideOnConnect = v) } }
            }
            Section("其他") {
                Toggle("播放电脑声音", s.audio) { v -> set { it.copy(audio = v) } }
                Toggle("电脑复制的文字自动复制到手机", s.syncClipboard) { v -> set { it.copy(syncClipboard = v) } }
                Toggle("显示统计信息", s.showStats) { v -> set { it.copy(showStats = v) } }
            }
            Text("NyaRemoteControl Android ${BuildConfig.VERSION_NAME}", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
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
