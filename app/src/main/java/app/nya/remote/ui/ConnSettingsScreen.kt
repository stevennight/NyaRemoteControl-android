package app.nya.remote.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.core.ShareConfig
import app.nya.remote.data.ConnSettings
import app.nya.remote.data.ControlMode
import app.nya.remote.data.DisplayChoice
import app.nya.remote.data.Shares

private val POLICIES = listOf(
    Triple("auto", "自动", "办公模式用“清晰优先”，游戏模式用“均衡”"),
    Triple("quality", "清晰优先", "只有持续 2 秒以上严重发送不出去才降，最低保留 60%"),
    Triple("balanced", "均衡", "持续积压或延迟明显上涨时降到实际能发送的速率，最低 35%"),
    Triple("smooth", "流畅优先", "积压、延迟上涨、丢包都会触发，最低 15%，适合很差的网络"),
    Triple("fixed", "固定码率", "从不自动调整"),
)

private val TRANSPORTS = listOf(
    Triple("auto", "自动", "游戏模式用“数据报 + 纠错”，办公模式用可靠传输"),
    Triple("stream", "可靠传输", "不丢画面，但网络丢包时会卡一下等重传"),
    Triple("datagram", "数据报 + 纠错", "丢包时靠纠错数据恢复，恢复不了就跳过这一帧，不卡顿；多占约 10–50% 带宽"),
)

private val CONNECTIONS = listOf(
    Triple("auto", "自动", "优先 UDP；UDP 连不上或丢包严重时改用 TCP，UDP 恢复后自动换回"),
    Triple("udp", "仅 UDP", "延迟最低；网络限制 UDP 时可能连不上或卡顿"),
    Triple("tcp", "仅 TCP", "适合 UDP 不通或很差的网络；网络差时延迟比 UDP 高。端口转发需要同时转发 TCP"),
)

/** Choices of the policy menu in a session too. */
val bitratePolicies: List<Triple<String, String, String>> get() = POLICIES

/** Connection modes, in a session too. */
val connectionModes: List<Triple<String, String, String>> get() = CONNECTIONS

private enum class Preset { ASIS, EXTEND, PRIVATE }

private fun presetOf(d: ConnSettings): Preset? = when {
    d.vdCount == 0 && !d.blockInput -> Preset.ASIS
    d.vdCount > 0 && !d.physicalOff && !d.blockInput -> Preset.EXTEND
    d.vdCount > 0 && d.physicalOff && d.blockInput -> Preset.PRIVATE
    else -> null
}

private fun applyPreset(d: ConnSettings, p: Preset): ConnSettings = when (p) {
    Preset.ASIS -> d.withDisplayChoice(DisplayChoice.ASIS)
    Preset.EXTEND -> d.withDisplayChoice(DisplayChoice(maxOf(1, d.vdCount)))
    Preset.PRIVATE -> d.withDisplayChoice(DisplayChoice.privacy(d.vdCount))
}

/**
 * "连接设置": the defaults, or the own settings of the host [scope] (id).
 * The Windows page's cards, for a phone.
 */
