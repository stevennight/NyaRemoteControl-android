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
 * Keys to the host, with sticky modifiers from the extra-keys bar: tap Ctrl,
 * then C, and the host gets Ctrl+C.
 */
class KeyboardController(private val send: (ScanKey, Boolean) -> Unit) {
    /** Modifiers latched for the next key. */
    var sticky by mutableStateOf(emptySet<ScanKey>())
        private set

    /** Characters the host keyboard can't type (shown as a hint once). */
    var onUntypable: ((Char) -> Unit)? = null

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
        for (c in text) {
            val s = KeyMap.stroke(c)
            if (s == null) onUntypable?.invoke(c) else tap(s.key, s.shift)
        }
    }

    fun key(keyCode: Int, down: Boolean): Boolean {
        val k = KeyMap.forKeyCode(keyCode) ?: return false
        send(k, down)
        return true
    }
}

/**
 * An invisible editor the soft keyboard types into. Asks for a plain
 * (password-like) keyboard without suggestions so keys arrive one by one.
 */
class RemoteKeyboardView(context: Context, private val keys: KeyboardController) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return Connection()
    }

    private inner class Connection : BaseInputConnection(this, false) {
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing("")
            keys.type(text)
            return true
        }

        // IMEs that compose anyway (e.g. word suggestions): mirror the
        // composition on the host with backspaces, so only final text stays.
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        private fun replaceComposing(now: String) {
            val common = composing.commonPrefixWith(now).length
            repeat(composing.length - common) { keys.tap(KeyMap.BACKSPACE) }
            keys.type(now.substring(common))
            composing = now
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
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
}
