package app.nya.remote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import app.nya.remote.BuildConfig
import app.nya.remote.data.AppConfig
import app.nya.remote.data.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "关于与诊断": this phone's name, version and updates, diagnostics. */
@Composable
fun AboutScreen(model: LauncherModel, onInstall: () -> Unit, padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(model.config.clientName) }
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("关于与诊断", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Group(title = "本机") {
            Field("NyaRemoteControl", "版本 ${BuildConfig.VERSION_NAME}", divider = false)
            Field("本机名称", "被控端的“已配对客户端”和连接记录里显示这个名字。留空则用手机型号 ${AppConfig.deviceName()}", stacked = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        name,
                        { name = it.take(64) },
                        Modifier.weight(1f),
                        placeholder = { Text(AppConfig.deviceName()) },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            model.changeConfig { it.copy(clientName = name.trim()) }
                            name = name.trim()
                            Toast.makeText(context, "已保存，下次连接时被控端显示新名称", Toast.LENGTH_SHORT).show()
                        },
                        enabled = name.trim() != model.config.clientName,
                    ) { Text("保存") }
                }
            }
            Field("硬件解码", model.decode)
        }

        UpdateCard(model.update, onCheck = model::checkUpdate, onInstall = onInstall, onPage = { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        })
        Group {
            SwitchField("启动时检查新版本", "从 GitHub 查看是否有新版本；是否安装由你决定", model.config.checkUpdates, divider = false) { v ->
                model.changeConfig { it.copy(checkUpdates = v) }
            }
        }

        Group(title = "应用") {
            SwitchField("连接时显示手势指引", checked = model.config.showGuideOnConnect, divider = false) { v ->
                model.changeConfig { it.copy(showGuideOnConnect = v) }
            }
            SwitchField("连接后显示统计信息", "帧率、码率、延迟等；连接后也可以在面板里打开", model.config.showStats) { v ->
                model.changeConfig { it.copy(showStats = v) }
            }
        }

        Group(title = "诊断") {
            Field("检测手机的屏幕、解码器、声音和网络", "连接有问题时，把结果和日志一起发给开发者", divider = false, stacked = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !sharing, onClick = {
                        sharing = true
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { Diagnostics.logFile(context) }
                            sharing = false
                            Diagnostics.share(context, f)
                        }
                    }) { Text(if (sharing) "正在读取…" else "分享日志") }
                    Button(enabled = !running, onClick = {
                        running = true
                        scope.launch {
                            report = withContext(Dispatchers.Default) { runCatching { Diagnostics.report(context) }.getOrElse { "诊断失败：${it.message}" } }
                            running = false
                        }
                    }) { Text(if (running) "检测中…" else "运行诊断") }
                }
            }
            if (report.isNotEmpty()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("结果", color = muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("诊断", report))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }) { Text("复制") }
                    }
                    Text(
                        report,
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Version and updates (the web page's `UpdateCard`). */
@Composable
private fun UpdateCard(u: UpdateInfo, onCheck: () -> Unit, onInstall: () -> Unit, onPage: (String) -> Unit) {
    var notes by remember { mutableStateOf(false) }
    val busy = u.state == "checking" || u.state == "downloading" || u.state == "installing"
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("版本与更新", color = muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (u.state == "available") {
                    Button(onClick = onInstall) { Text("更新到 ${u.latest}") }
                } else {
                    OutlinedButton(onClick = onCheck, enabled = !busy) { Text(if (u.state == "checking") "检查中…" else "检查更新") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("当前版本 ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Text(BuildConfig.VERSION_NAME, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                when (u.state) {
                    "up_to_date" -> Chip("已是最新，检查于 ${SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(u.checkedAt))}", ChipKind.OK)
                    "available" -> Chip("有新版本 ${u.latest}", ChipKind.WARN)
                    "downloading" -> Chip("正在下载 ${u.progress}%")
                    "installing" -> Chip("正在安装…")
                    "failed" -> Chip("出错", ChipKind.ERR)
                }
            }
            if (u.state == "downloading") LinearProgressIndicator(progress = { u.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (u.message.isNotBlank() && u.state != "up_to_date") {
                Text(u.message, fontSize = 13.sp, color = if (u.state == "failed") MaterialTheme.colorScheme.error else muted)
            }
            if (u.state == "available") {
                Text("下载并校验后交给系统安装程序（需要允许本应用安装应用）；安装时应用会关闭，正在进行的远程连接会断开。", fontSize = 12.5.sp, color = muted)
                Row {
                    if (u.notes.isNotBlank()) TextButton(onClick = { notes = !notes }) { Text(if (notes) "收起更新内容" else "查看更新内容") }
                    if (u.page.isNotBlank()) TextButton(onClick = { onPage(u.page) }) { Text("发布页") }
                }
                if (notes) {
                    Text(
                        u.notes,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                        fontSize = 12.5.sp,
                    )
                }
            }
        }
    }
}
