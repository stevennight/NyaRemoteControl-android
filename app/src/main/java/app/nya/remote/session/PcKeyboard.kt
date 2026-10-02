package app.nya.remote.session

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.input.KeyMap
import app.nya.remote.input.KeyboardController
import app.nya.remote.input.ScanKey
import app.nya.remote.ui.Accent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One key: what it shows (normal / with Shift), what it sends, how wide it is. */
private class PcKey(
    val label: String,
    val key: ScanKey?,
    val weight: Float = 1f,
    val shifted: String? = null,
    /** Ctrl / Alt / Shift / Win: latch for the next key. */
    val modifier: Boolean = false,
    /** Held: repeats like a real keyboard (backspace, arrows). */
    val repeat: Boolean = false,
    /** Not a key: switch to the phone's input method. */
    val switch: Boolean = false,
    val hide: Boolean = false,
)

private fun sc(code: Int, ext: Boolean = false) = ScanKey(code, ext)

private fun chars(s: String, shifted: String) = s.mapIndexed { i, c ->
    PcKey(c.uppercase(), KeyMap.stroke(c)!!.key, shifted = shifted[i].toString().takeIf { shifted[i] != c.uppercaseChar() })
}

private val rows: List<List<PcKey>> = listOf(
    listOf(PcKey("Esc", KeyMap.ESC)) + (1..12).map { PcKey("F$it", KeyMap.function(it)) } +
        listOf(PcKey("PrtSc", KeyMap.PRINT_SCREEN), PcKey("Del", KeyMap.DELETE, repeat = true)),
    chars("`1234567890-=", "~!@#$%^&*()_+") + PcKey("⌫", KeyMap.BACKSPACE, 1.8f, repeat = true),
    listOf(PcKey("Tab", KeyMap.TAB, 1.5f)) + chars("qwertyuiop[]\\", "QWERTYUIOP{}|").let { it.dropLast(1) + PcKey("\\", sc(0x2B), 1.3f, shifted = "|") },
    listOf(PcKey("Caps", sc(0x3A), 1.8f)) + chars("asdfghjkl;'", "ASDFGHJKL:\"") + PcKey("Enter", KeyMap.ENTER, 2.2f),
    listOf(PcKey("Shift", KeyMap.LSHIFT, 2.3f, modifier = true)) + chars("zxcvbnm,./", "ZXCVBNM<>?") +
        listOf(PcKey("Shift", KeyMap.LSHIFT, 1.5f, modifier = true), PcKey("↑", KeyMap.UP, repeat = true)),
    listOf(
        PcKey("Ctrl", KeyMap.LCTRL, 1.4f, modifier = true),
        PcKey("Win", KeyMap.LWIN, 1.2f, modifier = true),
        PcKey("Alt", KeyMap.LALT, 1.2f, modifier = true),
        PcKey("", KeyMap.SPACE, 5.5f),
        PcKey("Alt", KeyMap.LALT, 1.2f, modifier = true),
        PcKey("Ctrl", KeyMap.LCTRL, 1.2f, modifier = true),
        PcKey("输入法", null, 1.4f, switch = true),
        PcKey("←", KeyMap.LEFT, repeat = true),
        PcKey("↓", KeyMap.DOWN, repeat = true),
        PcKey("→", KeyMap.RIGHT, repeat = true),
        PcKey("收起", null, 1.2f, hide = true),
    ),
)

/**
 * A PC-layout keyboard drawn over the bottom of the remote screen, sending
 * scancodes like a real keyboard (down on press, up on release), for when the
 * phone's input method gets in the way (games, shortcuts, terminals).
 * [onHeight] reports its height in pixels so the picture can move above it.
 */
@Composable
fun PcKeyboard(keys: KeyboardController, onSwitch: () -> Unit, onHide: () -> Unit, onHeight: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shift = KeyMap.LSHIFT in keys.sticky
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xF2202226))
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .onSizeChanged { onHeight(it.height) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().height(if (i == 0) 30.dp else 38.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { k ->
                    val label = if (shift && k.shifted != null) k.shifted else k.label
                    val latched = k.modifier && k.key in keys.sticky
                    KeyCap(label, k.weight, latched, k.switch || k.hide) {
                        when {
                            k.switch -> Press.Tap(onSwitch)
                            k.hide -> Press.Tap(onHide)
                            k.modifier -> Press.Tap { keys.toggleSticky(k.key!!) }
                            else -> Press.Hold(k.repeat, down = { keys.press(k.key!!) }, repeatDown = { keys.repeat(k.key!!) }, up = { keys.release(k.key!!) })
                        }
                    }
                }
            }
        }
    }
}

private sealed interface Press {
    class Tap(val action: () -> Unit) : Press
    class Hold(val repeat: Boolean, val down: () -> Unit, val repeatDown: () -> Unit, val up: () -> Unit) : Press
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyCap(label: String, weight: Float, latched: Boolean, special: Boolean, press: () -> Press) {
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        pressed || latched -> Accent
        special -> Color(0xFF2E4A7A)
        else -> Color(0xFF3A3D43)
    }
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .pointerInput(label) {
                detectTapGestures(onPress = {
                    pressed = true
                    when (val p = press()) {
                        is Press.Tap -> {
                            if (tryAwaitRelease()) p.action()
                        }
                        is Press.Hold -> coroutineScope {
                            p.down()
                            // A real keyboard repeats a held key: first after 400 ms, then every 40 ms.
                            val repeater = if (p.repeat) {
                                launch {
                                    delay(400)
                                    while (true) {
                                        p.repeatDown()
                                        delay(40)
                                    }
                                }
                            } else {
                                null
                            }
                            tryAwaitRelease()
                            repeater?.cancel()
                            p.up()
                        }
                    }
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = if (label.length > 3) 11.sp else 14.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}
