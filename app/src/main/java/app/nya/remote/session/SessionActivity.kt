package app.nya.remote.session

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.nya.remote.BuildConfig
import app.nya.remote.core.CoreEvent
import app.nya.remote.core.NativeCore
import app.nya.remote.core.StartConfig
import app.nya.remote.data.ControlMode
import app.nya.remote.data.Host
import app.nya.remote.data.HostStore
import app.nya.remote.data.SettingsStore
import app.nya.remote.input.GestureConfig
import app.nya.remote.input.GestureEngine
import app.nya.remote.input.KeyboardController
import app.nya.remote.input.Pt
import app.nya.remote.input.RemoteInput
import app.nya.remote.input.RemoteKeyboardView
import app.nya.remote.input.ScanKey
import app.nya.remote.input.TouchAction
import app.nya.remote.input.Viewport
import app.nya.remote.ui.NyaTheme
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** The remote screen: video, cursor, touch input and the overlay UI. */
class SessionActivity : ComponentActivity(), SessionActions {
    private lateinit var hosts: HostStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var host: Host
    private var pairCode: String? = null

    private lateinit var ui: SessionUi
    private val viewport = Viewport()
    private lateinit var root: SessionLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var cursorView: CursorView
    private lateinit var keyboardView: RemoteKeyboardView
    private lateinit var keys: KeyboardController
    private lateinit var gestures: GestureEngine

