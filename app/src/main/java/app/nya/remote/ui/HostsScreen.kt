package app.nya.remote.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.data.Host
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    hosts: List<Host>,
    onSave: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onConnect: (Host, String?) -> Unit,
    onSettings: () -> Unit,
) {
    var editing by remember { mutableStateOf<Host?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nya 远程", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "设置") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("添加电脑") })
        },
        containerColor = PanelBg,
    ) { pad ->
        if (hosts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有电脑", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("在电脑上安装 NyaRemoteControl 被控端，\n然后用它的地址和配对码添加。", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(hosts, key = { it.id }) { h ->
                    HostCard(h, onClick = { onConnect(h, null) }, onEdit = { editing = h }, onDelete = { onDelete(h) })
                }
            }
        }
    }
    if (adding) {
        HostDialog(null, onDismiss = { adding = false }) { host, code ->
            adding = false
            onSave(host)
            onConnect(host, code)
        }
    }
    editing?.let { h ->
        HostDialog(h, onDismiss = { editing = null }) { host, code ->
            editing = null
            onSave(host)
            if (!code.isNullOrBlank()) onConnect(host, code)
        }
    }
}

@Composable
private fun HostCard(h: Host, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Accent, AccentCyan))),
            contentAlignment = Alignment.Center,
        ) {
            Text(h.displayName.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(h.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(h.address, color = Color.Gray, fontSize = 13.sp)
            val status = when {
                !h.paired -> "未配对"
                h.lastConnected > 0 -> "已配对 · 上次连接 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(h.lastConnected))
                else -> "已配对"
            }
            Text(status, color = if (h.paired) MaterialTheme.colorScheme.primary else Color(0xFFE08600), fontSize = 12.sp)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Add (host = null) or edit a host. The pairing code is used once, never stored. */
@Composable
private fun HostDialog(host: Host?, onDismiss: () -> Unit, onDone: (Host, String?) -> Unit) {
    var name by remember { mutableStateOf(host?.name ?: "") }
    var address by remember { mutableStateOf(host?.address ?: "") }
    var code by remember { mutableStateOf("") }
    var forget by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (host == null) "添加电脑" else "编辑电脑") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    address,
                    { address = it.trim() },
                    label = { Text("地址") },
                    placeholder = { Text("IP 或 IP:端口（默认 47100）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(name, { name = it }, label = { Text("名称（可选）") }, singleLine = true)
                OutlinedTextField(
                    code,
                    { code = it.uppercase() },
                    label = { Text(if (host?.paired == true) "配对码（重新配对时填写）" else "配对码") },
                    placeholder = { Text("在被控端管理程序里查看") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                if (host?.paired == true) {
                    Text("指纹 ${host.fingerprintShort ?: ""}", color = Color.Gray, fontSize = 12.sp)
                    TextButton(onClick = { forget = !forget }) {
                        Text(if (forget) "✓ 将忘记配对（被控端重装后需要）" else "忘记配对")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.isNotBlank(),
                onClick = {
                    val base = host ?: Host(name = name.trim(), address = address)
                    // The pin belongs to the host's certificate, not its address: keep it unless asked.
                    val h = base.copy(
                        name = name.trim(),
                        address = address,
                        fingerprint = if (forget) null else base.fingerprint,
                        fingerprintShort = if (forget) null else base.fingerprintShort,
                    )
                    onDone(h, code.trim().ifBlank { null })
                },
            ) { Text(if (host == null) "连接" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
