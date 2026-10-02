package app.nya.remote.input

import android.view.KeyEvent

/** A PC/AT set-1 scancode (what the host injects); [extended] = E0 prefix. */
data class ScanKey(val code: Int, val extended: Boolean = false)

/** One character typed on a US layout: the key and whether Shift is held. */
data class Stroke(val key: ScanKey, val shift: Boolean)

/**
 * Android key codes and typed characters to host scancodes. The host turns
 * scancodes into characters with its own keyboard layout and IME, so Chinese
 * is typed with the host's input method (pinyin letters go over as keys).
 */
object KeyMap {
    val ESC = ScanKey(0x01)
    val BACKSPACE = ScanKey(0x0E)
    val TAB = ScanKey(0x0F)
    val ENTER = ScanKey(0x1C)
    val LCTRL = ScanKey(0x1D)
    val LSHIFT = ScanKey(0x2A)
    val LALT = ScanKey(0x38)
    val SPACE = ScanKey(0x39)
    val LWIN = ScanKey(0x5B, true)
    val DELETE = ScanKey(0x53, true)
    val INSERT = ScanKey(0x52, true)
    val HOME = ScanKey(0x47, true)
    val END = ScanKey(0x4F, true)
    val PAGE_UP = ScanKey(0x49, true)
    val PAGE_DOWN = ScanKey(0x51, true)
    val UP = ScanKey(0x48, true)
    val DOWN = ScanKey(0x50, true)
    val LEFT = ScanKey(0x4B, true)
    val RIGHT = ScanKey(0x4D, true)
    val PRINT_SCREEN = ScanKey(0x37, true)
    val MENU = ScanKey(0x5D, true)

    fun function(n: Int): ScanKey = when (n) {
        in 1..10 -> ScanKey(0x3A + n)
        11 -> ScanKey(0x57)
        12 -> ScanKey(0x58)
        else -> throw IllegalArgumentException("F$n")
    }

    private val letters = "qwertyuiop".mapIndexed { i, c -> c to 0x10 + i } +
        "asdfghjkl".mapIndexed { i, c -> c to 0x1E + i } +
        "zxcvbnm".mapIndexed { i, c -> c to 0x2C + i }

    private val plain: Map<Char, Int> = buildMap {
        letters.forEach { (c, s) -> put(c, s) }
        "1234567890".forEachIndexed { i, c -> put(c, 0x02 + i) }
        put('-', 0x0C); put('=', 0x0D); put('[', 0x1A); put(']', 0x1B); put(';', 0x27)
        put('\'', 0x28); put('`', 0x29); put('\\', 0x2B); put(',', 0x33); put('.', 0x34)
        put('/', 0x35); put(' ', 0x39); put('\n', 0x1C); put('\t', 0x0F)
    }

    private val shifted: Map<Char, Int> = buildMap {
        letters.forEach { (c, s) -> put(c.uppercaseChar(), s) }
        "!@#$%^&*()".forEachIndexed { i, c -> put(c, 0x02 + i) }
        put('_', 0x0C); put('+', 0x0D); put('{', 0x1A); put('}', 0x1B); put(':', 0x27)
        put('"', 0x28); put('~', 0x29); put('|', 0x2B); put('<', 0x33); put('>', 0x34); put('?', 0x35)
    }

    /** Null for characters a US keyboard can't type (e.g. Chinese). */
    fun stroke(c: Char): Stroke? {
        plain[c]?.let { return Stroke(ScanKey(it), false) }
        shifted[c]?.let { return Stroke(ScanKey(it), true) }
        return null
    }

    private val keyCodes: Map<Int, ScanKey> = buildMap {
        for (i in 0..25) {
            val c = 'a' + i
            put(KeyEvent.KEYCODE_A + i, ScanKey(plain.getValue(c)))
        }
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, ScanKey(plain.getValue('0' + i)))
        for (i in 1..12) put(KeyEvent.KEYCODE_F1 + i - 1, function(i))
        put(KeyEvent.KEYCODE_ESCAPE, ESC)
        put(KeyEvent.KEYCODE_DEL, BACKSPACE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, DELETE)
        put(KeyEvent.KEYCODE_TAB, TAB)
        put(KeyEvent.KEYCODE_ENTER, ENTER)
        put(KeyEvent.KEYCODE_SPACE, SPACE)
        put(KeyEvent.KEYCODE_MINUS, ScanKey(0x0C))
        put(KeyEvent.KEYCODE_EQUALS, ScanKey(0x0D))
        put(KeyEvent.KEYCODE_LEFT_BRACKET, ScanKey(0x1A))
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, ScanKey(0x1B))
        put(KeyEvent.KEYCODE_SEMICOLON, ScanKey(0x27))
        put(KeyEvent.KEYCODE_APOSTROPHE, ScanKey(0x28))
        put(KeyEvent.KEYCODE_GRAVE, ScanKey(0x29))
        put(KeyEvent.KEYCODE_BACKSLASH, ScanKey(0x2B))
        put(KeyEvent.KEYCODE_COMMA, ScanKey(0x33))
        put(KeyEvent.KEYCODE_PERIOD, ScanKey(0x34))
        put(KeyEvent.KEYCODE_SLASH, ScanKey(0x35))
        put(KeyEvent.KEYCODE_SHIFT_LEFT, LSHIFT)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, ScanKey(0x36))
        put(KeyEvent.KEYCODE_CTRL_LEFT, LCTRL)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, ScanKey(0x1D, true))
        put(KeyEvent.KEYCODE_ALT_LEFT, LALT)
        put(KeyEvent.KEYCODE_ALT_RIGHT, ScanKey(0x38, true))
        put(KeyEvent.KEYCODE_META_LEFT, LWIN)
        put(KeyEvent.KEYCODE_META_RIGHT, ScanKey(0x5C, true))
        put(KeyEvent.KEYCODE_CAPS_LOCK, ScanKey(0x3A))
        put(KeyEvent.KEYCODE_DPAD_UP, UP)
        put(KeyEvent.KEYCODE_DPAD_DOWN, DOWN)
        put(KeyEvent.KEYCODE_DPAD_LEFT, LEFT)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, RIGHT)
        put(KeyEvent.KEYCODE_MOVE_HOME, HOME)
        put(KeyEvent.KEYCODE_MOVE_END, END)
        put(KeyEvent.KEYCODE_PAGE_UP, PAGE_UP)
        put(KeyEvent.KEYCODE_PAGE_DOWN, PAGE_DOWN)
        put(KeyEvent.KEYCODE_INSERT, INSERT)
        put(KeyEvent.KEYCODE_SYSRQ, PRINT_SCREEN)
        put(KeyEvent.KEYCODE_MENU, MENU)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, ScanKey(0x1C, true))
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, ScanKey(0x35, true))
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, ScanKey(0x37))
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, ScanKey(0x4A))
        put(KeyEvent.KEYCODE_NUMPAD_ADD, ScanKey(0x4E))
        put(KeyEvent.KEYCODE_NUMPAD_DOT, ScanKey(0x53))
        val pad = intArrayOf(0x52, 0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49)
        for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, ScanKey(pad[i]))
    }

    /** Null for keys the host has no equivalent for (volume, back, ...). */
    fun forKeyCode(keyCode: Int): ScanKey? = keyCodes[keyCode]
}
