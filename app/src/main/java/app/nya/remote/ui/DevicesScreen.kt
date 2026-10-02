package app.nya.remote.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.data.Host

/** What the devices page asks for. */
interface DeviceActions {
    /** Connect to [address]; [name] names a host that is not saved yet. */
    fun connect(address: String, name: String? = null)
    fun hostSettings(h: Host)
    fun copyAddress(h: Host)
    fun installUpdate()
}

@Composable
fun DevicesScreen(model: LauncherModel, actions: DeviceActions, padding: PaddingValues) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Host?>(null) }
    var deleting by remember { mutableStateOf<Host?>(null) }
    var address by remember { mutableStateOf("") }
    val hosts = model.book.hosts.sortedByDescending { it.lastConnected }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(250.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设备", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("  ${hosts.size} 台", color = muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加设备")
                }
            }
        }
        val u = model.update
        if (u.state == "available" || u.state == "downloading" || u.state == "installing") {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val text = when (u.state) {
                    "available" -> "有新版本 ${u.latest}（当前 ${app.nya.remote.BuildConfig.VERSION_NAME}）"
                    "downloading" -> "正在下载 ${u.latest}：${u.progress}%"
                    else -> u.message
                }
                Banner(text) {
                    if (u.state == "available") TextButton(onClick = actions::installUpdate) { Text("立即更新") }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            QuickConnect(address, { address = it }) { actions.connect(address.trim()) }
        }
        if (hosts.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("已保存的设备", color = muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            }
        }
        items(hosts, key = { it.id }) { h ->
            DeviceCard(
                h,
                onConnect = { actions.connect(h.address) },
                onSettings = { actions.hostSettings(h) },
                onRename = { renaming = h },
                onCopy = { actions.copyAddress(h) },
                onDelete = { deleting = h },
            )
        }
        item(key = "add") { AddCard(hosts.isEmpty()) { adding = true } }
    }

    if (adding) {
        AddDialog(
            onDismiss = { adding = false },
            onSave = { a, n -> model.changeHosts { it.add(a, n) }.also { if (it == null) adding = false } },
            onConnect = { a, n ->
                adding = false
                actions.connect(a, n.ifBlank { null })
            },
        )
    }
    renaming?.let { h ->
        RenameDialog(h, onDismiss = { renaming = null }) { name ->
            model.changeHosts { it.rename(h.id, name) }.also { if (it == null) renaming = null }
        }
    }
    deleting?.let { h ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除设备") },
            text = { Text("删除“${h.displayName}”？之后再连接需要重新配对。") },
            confirmButton = {
                TextButton(onClick = {
                    model.changeHosts { it.remove(h.id) }
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun QuickConnect(address: String, onChange: (String) -> Unit, onGo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            address,
            { onChange(it.trim()) },
            Modifier.weight(1f),
            placeholder = { Text("输入地址直接连接，例如 100.64.0.2", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) onGo() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Button(onClick = onGo, enabled = address.isNotBlank()) { Text("连接") }
    }
}

@Composable
private fun DeviceCard(h: Host, onConnect: () -> Unit, onSettings: () -> Unit, onRename: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val hu = hue(h.displayName)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Color.hsl(hu, 0.45f, 0.42f), Color.hsl((hu + 40) % 360, 0.40f, 0.22f))))
                .clickable(onClick = onConnect),
            contentAlignment = Alignment.Center,
        ) {
            Text(h.displayName.take(1).uppercase(), color = Color.White.copy(alpha = 0.9f), fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Filled.PlayArrow, "连接 ${h.displayName}", Modifier.align(Alignment.BottomEnd).padding(8.dp).size(22.dp), tint = Color.White.copy(alpha = 0.75f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(h.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.customName && h.serverName.isNotBlank() && h.serverName != h.name) {
                Text("被控端名称：${h.serverName}", color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(h.address, color = muted, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (h.paired) Chip("已配对", ChipKind.OK) else Chip("未配对", ChipKind.WARN)
                if (h.settings != null) Chip("单独设置")
            }
            Text(if (h.paired) "上次连接：${ago(h.lastConnected)}" else "第一次连接需要配对码", color = muted, fontSize = 12.5.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onConnect, Modifier.weight(1f)) { Text("连接") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("连接设置") }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text("改名") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("复制地址") }, onClick = { menu = false; onCopy() })
                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun AddCard(empty: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (empty) 180.dp else 236.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, null, Modifier.size(28.dp), tint = muted)
        Text("添加设备", color = muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        if (empty) {
            Text(
                "输入被控端的地址（Tailscale / EasyTier 等组网后的 IP）",
                color = muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onSave: (String, String) -> String?, onConnect: (String, String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加设备") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("被控端的地址（Tailscale / EasyTier 等组网后的 IP，可带端口）。第一次连接时输入被控端显示的配对码。", fontSize = 13.5.sp, color = muted)
                OutlinedTextField(
                    address,
                    { address = it.trim() },
                    label = { Text("地址") },
                    placeholder = { Text("100.64.0.2 或 host:47100") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("名称（可选）") },
                    placeholder = { Text("不填则使用被控端自己设置的名称") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { error = onSave(address, name) }, enabled = address.isNotBlank()) { Text("仅保存") }
                TextButton(onClick = { onConnect(address, name.trim()) }, enabled = address.isNotBlank()) { Text("连接") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameDialog(h: Host, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var name by remember { mutableStateOf(if (h.customName) h.name else "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(h.address, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = muted)
                OutlinedTextField(name, { name = it }, placeholder = { Text(h.serverName.ifBlank { h.address }) }, singleLine = true)
                Text(
                    (if (h.serverName.isNotBlank()) "被控端自己设置的名称是“${h.serverName}”。" else "") +
                        "留空则使用被控端的名称" + (if (h.serverName.isBlank()) "（连接后获取）" else "") + "，被控端改名后这里也跟着变。",
                    fontSize = 12.5.sp,
                    color = muted,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
