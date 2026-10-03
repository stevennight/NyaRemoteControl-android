package app.nya.remote.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.core.CoreEvent
import app.nya.remote.core.HostDisplay
import app.nya.remote.core.StatsLine
import app.nya.remote.data.ConnSettings
import app.nya.remote.data.ControlMode
import app.nya.remote.data.DisplayChoice
import app.nya.remote.input.KeyMap
import app.nya.remote.input.KeyboardController
import app.nya.remote.input.ScanKey
import app.nya.remote.ui.Accent
import app.nya.remote.ui.AccentCyan
import app.nya.remote.ui.GestureGuide
import app.nya.remote.ui.Seg
import app.nya.remote.ui.bitratePolicies
import app.nya.remote.ui.connectionModes
import app.nya.remote.ui.muted
import app.nya.remote.ui.sizeText
import kotlin.math.roundToInt

sealed interface Status {
    data object Connecting : Status
    data class Reconnecting(val message: String) : Status
    data object Connected : Status
    data class Disconnected(val message: String) : Status
}

/**
 * An upload or download in the transfers list. [cancelled]: stopped here or
 * by the host; what its tasks report afterwards changes nothing.
 */
data class TransferItem(val t: CoreEvent.Transfer, val savedTo: String? = null, val cancelled: Boolean = false)

/** Observable state of the session screen. */
class SessionUi(settings: ConnSettings, showStats: Boolean, showGuideOnConnect: Boolean) {
    var status by mutableStateOf<Status>(Status.Connecting)
    var hostName by mutableStateOf("")
    var needPairing by mutableStateOf(false)
    /** The host's certificate changed: offer to check it again. */
    var pinChanged by mutableStateOf<String?>(null)
    /** Fingerprint to confirm (reconnect without the pin). */
    var verifyFingerprint by mutableStateOf<String?>(null)
    /** This session's settings (the host's own, or the defaults), as changed in the panel. */
    var settings by mutableStateOf(settings)
    val controlMode: ControlMode get() = settings.controlMode
    val gameMode: Boolean get() = settings.game
    var panelOpen by mutableStateOf(false)
    var shortcutsOpen by mutableStateOf(false)
    var guideOpen by mutableStateOf(false)
    var keyboardOpen by mutableStateOf(false)
    /** The PC-layout keyboard is shown (instead of the phone's input method). */
    var pcKeyboardOpen by mutableStateOf(false)
    var showStats by mutableStateOf(showStats)
    var showGuideOnConnect by mutableStateOf(showGuideOnConnect)
    var stats by mutableStateOf<StatsLine?>(null)
    var stream by mutableStateOf<CoreEvent.StreamStarted?>(null)
    /** The connection is QUIC over TCP (null before the first connection). */
    var viaTcp by mutableStateOf<Boolean?>(null)
    var role by mutableStateOf<CoreEvent.Role?>(null)
    /** Short message while something changes ("正在切换到游戏模式…"); cleared when the stream (re)starts. */
    var notice by mutableStateOf("")
    var fileTransfer by mutableStateOf(false)
    var gamepad by mutableStateOf(false)
    var gamepadCount by mutableIntStateOf(0)
    /** Files copied on the host, waiting for "save to phone". */
    val offers = mutableStateListOf<CoreEvent.FileOffer>()
    /** Uploads and downloads, running and finished (newest last). */
    val transfers = mutableStateListOf<TransferItem>()
    var displays by mutableStateOf<List<HostDisplay>>(emptyList())
    /** The host can create virtual screens now (feature, driver installed, service mode). */
    var vdAvailable by mutableStateOf(false)
    var vdSupported by mutableStateOf(false)
    /** Host playback device that receives the microphone; empty = none. */
    var micDevice by mutableStateOf("")
    var micFeature by mutableStateOf(false)
    var micOn by mutableStateOf(false)
    /** A mouse is plugged in; its pointer can be locked (relative movement for games). */
    var mouseConnected by mutableStateOf(false)
    var mouseLocked by mutableStateOf(false)
    var usb by mutableStateOf(false)
    var usbItems: List<UsbSharing.Item> = emptyList()
    var folderMount by mutableStateOf<CoreEvent.FolderMount?>(null)
    /** A print job from the host waiting for "print" / "open" / "save". */
    var printJob by mutableStateOf<String?>(null)