    private var session: RemoteSession? = null
    private var decoder: VideoDecoder? = null
    private var audio: AudioPlayer? = null
    private var decoders: List<Decoder> = emptyList()
    private var surfaceReady = false
    private var guideShown = false
    private var untypableHinted = false
    private val main = Handler(Looper.getMainLooper())
    private val longPress = Runnable { gestures.timeout(SystemClock.uptimeMillis()); scheduleLongPress() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hosts = HostStore(this)
        settingsStore = SettingsStore(this)
        host = intent.getStringExtra(EXTRA_HOST_ID)?.let { hosts.get(it) } ?: run {
            finish()
            return
        }
        pairCode = intent.getStringExtra(EXTRA_PAIR_CODE)
        val settings = settingsStore.load()
        ui = SessionUi(settings.controlMode, settings.showStats, settings.gameMode, settings.showGuideOnConnect)
        ui.hostName = host.displayName

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            // Use the whole screen, notch area included.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        keys = KeyboardController { k, down -> session?.key(k.code, k.extended, down) }
        keys.onUntypable = {
            if (!untypableHinted) {
                untypableHinted = true
                Toast.makeText(this, "中文等字符请切换到英文键盘，用电脑的输入法输入", Toast.LENGTH_LONG).show()
            }
        }
        val density = resources.displayMetrics.density
        gestures = GestureEngine(
            viewport,
            remoteInput,
            GestureConfig(touchSlop = 10 * density, scrollPxPerNotch = 40 * density),
        )
        gestures.mode = ui.controlMode
        buildViews()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ui.guideOpen -> ui.guideOpen = false
                    ui.panelOpen -> ui.panelOpen = false
                    else -> ui.panelOpen = true
                }
            }
        })

        decoders = DecoderCaps.detect()
        connect()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        root = SessionLayout(this)
        surfaceView = SurfaceView(this)
        cursorView = CursorView(this, viewport)
        keyboardView = RemoteKeyboardView(this, keys)
        val overlay = ComposeView(this).apply {
            setContent { NyaTheme { SessionOverlay(ui, keys, this@SessionActivity) } }
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1))
        root.addView(cursorView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(keyboardView, FrameLayout.LayoutParams(1, 1))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        viewport.onChange = {
            root.requestLayout()
            cursorView.invalidate()
        }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startDecoder()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                stopDecoder()
            }
        })

        // The soft keyboard covers the lower part: let the picture pan above it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val open = insets.isVisible(WindowInsetsCompat.Type.ime())
            ui.keyboardOpen = open
            viewport.setBottomInset(if (open) ime.toFloat() + 46 * resources.displayMetrics.density else 0f)
            if (open) viewport.ensureVisible(gestures.cursorX, gestures.cursorY, 48 * resources.displayMetrics.density)
            insets
        }
    }

    // ------------------------------------------------------------ connection

    private fun screenSize(): Point {
        val p = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(p)
        }
        return p
    }

    private fun refreshRate(): Int {
        @Suppress("DEPRECATION")
        val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        return (d?.refreshRate ?: 60f).roundToInt()
    }

    private fun currentStreamOptions() = settingsStore.load().let { s ->
        val size = screenSize()
        streamOptions(s.copy(gameMode = ui.gameMode), size.x, size.y, refreshRate())
    }

    private fun connect() {
        val s = settingsStore.load()
        val config = StartConfig(
            address = host.address,
            pinned = host.fingerprint,
            pairCode = pairCode,
            clientName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            clientVersion = BuildConfig.VERSION_NAME,
            decoders = decoders.map { it.cap() },
            maxFps = minOf(refreshRate(), s.maxFps),
            stream = currentStreamOptions(),
        )
        pairCode = null
        ui.status = Status.Connecting
        val sess = try {
            RemoteSession(filesDir.absolutePath, config, ::onEvent)
        } catch (e: Exception) {
            ui.status = Status.Disconnected(e.message ?: e.toString())
            return
        }
        session = sess
        if (surfaceReady) startDecoder()
        if (s.audio) audio = AudioPlayer(sess).also { it.start() }
    }

    private fun onEvent(e: CoreEvent) {
        when (e) {
            CoreEvent.Connecting -> ui.status = Status.Connecting
            CoreEvent.NeedPairing -> ui.needPairing = true
            is CoreEvent.Connected -> {
                ui.status = Status.Connected
                ui.needPairing = false
                host = host.copy(
                    fingerprint = e.fingerprint,
                    fingerprintShort = e.fingerprintShort,
                    lastConnected = System.currentTimeMillis(),
                    name = host.name.ifBlank { e.serverName },
                )
                hosts.put(host)
                if (ui.hostName.isBlank() || ui.hostName == host.address) ui.hostName = host.displayName
                if (ui.showGuideOnConnect && !guideShown) {
                    guideShown = true
                    ui.guideOpen = true
                }
            }
            is CoreEvent.Reconnecting -> ui.status = Status.Reconnecting(e.message)
            is CoreEvent.Disconnected -> {
                ui.needPairing = false
                ui.status = Status.Disconnected(e.message)
            }
            is CoreEvent.SessionInfo -> if (e.hostName.isNotBlank()) ui.hostName = host.name.ifBlank { e.hostName }
            is CoreEvent.StreamStarted -> {
                ui.stream = e
                cursorView.setSource(e.sourceWidth, e.sourceHeight)
            }
            is CoreEvent.StreamError -> Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            is CoreEvent.Role -> ui.role = e
            is CoreEvent.CursorShape -> cursorView.addShape(e.id, e.width, e.height, e.hotX, e.hotY, e.rgbaBase64)
            is CoreEvent.CursorState -> {
                cursorView.setState(e.shapeId, e.visible, e.x, e.y)
                cursorView.remotePosition()?.let { (rx, ry) -> gestures.hostCursor(rx, ry) }
            }
            is CoreEvent.Clipboard -> if (settingsStore.load().syncClipboard) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("NyaRemoteControl", e.text))
            }
            is CoreEvent.Stats -> ui.stats = e.line
        }
    }

    private fun startDecoder() {
        val s = session ?: return
        if (decoder != null || !surfaceReady) return
        decoder = VideoDecoder(s, surfaceView.holder.surface, decoders) { w, h ->
            main.post {
                surfaceView.holder.setFixedSize(w, h)
                viewport.setContent(w.toFloat(), h.toFloat())
            }
        }.also { it.start() }
    }

    private fun stopDecoder() {
        decoder?.stop()
        decoder = null
    }

    private fun closeSession() {
        stopDecoder()
        audio?.stop()
        audio = null
        val s = session ?: return
        session = null
        thread(name = "nya-close") { s.close() }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::ui.isInitialized) closeSession()
        super.onDestroy()
    }

    // ------------------------------------------------------------ input

    private val remoteInput = object : RemoteInput {
        override fun moveTo(rx: Float, ry: Float) {
            session?.mouseTo(rx, ry)
            cursorView.moveLocal(rx, ry)
        }

        override fun button(button: Int, down: Boolean) {
            session?.mouseButton(button, down)
        }

        override fun wheel(dx: Int, dy: Int) {
            session?.wheel(dx, dy)
        }

        override fun toggleKeyboard() = this@SessionActivity.toggleKeyboard()

        override fun feedback() {
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun scheduleLongPress() {
        main.removeCallbacks(longPress)
        val at = gestures.longPressAt ?: return
        main.postAtTime(longPress, at)
    }

    private fun onTouch(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) || ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            return onMouse(ev)
        }
        val action = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            else -> return true
        }
        val pts = List(ev.pointerCount) { i -> Pt(ev.getPointerId(i), ev.getX(i), ev.getY(i)) }
        gestures.onTouch(action, pts, ev.getPointerId(ev.actionIndex), ev.eventTime)
        scheduleLongPress()
        return true
    }

    /** A real mouse (USB / Bluetooth): absolute position and its buttons as they are. */
    private var mouseButtons = 0

    private fun onMouse(ev: MotionEvent): Boolean {
        val (rx, ry) = viewport.toRemote(ev.x, ev.y)
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE,
            -> remoteInput.moveTo(rx, ry)
            MotionEvent.ACTION_SCROLL -> {
                val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
                session?.wheel((h * 120).roundToInt(), (v * 120).roundToInt())
            }
        }
        val now = ev.buttonState
        for ((mask, button) in listOf(
            MotionEvent.BUTTON_PRIMARY to NativeCore.BUTTON_LEFT,
            MotionEvent.BUTTON_SECONDARY to NativeCore.BUTTON_RIGHT,
            MotionEvent.BUTTON_TERTIARY to NativeCore.BUTTON_MIDDLE,
        )) {
            if ((now and mask) != (mouseButtons and mask)) session?.mouseButton(button, now and mask != 0)
        }
        mouseButtons = now
        return true
    }

    // Hardware keyboards; the soft keyboard goes through RemoteKeyboardView's input connection.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        (fromKeyboard(event) && keys.key(keyCode, true)) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        (fromKeyboard(event) && keys.key(keyCode, false)) || super.onKeyUp(keyCode, event)

    private fun fromKeyboard(event: KeyEvent) =
        event.device?.isVirtual == false && event.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD

    /** Lays the SurfaceView out where the viewport puts the picture; everything else fills the screen. */
    private inner class SessionLayout(context: Context) : FrameLayout(context) {
        init {
            setBackgroundColor(0xFF000000.toInt())
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            viewport.setView(w.toFloat(), h.toFloat())
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val l = viewport.left.roundToInt()
            val t = viewport.top.roundToInt()
            surfaceView.layout(l, t, l + viewport.width.roundToInt(), t + viewport.height.roundToInt())
            keyboardView.layout(0, 0, 1, 1)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = onTouch(event)

        override fun onGenericMotionEvent(event: MotionEvent): Boolean =
            if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) onMouse(event) else super.onGenericMotionEvent(event)
    }

    // ------------------------------------------------------------ SessionActions

    override fun setControlMode(mode: ControlMode) {
        ui.controlMode = mode
        gestures.mode = mode
        settingsStore.update { it.copy(controlMode = mode) }
    }

    override fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (ui.keyboardOpen) {
            imm.hideSoftInputFromWindow(keyboardView.windowToken, 0)
            keyboardView.clearFocus()
        } else {
            keyboardView.requestFocus()
            imm.showSoftInput(keyboardView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun setGameMode(game: Boolean) {
        ui.gameMode = game
        session?.setGameMode(game)
        settingsStore.update { it.copy(gameMode = game) }
    }

    override fun setShowStats(show: Boolean) {
        ui.showStats = show
        settingsStore.update { it.copy(showStats = show) }
    }

    override fun setShowGuideOnConnect(show: Boolean) {
        ui.showGuideOnConnect = show
        settingsStore.update { it.copy(showGuideOnConnect = show) }
    }

    override fun sendSas() {
        session?.sendSas()
        ui.panelOpen = false
    }

    override fun shortcut(vararg keys: ScanKey) {
        this.keys.combo(*keys)
        ui.panelOpen = false
    }

    override fun sendClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "手机剪贴板里没有文字", Toast.LENGTH_SHORT).show()
            return
        }
        session?.sendClipboard(text)
        Toast.makeText(this, "已发送到电脑剪贴板", Toast.LENGTH_SHORT).show()
    }

    override fun takeControl(kick: Boolean) {
        session?.takeControl(kick)
        ui.panelOpen = false
    }

    override fun resetZoom() = viewport.reset()

    override fun submitPairCode(code: String?) {
        ui.needPairing = false
        session?.providePairCode(code?.trim())
    }

    override fun retry() {
        closeSession()
        connect()
    }

    override fun disconnect() {
        ui.panelOpen = false
        session?.releaseAll()
        closeSession()
        finish()
    }

    companion object {
        const val EXTRA_HOST_ID = "host"
        const val EXTRA_PAIR_CODE = "pairCode"

        fun intent(context: Context, hostId: String, pairCode: String?) =
            Intent(context, SessionActivity::class.java).putExtra(EXTRA_HOST_ID, hostId).putExtra(EXTRA_PAIR_CODE, pairCode)
    }
}
