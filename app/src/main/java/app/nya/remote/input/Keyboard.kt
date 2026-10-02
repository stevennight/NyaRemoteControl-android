package app.nya.remote.input

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keys and text to the host, with sticky modifiers from the extra-keys bar:
 * tap Ctrl, then C, and the host gets Ctrl+C.
 *
 * Text the phone's IME commits (any language) goes, in order of preference:
 * as Unicode text ([sendText], protocol 1.5 hosts); as keys when a US keyboard
 * can type all of it; otherwise through the host clipboard and Ctrl+V
 * ([paste], works with every host version).
 */
class KeyboardController(
    private val send: (ScanKey, Boolean) -> Unit,
    private val sendText: (String) -> Unit = {},
    private val paste: (String) -> Unit = {},
) {
    /** The host types Unicode text (TEXT_INPUT negotiated). */
    var textInput = false

    /** Modifiers latched for the next key. */
    var sticky by mutableStateOf(emptySet<ScanKey>())
        private set

    fun toggleSticky(k: ScanKey) {
        sticky = if (k in sticky) sticky - k else sticky + k
    }

    /** Press and release [k] with the latched modifiers (and Shift if asked). */
    fun tap(k: ScanKey, shift: Boolean = false) {
        val mods = sticky.toMutableList()
        if (shift && KeyMap.LSHIFT !in mods) mods += KeyMap.LSHIFT
        mods.forEach { send(it, true) }
        send(k, true)
        send(k, false)
        mods.asReversed().forEach { send(it, false) }
        if (sticky.isNotEmpty()) sticky = emptySet()
    }

    /** A shortcut: press in order, release in reverse. */
    fun combo(vararg keys: ScanKey) {
        keys.forEach { send(it, true) }
        keys.reversed().forEach { send(it, false) }
    }

    fun type(text: CharSequence) {
        if (text.isEmpty()) return
        val typable = text.all { KeyMap.stroke(it) != null }
        when {
            // Shortcuts (a latched modifier) and single ASCII keys go as key presses.
            sticky.isNotEmpty() || (typable && (!textInput || text.length == 1)) -> {
                for (c in text) KeyMap.stroke(c)?.let { tap(it.key, it.shift) }
            }
            textInput -> {
                // Line breaks and tabs are keys to applications; the rest is text.
                val run = StringBuilder()
                fun flush() {
                    if (run.isNotEmpty()) sendText(run.toString())
                    run.clear()
                }
                for (c in text) {
                    when (c) {
                        '\n' -> { flush(); tap(KeyMap.ENTER) }
                        '\t' -> { flush(); tap(KeyMap.TAB) }
                        '\r' -> {}
                        else -> run.append(c)
                    }
                }
                flush()
            }
            // Chinese etc. on a host without text input: through its clipboard.
            else -> paste(text.toString())
        }
    }

    fun key(keyCode: Int, down: Boolean): Boolean {
        val k = KeyMap.forKeyCode(keyCode) ?: return false
        send(k, down)
        return true
    }
}

/**
 * An invisible editor the soft keyboard types into. A normal text field to
 * the IME, so its own languages (pinyin, handwriting, voice) work; only the
 * committed result goes to the host, the composition stays in the IME.
 */
class RemoteKeyboardView(context: Context, private val keys: KeyboardController) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = PADDING.length
        outAttrs.initialSelEnd = PADDING.length
        return Connection()
    }

    private inner class Connection : BaseInputConnection(this, false) {
        private var composing = ""

        // Some IMEs send backspace only when they believe there is text before
        // the cursor: pretend there is (the host's text is unknown here).
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = PADDING.takeLast(length.coerceAtLeast(0))

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            keys.type(text)
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) keys.type(composing)
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isNotEmpty()) return true // the IME edits its own composition
            repeat(beforeLength.coerceAtMost(64)) { keys.tap(KeyMap.BACKSPACE) }
            repeat(afterLength.coerceAtMost(64)) { keys.tap(KeyMap.DELETE) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val down = event.action == KeyEvent.ACTION_DOWN
            val k = KeyMap.forKeyCode(event.keyCode)
            if (k != null) {
                // Soft keys come as down/up pairs: send them as one tap with the latched modifiers.
                if (down) keys.tap(k)
                return true
            }
            val c = event.unicodeChar
            if (down && c != 0) keys.type(c.toChar().toString())
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            keys.tap(KeyMap.ENTER)
            return true
        }
    }

    private companion object {
        const val PADDING = "                                "
    }
}