    val watching: Boolean get() = role?.controlling == false && status == Status.Connected
    val micAvailable: Boolean get() = micFeature && micDevice.isNotBlank()
    /** The host display shown now. */
    val currentDisplay: Int get() = stream?.displayId ?: settings.display
}

interface SessionActions {
    fun setControlMode(mode: ControlMode)
    fun toggleKeyboard()
    /** Phone input method <-> PC-layout keyboard. */
    fun switchKeyboard()
    fun pcKeyboardHeight(px: Int)
    fun setGameMode(game: Boolean)
    fun setPolicy(policy: String)
    /** Connection mode "auto" / "udp" / "tcp". */
    fun setTransport(mode: String)
    fun setShowStats(show: Boolean)
    fun setShowGuideOnConnect(show: Boolean)
    fun sendSas()
    fun shortcut(vararg keys: ScanKey)
    fun sendClipboard()
    fun takeControl(kick: Boolean)
    fun resetZoom()
    fun pickDisplay(id: Int)
    fun setDisplayChoice(c: DisplayChoice)
    fun toggleMic()
    fun setMouseLock(on: Boolean)
    fun toggleUsb(item: UsbSharing.Item)
    /** "print" / "open" / "save" the waiting print job; null drops it. */
    fun printJob(how: String?)
    fun pickFiles()
    fun acceptOffer(id: String)
    fun dismissOffer(id: String)
    fun dismissTransfer(id: String)
    /** Stop a running transfer on both sides. */
    fun cancelTransfer(id: String)
    fun openDownloads()
    fun submitPairCode(code: String?)
    fun checkAgain(retry: Boolean)
    fun confirmFingerprint(ok: Boolean)
    fun retry()
    fun disconnect()
}

private val PanelSurface @Composable get() = MaterialTheme.colorScheme.background
private val CardSurface @Composable get() = MaterialTheme.colorScheme.surface
private val Bubble = Color(0xE6202226)
private val LatencyOk = Color(0xFF3CCF8E)
private val LatencyWarn = Color(0xFFFFC05C)
private val LatencyBad = Color(0xFFFF8F86)

/** Colour of an end-to-end latency: good / fair / poor. */
private fun latencyColor(ms: Float) = when {
    ms <= 0f -> Color(0xFF9AA0AB)
    ms < 60f -> LatencyOk
    ms < 120f -> LatencyWarn
    else -> LatencyBad
}

@Composable
fun SessionOverlay(ui: SessionUi, keys: KeyboardController, actions: SessionActions) {
    Box(Modifier.fillMaxSize()) {
        if (ui.showStats) StatsPanel(ui, Modifier.align(Alignment.TopStart).padding(12.dp))

        if (ui.watching) ui.role?.let { WatchingBanner(it, actions, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) }

        when (val s = ui.status) {
            Status.Connecting -> if (ui.verifyFingerprint == null && !ui.needPairing) {
                StatusCard("正在连接 ${ui.hostName}…", Modifier.align(Alignment.Center), onCancel = actions::disconnect)
            }
            is Status.Reconnecting -> StatusCard("连接中断，正在重连…\n${s.message}", Modifier.align(Alignment.Center), onCancel = actions::disconnect)
            else -> {}
        }

        if (ui.notice.isNotEmpty() && ui.status == Status.Connected) {
            Text(
                ui.notice,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).background(Bubble, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White,
                fontSize = 14.sp,
            )
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (ui.keyboardOpen) 0.dp else 16.dp).widthIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ui.printJob?.let { PrintBanner(it, actions) }
            ui.offers.forEach { OfferBanner(it, actions) }
            ui.transfers.takeLast(4).forEach { TransferCard(it, actions) }
        }

        if (ui.pcKeyboardOpen) {
            PcKeyboard(keys, actions::switchKeyboard, actions::toggleKeyboard, actions::pcKeyboardHeight, Modifier.align(Alignment.BottomCenter))
        } else if (ui.keyboardOpen) {
            ExtraKeys(keys, actions, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime))
        }

        if (!ui.panelOpen) FloatingBall(latencyColor(ui.stats?.latencyMs ?: 0f)) { ui.panelOpen = true }

        // Panel scrim and the side panel.
        AnimatedVisibility(ui.panelOpen, enter = fadeIn(), exit = fadeOut()) {
            val none = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().background(Color(0x55000000)).clickable(none, null) { ui.panelOpen = false })
        }
        AnimatedVisibility(
            ui.panelOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            SidePanel(ui, actions)
        }

        if (ui.guideOpen) {
            GestureGuide(ui.controlMode, ui.showGuideOnConnect, actions::setShowGuideOnConnect) { ui.guideOpen = false }
        }

        if (ui.needPairing) PairDialog(ui.hostName, actions)
        ui.verifyFingerprint?.let { VerifyDialog(it, actions) }
        ui.pinChanged?.let { PinChangedDialog(ui.hostName, actions) }

        (ui.status as? Status.Disconnected)?.takeIf { ui.pinChanged == null }?.let { s ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("连接已断开") },
                text = { Text(s.message) },
                confirmButton = { TextButton(onClick = actions::retry) { Text("重新连接") } },
                dismissButton = { TextButton(onClick = actions::disconnect) { Text("返回") } },
            )
        }
    }
}