@Composable
fun ConnSettingsScreen(model: LauncherModel, scope: String?, onScope: (String?) -> Unit, padding: PaddingValues) {
    val context = LocalContext.current
    val host = scope?.let { model.book.byId(it) }
    val saved = host?.settings ?: model.config.defaults
    // Edited copy, taken when the page (or its scope) opens.
    var d by remember(scope) { mutableStateOf(saved) }
    var bitrateMode by remember(scope) { mutableStateOf(modeOf(saved)) }
    val dirty = d != saved
    fun set(f: (ConnSettings) -> ConnSettings) {
        d = f(d)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("连接设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.widthIn(max = 220.dp)) {
                    Select(
                        listOf<Pair<String?, String>>(null to "默认设置（所有设备）") +
                            model.book.hosts.map { it.id to (it.displayName + if (it.settings == null) "（使用默认）" else "") },
                        scope,
                        onPick = onScope,
                    )
                }
            }
            Text(
                when {
                    host == null -> "没有单独设置的设备使用这里的设置。连接后在面板里改的画面模式、显示器、码率策略、连接方式、麦克风、操作方式会记在那台设备的单独设置里。"
                    host.settings != null -> "“${host.displayName}”使用单独的设置。"
                    else -> "“${host.displayName}”目前使用默认设置；在这里保存后改为单独设置，不影响其他设备。"
                },
                color = muted,
                fontSize = 13.sp,
            )
            if (host?.settings != null) {
                TextButton(onClick = {
                    model.saveSettings(host.id, null)
                    Toast.makeText(context, "“${host.displayName}”改为使用默认设置", Toast.LENGTH_SHORT).show()
                }, contentPadding = PaddingValues(0.dp)) { Text("改回使用默认设置") }
            }

            DisplayCard(d, ::set)
            PictureCard(d, bitrateMode, { bitrateMode = it }, ::set)
            Group(title = "声音与外设") {
                SwitchField("播放被控端声音", checked = d.audio) { v -> set { it.copy(audio = v) } }
                SwitchField("同步剪贴板", "被控端复制的文字和图片自动复制到手机，复制的文件提示保存", d.clipboard) { v -> set { it.copy(clipboard = v) } }
                SwitchField("连接后打开麦克风", "把手机麦克风传给被控端（被控端需要安装“虚拟麦克风”组件）", d.mic) { v -> set { it.copy(mic = v) } }
                Field("被控端打印时", "被控端选择“打印到 NyaRemoteControl 客户端”打印机时，内容以 PDF 发到手机（被控端需要安装“打印到客户端”组件）", stacked = true) {
                    Select(
                        listOf("ask" to "每次询问", "print" to "用手机的打印服务打印", "open" to "用其他应用打开 PDF", "save" to "只保存到下载文件夹"),
                        d.printMode,
                    ) { v -> set { it.copy(printMode = v) } }
                }
            }
            Group(title = "操作") {
                Field("操作方式", "触屏式：手指直接点哪里就是哪里 · 鼠标式：手指像触控板一样移动鼠标。连接后可随时在面板里切换", stacked = true) {
                    Seg(listOf(ControlMode.TOUCH to "触屏式", ControlMode.MOUSE to "鼠标式"), d.controlMode) { v -> set { it.copy(controlMode = v) } }
                }
                Field("键盘", "电脑键盘：屏幕上的电脑布局键盘，按键直接发给被控端（游戏、快捷键、命令行更顺手）。键盘上可随时切换", stacked = true) {
                    Seg(listOf(false to "手机输入法", true to "电脑键盘"), d.pcKeyboard) { v -> set { it.copy(pcKeyboard = v) } }
                }
            }
            SharesCard(d, ::set)
            Group(title = "高级") {
                Field("编码格式") {
                    Select(listOf("auto" to "自动", "hevc" to "HEVC", "h264" to "H.264", "av1" to "AV1"), d.codec) { v -> set { it.copy(codec = v) } }
                }
                Field("被控端编码器") {
                    Select(
                        listOf("auto" to "自动", "nvenc" to "NVIDIA NVENC", "qsv" to "Intel QSV", "amf" to "AMD AMF", "software" to "软件"),
                        d.encoder,
                    ) { v -> set { it.copy(encoder = v) } }
                }
                SwitchField("硬件解码", "关闭后用手机的软件解码器（硬件解码出现花屏、绿屏时再试）", d.hwDecode) { v -> set { it.copy(hwDecode = v) } }
            }
        }

        // Save bar, as on the Windows page.
        if (dirty) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = padding.calculateBottomPadding() + 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("有未保存的修改", color = muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    d = saved
                    bitrateMode = modeOf(saved)
                }) { Text("放弃修改") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    model.saveSettings(host?.id, d)
                    Toast.makeText(context, "已保存，下次连接时生效", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            }
        }
    }
}

private fun modeOf(s: ConnSettings) = if (s.unlimitedBitrate) "unlimited" else if (s.bitrateKbps > 0) "manual" else "auto"

