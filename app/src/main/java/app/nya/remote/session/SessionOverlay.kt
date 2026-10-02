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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.core.CoreEvent
import app.nya.remote.core.StatsLine
import app.nya.remote.data.ControlMode
import app.nya.remote.input.KeyMap
import app.nya.remote.input.KeyboardController
import app.nya.remote.input.ScanKey
import app.nya.remote.ui.Accent
import app.nya.remote.ui.AccentCyan
import app.nya.remote.ui.GestureGuide
import app.nya.remote.ui.PanelBg
import kotlin.math.roundToInt

sealed interface Status {
    data object Connecting : Status
    data class Reconnecting(val message: String) : Status
    data object Connected : Status
    data class Disconnected(val message: String) : Status
}

/** Observable state of the session screen. */
class SessionUi(initialMode: ControlMode, showStats: Boolean, gameMode: Boolean, showGuideOnConnect: Boolean) {
    var status by mutableStateOf<Status>(Status.Connecting)
    var hostName by mutableStateOf("")
    var needPairing by mutableStateOf(false)
    var controlMode by mutableStateOf(initialMode)
    var panelOpen by mutableStateOf(false)
    var shortcutsOpen by mutableStateOf(false)
    var guideOpen by mutableStateOf(false)
    var keyboardOpen by mutableStateOf(false)
    var showStats by mutableStateOf(showStats)
    var gameMode by mutableStateOf(gameMode)
    var showGuideOnConnect by mutableStateOf(showGuideOnConnect)
    var stats by mutableStateOf<StatsLine?>(null)
    var stream by mutableStateOf<CoreEvent.StreamStarted?>(null)
    var role by mutableStateOf<CoreEvent.Role?>(null)
}

interface SessionActions {
    fun setControlMode(mode: ControlMode)
    fun toggleKeyboard()
    fun setGameMode(game: Boolean)
    fun setShowStats(show: Boolean)
    fun setShowGuideOnConnect(show: Boolean)
    fun sendSas()
    fun shortcut(vararg keys: ScanKey)
    fun sendClipboard()
    fun takeControl(kick: Boolean)
    fun resetZoom()
    fun submitPairCode(code: String?)
    fun retry()
    fun disconnect()
}