// ------------------------------------------------------------------ bubbles

@Composable
private fun BubbleRow(content: @Composable () -> Unit) {
    Row(
        Modifier.background(Bubble, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun PrintBanner(path: String, actions: SessionActions) {
    Column(Modifier.fillMaxWidth().background(Bubble, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)) {
        Text("被控端发来打印：${path.substringAfterLast('/').substringAfterLast('\\')}", color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row {
            TextButton(onClick = { actions.printJob("print") }) { Text("打印", color = AccentCyan) }
            TextButton(onClick = { actions.printJob("open") }) { Text("打开", color = AccentCyan) }
            TextButton(onClick = { actions.printJob("save") }) { Text("保存", color = AccentCyan) }
            TextButton(onClick = { actions.printJob(null) }) { Text("忽略", color = Color(0xFFB8BBC2)) }
        }
    }
}

@Composable
private fun OfferBanner(o: CoreEvent.FileOffer, actions: SessionActions) {
    Column(Modifier.fillMaxWidth().background(Bubble, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)) {
        val first = o.files.firstOrNull()?.name.orEmpty()
        val what = if (o.files.size == 1) first else "$first 等 ${o.files.size} 个文件"
        Text("被控端复制了文件", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("$what（${sizeText(o.totalBytes)}）", color = Color(0xFFDADCE0), fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row {
            TextButton(onClick = { actions.acceptOffer(o.id) }) { Text("保存到手机", color = AccentCyan) }
            TextButton(onClick = { actions.dismissOffer(o.id) }) { Text("忽略", color = Color(0xFFB8BBC2)) }
        }
    }
}

@Composable
private fun TransferCard(item: TransferItem, actions: SessionActions) {
    val t = item.t
    Column(Modifier.fillMaxWidth().background(Bubble, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.upload) "↑ 发送 " else "↓ 下载 ", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(t.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (t.finished) {
                Text("✕", color = Color(0xFFB8BBC2), fontSize = 15.sp, modifier = Modifier.clip(CircleShape).clickable { actions.dismissTransfer(t.id) }.padding(horizontal = 10.dp, vertical = 2.dp))
            } else if (t.id.isNotEmpty()) {
                // Stops it on both sides; what arrived of it is deleted.
                Text("取消", color = AccentCyan, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { actions.cancelTransfer(t.id) }.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
        when {
            !t.finished -> {
                if (t.total > 0) {
                    LinearProgressIndicator(
                        progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp),
                        color = AccentCyan,
                    )
                }
                Text(
                    if (t.total > 0) "${sizeText(t.done)} / ${sizeText(t.total)}" else t.message.ifBlank { sizeText(t.done) },
                    color = Color(0xFFB8BBC2),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            t.ok -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.savedTo?.let { "已保存到 $it" } ?: t.message, color = Color(0xFF9BE3B5), fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                if (item.savedTo != null) TextButton(onClick = actions::openDownloads) { Text("打开", color = AccentCyan) }
            }
            else -> Text(t.message, color = Color(0xFFFF8A80), fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun StatusCard(text: String, modifier: Modifier, onCancel: () -> Unit) {
    Column(modifier.background(Color(0xCC202226), RoundedCornerShape(14.dp)).padding(start = 22.dp, end = 12.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(14.dp))
            Text(text, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("取消", color = AccentCyan) }
    }
}

/** Statistics as the Windows client shows them (session.rs `stream_lines`). */
fun statsLines(ui: SessionUi): List<String> {
    val s = ui.stats ?: return listOf("等待统计…")
    val out = mutableListOf<String>()
    ui.stream?.let { st ->
        out += "编码  ${st.encoder} ${st.codec} 4:2:0 ${st.width}×${st.height}@${st.fps}" + if (st.crossGpu.isNotEmpty()) "  跨显卡 ${st.crossGpu}" else ""
        if (st.hdrTonemapped) out += "HDR  被控端显示器开启了 HDR，已转换为 SDR 传输"
        if (st.hdr) out += "HDR  HDR10 直通（HEVC 10 bit，BT.2020 PQ）"
    }
    out += "帧率  被控端 ${s.serverFps} / 本机 ${s.fps}   丢帧 ${s.framesDropped}"
    val kbps = maxOf(s.serverKbps.toFloat(), s.mbps * 1000f)
    out += "码率  实际 %.1f Mbps   上限 %.1f Mbps".format(kbps / 1000f, s.targetKbps / 1000f)
    if (s.bitrateNote.isNotBlank()) out += "策略  ${s.bitrateNote}"
    if (s.fecPercent > 0) {
        out += "传输  数据报 + 纠错 ${s.fecPercent}%%   丢包 %.1f%%   纠错恢复 ${s.framesRecovered} 帧   丢帧 ${s.framesLost}".format(s.lossPercent)
    }
    out += "延迟  端到端 %.1f ms   RTT %.1f ms".format(s.latencyMs, s.rttMs)
    ui.viaTcp?.let { tcp ->
        val mode = connectionModes.find { it.first == ui.settings.transport }?.second ?: "自动"
        // The host's view of the path (hosts before protocol 1.8 send none).
        val path = if (s.pathRttMs > 0f) "   丢包 %.1f%%   往返 %.0f ms".format(s.pathLossPct, s.pathRttMs) else ""
        out += "连接  ${if (tcp) "TCP" else "UDP"}（$mode）$path"
    }
    val p99 = if (s.encodeP99Ms > 0) "%.1f".format(s.encodeP99Ms) else "—"
    out += "耗时  编码 %.1f/%s（中位/P99）  解码 %.1f ms".format(s.encodeMs, p99, s.decodeMs)
    if (s.audioTargetMs > 0) {
        out += "声音  缓冲 %.0f / 目标 %.0f ms".format(s.audioMs, s.audioTargetMs) + if (s.audioUnderruns > 0) "   断音 ${s.audioUnderruns}" else ""
    }
    return out
}

@Composable
private fun StatsPanel(ui: SessionUi, modifier: Modifier) {
    Text(
        statsLines(ui).joinToString("\n"),
        modifier.background(Color(0xB3000000), RoundedCornerShape(8.dp)).padding(8.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 15.sp,
    )
}

@Composable
private fun WatchingBanner(role: CoreEvent.Role, actions: SessionActions, modifier: Modifier) {
    Row(
        modifier.background(Color(0xDD202226), RoundedCornerShape(20.dp)).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val who = role.controller.ifBlank { "另一个客户端" }
        Text("正在观看 · $who 正在操作", color = Color.White, fontSize = 13.sp)
        TextButton(onClick = { actions.takeControl(false) }) { Text("接管操作", color = AccentCyan) }
        TextButton(onClick = { actions.takeControl(true) }) { Text("顶掉对方", color = Color(0xFFFF8A80)) }
    }
}

/** The draggable ball that opens the panel; rests at the left or right edge. The ring shows the latency. */
@Composable
private fun FloatingBall(ring: Color, onClick: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = 52.dp
        val sizePx = with(density) { size.toPx() }
        val maxX = with(density) { maxWidth.toPx() } - sizePx
        val maxY = with(density) { maxHeight.toPx() } - sizePx
        var x by remember { mutableFloatStateOf(Float.NaN) }
        var y by remember { mutableFloatStateOf(Float.NaN) }
        if (x.isNaN()) {
            x = maxX - with(density) { 12.dp.toPx() }
            y = maxY * 0.75f
        }
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent, AccentCyan)))
                .border(2.dp, ring.copy(alpha = 0.85f), CircleShape)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            // Snap to the nearer side.
                            x = if (x + sizePx / 2 < (maxX + sizePx) / 2) 12.dp.toPx() else maxX - 12.dp.toPx()
                        },
                    ) { change, drag ->
                        change.consume()
                        x = (x + drag.x).coerceIn(0f, maxX)
                        y = (y + drag.y).coerceIn(0f, maxY)
                    }
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("Nya", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

// ------------------------------------------------------------------ side panel

@Composable
private fun SidePanel(ui: SessionUi, actions: SessionActions) {
    val watching = ui.watching
    Column(
        Modifier
            .fillMaxHeight()
            .width(400.dp)
            .background(PanelSurface, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(ui, actions)

        if (watching) {
            Card {
                val who = ui.role?.controller?.ifBlank { null } ?: "另一个客户端"
                Text("正在观看 · $who 正在操作", fontSize = 13.sp, color = muted)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("接管操作", Modifier.weight(1f)) { actions.takeControl(false) }
                    Tile("顶掉对方", Modifier.weight(1f), danger = true) { actions.takeControl(true) }
                }
                Text("接管：由你操作，$who 改为观看 · 顶掉：由你操作，并断开 $who", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Card(enabled = !watching) {
            Label("操作方式")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("触屏式", ui.controlMode == ControlMode.TOUCH, Modifier.weight(1f), !watching) { actions.setControlMode(ControlMode.TOUCH) }
                Choice("鼠标式", ui.controlMode == ControlMode.MOUSE, Modifier.weight(1f), !watching) { actions.setControlMode(ControlMode.MOUSE) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("键盘输入", Modifier.weight(1f), enabled = !watching) {
                    ui.panelOpen = false
                    actions.toggleKeyboard()
                }
                Tile("快捷操作", Modifier.weight(1f), selected = ui.shortcutsOpen, enabled = !watching) { ui.shortcutsOpen = !ui.shortcutsOpen }
                Tile("手势指引", Modifier.weight(1f)) {
                    ui.panelOpen = false
                    ui.guideOpen = true
                }
            }
            if (ui.shortcutsOpen && !watching) Shortcuts(actions)
        }

        DisplaysCard(ui, actions)

        Card {
            Label("画面模式")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("办公 · 清晰", !ui.gameMode, Modifier.weight(1f)) { actions.setGameMode(false) }
                Choice("游戏 · 流畅", ui.gameMode, Modifier.weight(1f)) { actions.setGameMode(true) }
            }
            Text(
                if (ui.gameMode) "4:2:0，高帧率，延迟最低" else "文字清晰，画面静止后补发清晰帧；画面不变时几乎不占带宽",
                fontSize = 12.sp,
                color = muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Label("网络变差时", Modifier.padding(top = 12.dp))
            Box(Modifier.padding(top = 8.dp)) {
                Seg(bitratePolicies.map { it.first to it.second }, ui.settings.bitratePolicy) { actions.setPolicy(it) }
            }
            Text(bitratePolicies.find { it.first == ui.settings.bitratePolicy }?.third.orEmpty(), fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
            ui.stats?.takeIf { it.targetKbps > 0 }?.let { st ->
                Text(
                    "当前 %.1f Mbps，上限 %.1f Mbps".format(maxOf(st.serverKbps / 1000f, st.mbps), st.targetKbps / 1000f),
                    fontSize = 12.sp,
                    color = muted,
                )
            }
            val now = when (ui.viaTcp) {
                true -> "（现在：TCP）"
                false -> "（现在：UDP）"
                null -> ""
            }
            Label("连接方式$now", Modifier.padding(top = 12.dp))
            Box(Modifier.padding(top = 8.dp)) {
                Seg(connectionModes.map { it.first to it.second }, ui.settings.transport) { actions.setTransport(it) }
            }
            Text(connectionModes.find { it.first == ui.settings.transport }?.third.orEmpty(), fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("还原缩放", Modifier.weight(1f)) {
                    ui.panelOpen = false
                    actions.resetZoom()
                }
                Tile("统计信息", Modifier.weight(1f), selected = ui.showStats) { actions.setShowStats(!ui.showStats) }
            }
        }

        Card(enabled = !watching) {
            Label("传输与外设")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("发送剪贴板", Modifier.weight(1f), enabled = !watching) { actions.sendClipboard() }
                if (ui.fileTransfer) Tile("发送文件…", Modifier.weight(1f), enabled = !watching) { actions.pickFiles() }
            }
            if (ui.fileTransfer) {
                Text("文件保存在被控端的 下载\\NyaRemoteControl；在被控端复制文件后，这里会提示保存到手机", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
            }
            ToggleRow(
                "麦克风",
                when {
                    ui.micAvailable -> "手机麦克风 → 被控端“${ui.micDevice}”。打开期间 CABLE Output 自动成为被控端的默认麦克风"
                    ui.micFeature -> "被控端没有安装虚拟声卡（在被控端的“本机 → 可选组件”中安装）"
                    else -> "被控端版本太旧，不支持麦克风"
                },
                ui.micOn,
                enabled = ui.micAvailable && !watching,
            ) { actions.toggleMic() }
            if (ui.mouseConnected) {
                ToggleRow("锁定鼠标", "相对鼠标：实体鼠标锁定在画面里，适合 FPS 游戏；在这里关闭", ui.mouseLocked, enabled = !watching) {
                    actions.setMouseLock(!ui.mouseLocked)
                }
            }
            if (ui.gamepadCount > 0) {
                Text("手柄 ×${ui.gamepadCount} 已映射为被控端 Xbox 手柄", fontSize = 13.sp, color = Color(0xFF2E9E6A), modifier = Modifier.padding(top = 10.dp))
            }
            ui.folderMount?.let { m ->
                Text(
                    if (m.mounted) "共享文件夹已挂载到被控端 ${m.mountPoint}" else "共享文件夹：${m.message}",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (ui.usb) {
                Label("USB 设备透传（手机 OTG 接口上的设备）", Modifier.padding(top = 12.dp))
                Text("透传后设备在手机上暂时不可用，断开后自动归还", fontSize = 12.sp, color = muted)
                if (ui.usbItems.isEmpty()) Text("没有检测到 USB 设备", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                ui.usbItems.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 13.sp)
                            if (item.status.isNotBlank()) Text(item.status, fontSize = 11.sp, color = muted)
                        }
                        TextButton(onClick = { actions.toggleUsb(item) }, enabled = !watching) { Text(if (item.shared) "停止" else "透传") }
                    }
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardSurface).clickable(onClick = actions::disconnect).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("断开连接", color = Color(0xFFE53935), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Header(ui: SessionUi, actions: SessionActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Accent, AccentCyan)), RoundedCornerShape(16.dp))
            .clickable { actions.setShowStats(!ui.showStats) }
            .padding(16.dp),
    ) {
        Text(ui.hostName.ifBlank { "远程主机" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val s = ui.stats
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(latencyColor(s.latencyMs)))
                Spacer(Modifier.width(6.dp))
                val latency = if (s.latencyMs > 0) "延迟 %.0f ms · ".format(s.latencyMs) else ""
                Text("$latency${s.fps} fps · %.1f Mbps".format(s.mbps), color = Color(0xEEFFFFFF), fontSize = 13.sp)
            } else {
                Text("正在连接…", color = Color(0xDDFFFFFF), fontSize = 13.sp)
            }
        }
        ui.role?.takeIf { it.controlling && it.viewers.isNotEmpty() }?.let { r ->
            Text("${r.viewers.size} 人观看：${r.viewers.joinToString("、")}", color = Color(0xDDFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "屏幕 2 · 虚拟 · 主  2400×1080 · HDR→SDR". */
private fun displayLabel(i: Int, d: HostDisplay): String =
    "屏幕 ${i + 1} · ${if (d.isVirtual) "虚拟" else "物理"}${if (d.primary) " · 主" else ""}   ${d.width}×${d.height}${if (d.hdr) " · HDR→SDR" else ""}"

/** Host displays to switch to, then the display setup (virtual screens, privacy). */
@Composable
private fun DisplaysCard(ui: SessionUi, actions: SessionActions) {
    Card {
        Label("被控端的显示器")
        if (ui.watching) {
            Text("观看时只显示被控端的主画面，切换和更改显示器由正在操作的人决定", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
            return@Card
        }
        val current = ui.currentDisplay
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.displays.forEachIndexed { i, d ->
                val selected = d.id == current || (current == 0 && d.primary)
                Choice(displayLabel(i, d), selected, Modifier.fillMaxWidth(), small = true) { if (!selected) actions.pickDisplay(d.id) }
            }
        }
        val c = ui.settings.displayChoice
        if (ui.vdAvailable) {
            Label("虚拟显示器", Modifier.padding(top = 12.dp))
            Box(Modifier.padding(top = 6.dp)) {
                Seg(listOf(0 to "不用", 1 to "1 个", 2 to "2 个", 3 to "3 个", 4 to "4 个"), c.count) { n -> actions.setDisplayChoice(c.copy(count = n)) }
            }
            Label("被控端物理显示器", Modifier.padding(top = 10.dp))
            Box(Modifier.padding(top = 6.dp).alpha(if (c.count > 0) 1f else 0.45f)) {
                Seg(listOf(false to "保持显示", true to "关闭（黑屏）"), c.physicalOff, enabled = c.count > 0) { v -> actions.setDisplayChoice(c.copy(physicalOff = v)) }
            }
            ToggleRow("屏蔽被控端本地键盘鼠标", "被控端旁边的人无法操作（Ctrl+Alt+Del 除外）", c.blockInput) {
                actions.setDisplayChoice(c.copy(blockInput = !c.blockInput))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val privacy = DisplayChoice.privacy(c.count)
                Tile("一键隐私屏", Modifier.weight(1f), enabled = c != privacy) { actions.setDisplayChoice(privacy) }
                Tile("恢复被控端原样", Modifier.weight(1f), enabled = c != DisplayChoice.ASIS) { actions.setDisplayChoice(DisplayChoice.ASIS) }
            }
        } else if (ui.status == Status.Connected && ui.displays.isNotEmpty()) {
            ToggleRow("屏蔽被控端本地键盘鼠标", "被控端旁边的人无法操作（Ctrl+Alt+Del 除外）", c.blockInput) {
                actions.setDisplayChoice(c.copy(blockInput = !c.blockInput))
            }
            Text(
                if (ui.vdSupported) "被控端没有安装虚拟显示器（在被控端的“本机 → 可选组件”中安装），或没有以服务模式运行" else "被控端版本太旧，不支持虚拟显示器",
                fontSize = 12.sp,
                color = muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) = Text(text, modifier, fontSize = 13.sp, color = muted)

@Composable
private fun Card(enabled: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(CardSurface, RoundedCornerShape(16.dp)).padding(14.dp).alpha(if (enabled) 1f else 0.6f)) { content() }
}

@Composable
private fun ToggleRow(title: String, help: String, on: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 15.sp)
            Text(help, fontSize = 12.sp, color = muted)
        }
        Switch(checked = on, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = if (small) 9.dp else 12.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontSize = if (small) 13.5.sp else 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Tile(text: String, modifier: Modifier, selected: Boolean = false, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                danger -> Color(0xFFE53935)
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private class Shortcut(val label: String, val keys: List<ScanKey>)

private val winKey = KeyMap.LWIN
private val shortcuts = listOf(
    Shortcut("Win", listOf(winKey)),
    Shortcut("显示桌面", listOf(winKey, ScanKey(0x20))),
    Shortcut("切换窗口", listOf(KeyMap.LALT, KeyMap.TAB)),
    Shortcut("任务视图", listOf(winKey, KeyMap.TAB)),
    Shortcut("任务管理器", listOf(KeyMap.LCTRL, KeyMap.LSHIFT, KeyMap.ESC)),
    Shortcut("资源管理器", listOf(winKey, ScanKey(0x12))),
    Shortcut("关闭窗口", listOf(KeyMap.LALT, KeyMap.function(4))),
    Shortcut("截图", listOf(winKey, KeyMap.LSHIFT, ScanKey(0x1F))),
    Shortcut("锁定", listOf(winKey, ScanKey(0x26))),
    Shortcut("复制", listOf(KeyMap.LCTRL, ScanKey(0x2E))),
    Shortcut("粘贴", listOf(KeyMap.LCTRL, ScanKey(0x2F))),
    Shortcut("全选", listOf(KeyMap.LCTRL, ScanKey(0x1E))),
    Shortcut("撤销", listOf(KeyMap.LCTRL, ScanKey(0x2C))),
    Shortcut("保存", listOf(KeyMap.LCTRL, ScanKey(0x1F))),
    Shortcut("运行", listOf(winKey, ScanKey(0x13))),
)

@Composable
private fun Shortcuts(actions: SessionActions) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Ctrl+Alt+Del（需要被控端以服务模式运行）", false, Modifier.fillMaxWidth(), small = true) { actions.sendSas() }
        shortcuts.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s -> Choice(s.label, false, Modifier.weight(1f), small = true) { actions.shortcut(*s.keys.toTypedArray()) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private class Extra(val label: String, val key: ScanKey, val sticky: Boolean = false)

private val extraKeys = listOf(
    Extra("Esc", KeyMap.ESC),
    Extra("Tab", KeyMap.TAB),
    Extra("Ctrl", KeyMap.LCTRL, sticky = true),
    Extra("Alt", KeyMap.LALT, sticky = true),
    Extra("Shift", KeyMap.LSHIFT, sticky = true),
    Extra("Win", KeyMap.LWIN, sticky = true),
    Extra("←", KeyMap.LEFT),
    Extra("↑", KeyMap.UP),
    Extra("↓", KeyMap.DOWN),
    Extra("→", KeyMap.RIGHT),
    Extra("Del", KeyMap.DELETE),
    Extra("Home", KeyMap.HOME),
    Extra("End", KeyMap.END),
    Extra("PgUp", KeyMap.PAGE_UP),
    Extra("PgDn", KeyMap.PAGE_DOWN),
) + (1..12).map { Extra("F$it", KeyMap.function(it)) }

/** Keys a phone keyboard lacks, above the soft keyboard. Modifiers latch for the next key. */
@Composable
private fun ExtraKeys(keys: KeyboardController, actions: SessionActions, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color(0xF0202226)).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            extraKeys.forEach { e ->
                val latched = e.sticky && e.key in keys.sticky
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (latched) Accent else Color(0xFF3A3D43))
                        .clickable { if (e.sticky) keys.toggleSticky(e.key) else keys.tap(e.key) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(e.label, color = Color.White, fontSize = 14.sp)
                }
            }
        }
        TextButton(onClick = actions::switchKeyboard) { Text("电脑键盘", color = AccentCyan) }
        TextButton(onClick = actions::toggleKeyboard) { Text("收起", color = AccentCyan) }
    }
}

// ------------------------------------------------------------------ connection dialogs

@Composable
private fun PairDialog(hostName: String, actions: SessionActions) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("首次连接：配对") },
        text = {
            Column {
                Text("请输入 $hostName 的配对码。在被控端打开 NyaRemoteControl 的“本机 → 概览”查看。", fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    singleLine = true,
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = { TextButton(onClick = { actions.submitPairCode(code) }, enabled = code.isNotBlank()) { Text("配对") } },
        dismissButton = { TextButton(onClick = { actions.submitPairCode(null) }) { Text("取消") } },
    )
}

@Composable
private fun PinChangedDialog(hostName: String, actions: SessionActions) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("被控端证书已变化") },
        text = {
            Text("$hostName 的证书和上次保存的不一样。常见原因：被控端从开发模式改为服务模式，或重装过；也可能有人在冒充被控端。", fontSize = 14.sp)
        },
        confirmButton = { TextButton(onClick = { actions.checkAgain(true) }) { Text("重新验证") } },
        dismissButton = { TextButton(onClick = { actions.checkAgain(false) }) { Text("取消") } },
    )
}

@Composable
private fun VerifyDialog(fingerprint: String, actions: SessionActions) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("核对证书指纹") },
        text = {
            Column {
                Text("被控端已经认识本机，所以没有用配对码验证它的身份。请核对指纹：", fontSize = 14.sp)
                Text(
                    fingerprint,
                    Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("与被控端“本机 → 概览”显示的“证书指纹”一致才继续。", fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = { actions.confirmFingerprint(true) }) { Text("一致，继续") } },
        dismissButton = { TextButton(onClick = { actions.confirmFingerprint(false) }) { Text("不一致，取消") } },
    )
}