@Composable
private fun DisplayCard(d: ConnSettings, set: ((ConnSettings) -> ConnSettings) -> Unit) {
    val noVd = d.vdCount == 0
    Group(title = "被控端显示器") {
        val preset = presetOf(d)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(Preset.ASIS, "原样", "传输被控端现有的显示器"),
                Triple(Preset.EXTEND, "加虚拟屏", "新建和手机屏幕一样的虚拟显示器，物理屏照常显示"),
                Triple(Preset.PRIVATE, "隐私屏", "只留虚拟屏，物理屏黑屏，屏蔽被控端本地键鼠"),
            ).forEach { (p, name, text) ->
                val on = preset == p
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.5.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface)
                        .clickable { set { applyPreset(it, p) } }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresetPicture(p)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(text, color = muted, fontSize = 12.sp)
                    }
                }
            }
        }
        Field("虚拟显示器数量", "在被控端新建，第一个设为主显示器，多个时可在面板里切换查看。需要被控端安装“虚拟显示器”组件，并以服务模式运行", stacked = true) {
            Seg(listOf(0 to "不用", 1 to "1", 2 to "2", 3 to "3", 4 to "4"), d.vdCount) { v -> set { it.withDisplayChoice(it.displayChoice.copy(count = v)) } }
        }
        Field("被控端物理显示器", "关闭后被控端屏幕黑屏，断开 15 秒后自动恢复", enabled = !noVd, stacked = true) {
            Seg(listOf(false to "保持显示", true to "关闭"), d.physicalOff, enabled = !noVd) { v -> set { it.copy(physicalOff = v) } }
        }
        SwitchField("屏蔽被控端本地键盘鼠标", "远程操作时旁边的人无法操作（Ctrl+Alt+Del 除外）", d.blockInput) { v -> set { it.copy(blockInput = v) } }
        Field("虚拟显示器分辨率", "跟随手机屏幕：画面 1:1 最清晰；≤1080p：同样的宽高比，更省流量和解码", enabled = !noVd, stacked = true) {
            Seg(listOf("screen" to "跟随手机屏幕", "screen1080" to "跟随屏幕（≤1080p）", "fixed" to "固定"), d.vdSize, enabled = !noVd) { v ->
                set { it.copy(vdSize = v) }
            }
            if (d.vdSize == "fixed") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(d.vdWidth, "宽", 640..7680, enabled = !noVd) { v -> set { it.copy(vdWidth = v) } }
                    Text("  ×  ", color = muted)
                    NumberField(d.vdHeight, "高", 480..4320, enabled = !noVd) { v -> set { it.copy(vdHeight = v) } }
                }
            }
        }
        Field("虚拟显示器缩放比例", "被控端 Windows 的缩放。手机屏幕小、像素密度高，150% 以上文字才看得清", enabled = !noVd, stacked = true) {
            Seg(listOf(0 to "不改", 100 to "100%", 125 to "125%", 150 to "150%", 175 to "175%", 200 to "200%", 250 to "250%"), d.vdScale, enabled = !noVd) { v ->
                set { it.copy(vdScale = v) }
            }
        }
    }
}

/** Small picture of a preset: rectangles for the virtual and physical screens. */
@Composable
private fun PresetPicture(p: Preset) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        @Composable
        fun screen(virtual: Boolean, off: Boolean = false) = Box(
            Modifier
                .width(30.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (virtual) accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.5.dp, (if (virtual) accent else line).copy(alpha = if (off) 0.35f else 1f), RoundedCornerShape(3.dp)),
        )
        when (p) {
            Preset.ASIS -> { screen(false); screen(false) }
            Preset.EXTEND -> { screen(true); screen(false) }
            Preset.PRIVATE -> { screen(true); screen(false, off = true) }
        }
    }
}

@Composable
private fun PictureCard(d: ConnSettings, bitrateMode: String, onBitrateMode: (String) -> Unit, set: ((ConnSettings) -> ConnSettings) -> Unit) {
    Group(title = "画面") {
        Field("模式", "办公：文字清晰，静止时补发清晰帧 · 游戏：高帧率、低延迟", stacked = true) {
            Seg(listOf("office" to "办公", "game" to "游戏"), d.mode) { v -> set { it.copy(mode = v) } }
        }
        Field(
            "码率上限",
            when (bitrateMode) {
                "auto" -> "按分辨率和帧率估算，1080p60 办公约 7.5 Mbps"
                "unlimited" -> "最高 80 Mbps；静止画面只占用实际需要的带宽"
                else -> "手动指定"
            },
            stacked = true,
        ) {
            Seg(listOf("auto" to "自动", "unlimited" to "不限制", "manual" to "手动"), bitrateMode) { m ->
                onBitrateMode(m)
                set {
                    when (m) {
                        "unlimited" -> it.copy(unlimitedBitrate = true, bitrateKbps = 0)
                        "manual" -> it.copy(unlimitedBitrate = false, bitrateKbps = if (it.bitrateKbps > 0) it.bitrateKbps else 10_000)
                        else -> it.copy(unlimitedBitrate = false, bitrateKbps = 0)
                    }
                }
            }
            if (bitrateMode == "manual") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(d.bitrateKbps, "码率", 1000..80_000) { v -> set { it.copy(bitrateKbps = v) } }
                    Text("  kbps", color = muted, fontSize = 13.sp)
                }
            }
        }
        Field("网络变差时", POLICIES.find { it.first == d.bitratePolicy }?.third, stacked = true) {
            Select(POLICIES.map { it.first to it.second }, d.bitratePolicy) { v -> set { it.copy(bitratePolicy = v) } }
        }
        Field("连接方式", CONNECTIONS.find { it.first == d.transport }?.third, stacked = true) {
            Select(CONNECTIONS.map { it.first to it.second }, d.transport) { v -> set { it.copy(transport = v) } }
        }
        Field("画面传输方式", TRANSPORTS.find { it.first == d.videoTransport }?.third, stacked = true) {
            Select(TRANSPORTS.map { it.first to it.second }, d.videoTransport) { v -> set { it.copy(videoTransport = v) } }
        }
        SwitchField("HDR 直通", "被控端开启 HDR、手机屏幕支持 HDR10 时，用 HDR10 传输（10 bit）；否则被控端转换为 SDR", d.hdr) { v -> set { it.copy(hdr = v) } }
        Field("帧率上限", "跟随手机屏幕的刷新率，或限制（高刷新率更耗电、更占流量）", stacked = true) {
            Seg(listOf(0 to "跟随屏幕", 30 to "30", 60 to "60", 90 to "90", 120 to "120"), d.maxFps) { v -> set { it.copy(maxFps = v) } }
        }
    }
}