@Composable
fun SessionOverlay(ui: SessionUi, keys: KeyboardController, actions: SessionActions) {
    Box(Modifier.fillMaxSize()) {
        if (ui.showStats) StatsText(ui, Modifier.align(Alignment.TopStart).padding(12.dp))

        ui.role?.takeIf { !it.controlling && ui.status == Status.Connected }?.let { role ->
            ViewerBanner(role, actions, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        }

        when (val s = ui.status) {
            Status.Connecting -> StatusCard("正在连接 ${ui.hostName}…", Modifier.align(Alignment.Center))
            is Status.Reconnecting -> StatusCard("连接中断，正在重连…\n${s.message}", Modifier.align(Alignment.Center))
            else -> {}
        }

        if (ui.keyboardOpen) {
            ExtraKeys(keys, actions, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime))
        }

        if (!ui.panelOpen) FloatingBall { ui.panelOpen = true }

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

        (ui.status as? Status.Disconnected)?.let { s ->
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

@Composable
private fun StatusCard(text: String, modifier: Modifier) {
    Row(
        modifier.background(Color(0xCC202226), RoundedCornerShape(14.dp)).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun StatsText(ui: SessionUi, modifier: Modifier) {
    val s = ui.stats ?: return
    val st = ui.stream
    val lines = buildString {
        append("${s.fps} fps · %.1f Mbps · RTT %.0f ms".format(s.mbps, s.rttMs))
        if (s.latencyMs > 0) append(" · 延迟 %.0f ms".format(s.latencyMs))
        append("\n解码 %.1f ms · 编码 %.1f ms".format(s.decodeMs, s.encodeMs))
        if (s.targetKbps > 0) append(" · 目标 %.1f Mbps".format(s.targetKbps / 1000f))
        if (s.fecPercent > 0) append(" · 纠错 ${s.fecPercent}%")
        if (s.framesLost + s.framesDropped > 0) append(" · 丢帧 ${s.framesLost + s.framesDropped}")
        if (st != null) append("\n${st.codec} ${st.width}×${st.height} · ${st.encoder}")
    }
    Text(
        lines,
        modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 15.sp,
    )
}

@Composable
private fun ViewerBanner(role: CoreEvent.Role, actions: SessionActions, modifier: Modifier) {
    Row(
        modifier.background(Color(0xDD202226), RoundedCornerShape(20.dp)).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val who = role.controller.ifBlank { "其他客户端" }
        Text("正在观看（$who 操作中）", color = Color.White, fontSize = 13.sp)
        TextButton(onClick = { actions.takeControl(false) }) { Text("接管操作", color = AccentCyan) }
        TextButton(onClick = { actions.takeControl(true) }) { Text("顶掉对方", color = Color(0xFFFF8A80)) }
    }
}

/** The draggable ball that opens the panel; rests at the left or right edge. */
@Composable
private fun FloatingBall(onClick: () -> Unit) {
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
                .border(2.dp, Color(0x99FFFFFF), CircleShape)
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

@Composable
private fun SidePanel(ui: SessionUi, actions: SessionActions) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(400.dp)
            .background(PanelBg, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Accent, AccentCyan)), RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text(ui.hostName.ifBlank { "远程主机" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val s = ui.stats
            Text(
                if (s != null) "${s.fps} fps · %.1f Mbps · RTT %.0f ms".format(s.mbps, s.rttMs) else "正在连接…",
                color = Color(0xDDFFFFFF),
                fontSize = 13.sp,
            )
        }

        Card {
            Text("操作方式", fontSize = 13.sp, color = Color.Gray)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("触屏式", ui.controlMode == ControlMode.TOUCH, Modifier.weight(1f)) { actions.setControlMode(ControlMode.TOUCH) }
                Choice("鼠标式", ui.controlMode == ControlMode.MOUSE, Modifier.weight(1f)) { actions.setControlMode(ControlMode.MOUSE) }
            }
        }

        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("键盘输入", Modifier.weight(1f)) {
                    ui.panelOpen = false
                    actions.toggleKeyboard()
                }
                Tile("快捷操作", Modifier.weight(1f), selected = ui.shortcutsOpen) { ui.shortcutsOpen = !ui.shortcutsOpen }
                Tile("手势指引", Modifier.weight(1f)) {
                    ui.panelOpen = false
                    ui.guideOpen = true
                }
            }
            if (ui.shortcutsOpen) Shortcuts(actions)
        }

        Card {
            Text("画面", fontSize = 13.sp, color = Color.Gray)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("办公模式", !ui.gameMode, Modifier.weight(1f)) { actions.setGameMode(false) }
                Choice("游戏模式", ui.gameMode, Modifier.weight(1f)) { actions.setGameMode(true) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("还原缩放", Modifier.weight(1f)) {
                    ui.panelOpen = false
                    actions.resetZoom()
                }
                Tile(if (ui.showStats) "隐藏统计" else "显示统计", Modifier.weight(1f), selected = ui.showStats) {
                    actions.setShowStats(!ui.showStats)
                }
                Tile("发送剪贴板", Modifier.weight(1f)) { actions.sendClipboard() }
            }
        }

        ui.role?.takeIf { !it.controlling }?.let {
            Card {
                Text("其他客户端正在操作，你在观看", fontSize = 13.sp, color = Color.Gray)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("接管操作", Modifier.weight(1f)) { actions.takeControl(false) }
                    Tile("顶掉对方", Modifier.weight(1f)) { actions.takeControl(true) }
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(onClick = actions::disconnect).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("断开连接", color = Color(0xFFE53935), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp)) { content() }
}

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFFE3ECFF) else Color(0xFFF2F3F6))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Accent else Color(0xFF303236), fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun Tile(text: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) =
    Choice(text, selected, modifier, onClick)

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
    Shortcut("复制", listOf(KeyMap.LCTRL, ScanKey(0x2E))),
    Shortcut("粘贴", listOf(KeyMap.LCTRL, ScanKey(0x2F))),
    Shortcut("全选", listOf(KeyMap.LCTRL, ScanKey(0x1E))),
    Shortcut("撤销", listOf(KeyMap.LCTRL, ScanKey(0x2C))),
)

@Composable
private fun Shortcuts(actions: SessionActions) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Ctrl+Alt+Del", false, Modifier.fillMaxWidth()) { actions.sendSas() }
        shortcuts.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s -> Choice(s.label, false, Modifier.weight(1f)) { actions.shortcut(*s.keys.toTypedArray()) } }
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
        TextButton(onClick = actions::toggleKeyboard) { Text("收起", color = AccentCyan) }
    }
}

@Composable
private fun PairDialog(hostName: String, actions: SessionActions) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("输入配对码") },
        text = {
            Column {
                Text("首次连接 $hostName 需要配对码，在被控端管理程序里查看。", fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    singleLine = true,
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
        },
        confirmButton = { TextButton(onClick = { actions.submitPairCode(code) }, enabled = code.isNotBlank()) { Text("配对") } },
        dismissButton = { TextButton(onClick = { actions.submitPairCode(null) }) { Text("取消") } },
    )
}
