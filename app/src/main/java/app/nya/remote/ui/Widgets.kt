package app.nya.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Building blocks of the launcher pages, after the Windows client's page
// (common/web: card groups of fields, segmented choices, switches, chips).

/** A card with an optional heading and fields separated by thin lines. */
@Composable
fun Group(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        if (title != null) {
            Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        content()
    }
}

/**
 * One setting: title and explanation, the control on the right, or below
 * when [stacked] (wide controls on a narrow phone).
 */
@Composable
fun Field(
    title: String,
    help: String? = null,
    enabled: Boolean = true,
    stacked: Boolean = false,
    divider: Boolean = true,
    control: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)) {
        if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldText(title, help)
                control?.invoke()
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = 12.dp)) { FieldText(title, help) }
                control?.invoke()
            }
        }
    }
}

@Composable
private fun FieldText(title: String, help: String?) {
    Column {
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
        if (!help.isNullOrBlank()) Text(help, fontSize = 12.5.sp, color = muted, lineHeight = 17.sp)
    }
}

/** A switch field; the whole row toggles. */
@Composable
fun SwitchField(title: String, help: String? = null, checked: Boolean, enabled: Boolean = true, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    Box(Modifier.clickable(enabled = enabled) { onChange(!checked) }) {
        Field(title, help, enabled, divider = divider) { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) }
    }
}

/** Segmented choice (the web page's `Seg`): wraps on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Seg(options: List<Pair<T, String>>, value: T, enabled: Boolean = true, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            val on = v == value
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { onPick(v) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    fontSize = 13.5.sp,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** A drop-down (the web page's `<select>`). */
@Composable
fun <T> Select(options: List<Pair<T, String>>, value: T, enabled: Boolean = true, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .widthIn(min = 120.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.find { it.first == value }?.second ?: "", fontSize = 13.5.sp, modifier = Modifier.weight(1f, fill = false))
            Text("  ▾", fontSize = 12.sp, color = muted)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (v == value) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onPick(v)
                    },
                )
            }
        }
    }
}

enum class ChipKind { PLAIN, OK, WARN, ERR }

@Composable
fun Chip(text: String, kind: ChipKind = ChipKind.PLAIN) {
    val (fg, bg) = when (kind) {
        ChipKind.OK -> Ok to Ok.copy(alpha = 0.13f)
        ChipKind.WARN -> Warn to Warn.copy(alpha = 0.14f)
        ChipKind.ERR -> Danger to Danger.copy(alpha = 0.13f)
        ChipKind.PLAIN -> muted to MaterialTheme.colorScheme.surfaceVariant
    }
    Text(
        text,
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
        color = fg,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** A coloured banner (info / error) above a page. */
@Composable
fun Banner(text: String, error: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.10f)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), fontSize = 13.5.sp, color = if (error) c else MaterialTheme.colorScheme.onSurface)
        action?.invoke()
    }
}

fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toFloat())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toFloat())
    else -> "$bytes B"
}

/** "3 分钟前" etc. (the web page's `ago`); [ms] = 0: never. */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "从未连接"
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "刚刚"
        s < 3600 -> "${s / 60} 分钟前"
        s < 86400 -> "${s / 3600} 小时前"
        s < 86400 * 30 -> "${s / 86400} 天前"
        else -> java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.CHINA).format(java.util.Date(ms))
    }
}

/** Stable colour of a device card, from its name (same hash as the web page's `hue`). */
fun hue(name: String): Float {
    var h = 7L
    for (c in name) h = (h * 31 + c.code) and 0xFFFF_FFFFL
    return (h % 360).toFloat()
}