@Composable
private fun SharesCard(d: ConnSettings, set: ((ConnSettings) -> ConnSettings) -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(Shares.accessGranted(context)) }
    // Coming back from the system's "all files access" page.
    LaunchedEffect(Unit) { access = Shares.accessGranted(context) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = it }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { Shares.pathOf(it) }
        when {
            uri == null -> {}
            path == null -> Toast.makeText(context, "只能共享手机存储里的文件夹", Toast.LENGTH_LONG).show()
            d.sharedFolders.any { it.path == path } -> Toast.makeText(context, "这个文件夹已经在列表里了", Toast.LENGTH_SHORT).show()
            else -> {
                val name = Shares.uniqueName(path.substringAfterLast('/'), d.sharedFolders.map { it.name })
                set { it.copy(sharedFolders = it.sharedFolders + ShareConfig(name, path, readOnly = false)) }
            }
        }
    }
    Group(title = "共享文件夹") {
        Text(
            "连接后这些文件夹出现在被控端的一个盘符里（例如 Z:），被控端的程序可以直接打开、保存。被控端需要在“可选组件”里安装“文件夹挂载”。",
            color = muted,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (!access) {
            Field("需要先允许本应用访问手机文件", "共享文件夹由被控端按路径读写，需要“所有文件访问权限”") {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            context.startActivity(Shares.accessSettings(context))
                        } else {
                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }) { Text("授予") }
                    TextButton(onClick = { access = Shares.accessGranted(context) }) { Text("我已授予") }
                }
            }
        }
        if (d.sharedFolders.isEmpty()) {
            Field("还没有共享文件夹")
        }
        d.sharedFolders.forEachIndexed { i, f ->
            Column(Modifier.fillMaxWidth()) {
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            f.name,
                            { v ->
                                val name = v.replace('/', '_').replace('\\', '_').replace(':', '_')
                                set { s -> s.copy(sharedFolders = s.sharedFolders.mapIndexed { j, x -> if (j == i) x.copy(name = name) else x }) }
                            },
                            label = { Text("在被控端显示的名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(f.path, color = muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
                        Text("只读", color = muted, fontSize = 12.sp)
                        Switch(checked = f.readOnly, onCheckedChange = { v ->
                            set { s -> s.copy(sharedFolders = s.sharedFolders.mapIndexed { j, x -> if (j == i) x.copy(readOnly = v) else x }) }
                        })
                    }
                    IconButton(onClick = { set { s -> s.copy(sharedFolders = s.sharedFolders.filterIndexed { j, _ -> j != i }) } }) {
                        Icon(Icons.Filled.Close, "不再共享")
                    }
                }
            }
        }
        Field("") {
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("添加文件夹…") }
        }
    }
}

/** A small number box; only values inside [range] are taken (the box keeps what is typed meanwhile). */
@Composable
private fun NumberField(value: Int, label: String, range: IntRange, enabled: Boolean = true, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { t ->
            text = t.filter { it.isDigit() }.take(6)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        Modifier.width(110.dp),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
