package app.nya.remote.session

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.nya.remote.BuildConfig
import app.nya.remote.core.CoreEvent
import app.nya.remote.core.NativeCore
import app.nya.remote.core.StartConfig
import app.nya.remote.data.AppConfig
import app.nya.remote.data.ConfigStore
import app.nya.remote.data.ConnSettings
import app.nya.remote.data.ControlMode
import app.nya.remote.data.DisplayChoice
import app.nya.remote.data.Downloads
import app.nya.remote.data.HostStore
import app.nya.remote.data.Shares
import app.nya.remote.input.GestureConfig
import app.nya.remote.input.GestureEngine
import app.nya.remote.input.Gamepads
import app.nya.remote.input.KeyMap
import app.nya.remote.input.KeyboardController
import app.nya.remote.input.Pt
import app.nya.remote.input.RemoteInput
import app.nya.remote.input.RemoteKeyboardView
import app.nya.remote.input.ScanKey
import app.nya.remote.input.TouchAction
import app.nya.remote.input.Viewport
import app.nya.remote.ui.NyaTheme
import app.nya.remote.ui.connectionModes
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** The remote screen: video, cursor, touch input and the overlay UI. */
class SessionActivity : ComponentActivity(), SessionActions {
    private lateinit var hosts: HostStore
    private lateinit var configStore: ConfigStore
    private lateinit var config: AppConfig
    /** The host's address (saved hosts are matched by it). */
    private lateinit var address: String
    /** Name for a host that is not saved yet (from the add dialog). */
    private var newName: String? = null
    /** Connect once without the saved pin (the user asked to check the host again). */
    private var reverify = false

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
    private lateinit var gamepads: Gamepads
    private lateinit var clipboard: ClipboardBridge
    private lateinit var usbSharing: UsbSharing
    private var mic: MicCapture? = null
    private var streamHdr = false
    /** Keys and pastes leave in order (a paste waits for the host clipboard). */
    private val keyOut = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMic() else Toast.makeText(this, "没有麦克风权限", Toast.LENGTH_SHORT).show()
    }
    private val inputManager by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = checkMouse()
        override fun onInputDeviceChanged(deviceId: Int) = checkMouse()
        override fun onInputDeviceRemoved(deviceId: Int) {
            gamepads.removed(deviceId)
            checkMouse()
        }
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> sendPicked(uris) }
    private val main = Handler(Looper.getMainLooper())
    private val longPress = Runnable { gestures.timeout(SystemClock.uptimeMillis()); scheduleLongPress() }

    /** The settings of this session: what the panel shows and changes. */
    private var sd: ConnSettings
        get() = ui.settings
        set(v) {
            ui.settings = v
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hosts = HostStore(this)
        configStore = ConfigStore(this)
        config = configStore.load()
        address = intent.getStringExtra(EXTRA_ADDRESS)?.trim().orEmpty()
        if (address.isEmpty()) {
            finish()
            return
        }
        newName = intent.getStringExtra(EXTRA_NAME)?.trim()?.ifBlank { null }
        val book = hosts.book()
        val settings = book.settingsFor(address, config.defaults)
        ui = SessionUi(settings, config.showStats, config.showGuideOnConnect)
        ui.hostName = book.byAddress(address)?.displayName ?: newName ?: address

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

        keys = KeyboardController(
            send = { k, down -> keyOut.execute { session?.key(k.code, k.extended, down) } },
            sendText = { t -> keyOut.execute { session?.text(t) } },
            paste = { t ->
                // Older hosts can't type Unicode: put the text on their clipboard, then Ctrl+V.
                keyOut.execute {
                    val s = session ?: return@execute
                    s.sendClipboard(t)
                    Thread.sleep(150)
                    s.key(KeyMap.LCTRL.code, false, true)
                    s.key(0x2F, false, true)
                    s.key(0x2F, false, false)
                    s.key(KeyMap.LCTRL.code, false, false)
                }
            },
        )
        clipboard = ClipboardBridge(this) { session }
        usbSharing = UsbSharing(this, { session }) { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        ui.usbItems = usbSharing.items
        gamepads = Gamepads { i, connected, st ->
            session?.gamepad(i, connected, st.buttons, st.leftTrigger, st.rightTrigger, st.lx, st.ly, st.rx, st.ry)
        }
        gamepads.onFirstPad = { name -> Toast.makeText(this, "手柄已连接：$name（被控端上是虚拟 Xbox 手柄）", Toast.LENGTH_SHORT).show() }
        gamepads.onCount = { n -> ui.gamepadCount = n }
        inputManager.registerInputDeviceListener(deviceListener, main)
        checkMouse()
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

        decoders = DecoderCaps.detect(settings.hwDecode)
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
            val open = insets.isVisible(WindowInsetsCompat.Type.ime()) || (Build.VERSION.SDK_INT < 30 && imeWanted)
            ui.keyboardOpen = open
            // The PC keyboard sets the inset itself.
            if (!ui.pcKeyboardOpen) viewport.setBottomInset(if (open) ime.toFloat() + 46 * resources.displayMetrics.density else 0f)

            if (open) viewport.ensureVisible(gestures.cursorX, gestures.cursorY, 48 * resources.displayMetrics.density)
            insets
        }

        // A locked (captured) mouse reports relative movement, to whichever view has the focus.
        root.isFocusableInTouchMode = true
        root.setOnCapturedPointerListener { _, ev -> onCapturedMouse(ev) }
        keyboardView.setOnCapturedPointerListener { _, ev -> onCapturedMouse(ev) }
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

    /** The screen shows HDR10 and a decoder handles HEVC Main10. */
    @Suppress("DEPRECATION")
    private fun hdrCapable(): Boolean {
        val d = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return false
        val screen = d.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
        return screen && decoders.any { it.tenBit }
    }

    private fun currentStreamOptions() = screenSize().let { size -> streamOptions(sd, size.x, size.y, refreshRate(), hdrCapable()) }

    private fun connect() {
        val book = hosts.book()
        val saved = book.byAddress(address)
        val s = sd
        val start = StartConfig(
            address = address,
            pinned = if (reverify) null else saved?.fingerprint,
            clientName = config.effectiveClientName,
            clientVersion = BuildConfig.VERSION_NAME,
            decoders = decoders.map { it.cap() } + decoders.filter { it.tenBit && hdrCapable() }.map { it.tenBitCap() },
            maxFps = fpsLimit(s, refreshRate()),
            stream = currentStreamOptions(),
            downloadDir = Downloads.receiveDir(this).absolutePath,
            shares = if (Shares.accessGranted()) s.sharedFolders else emptyList(),
            reverify = reverify,
            transport = s.transport,
        )
        ui.status = Status.Connecting
        ui.pinChanged = null
        ui.verifyFingerprint = null
        val sess = try {
            RemoteSession(filesDir.absolutePath, start, ::onEvent)
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
            is CoreEvent.VerifyFingerprint -> ui.verifyFingerprint = e.fingerprint
            is CoreEvent.Connected -> onConnected(e)
            is CoreEvent.Reconnecting -> ui.status = Status.Reconnecting(e.message)
            is CoreEvent.Disconnected -> {
                ui.needPairing = false
                ui.verifyFingerprint = null
                ui.status = Status.Disconnected(e.message)
            }
            is CoreEvent.PinChanged -> {
                ui.needPairing = false
                ui.pinChanged = e.message
                ui.status = Status.Disconnected(e.message)
            }
            is CoreEvent.SessionInfo -> {
                ui.displays = e.displays
                ui.vdAvailable = e.virtualDisplayAvailable
                ui.micDevice = e.micDevice
                if (ui.micAvailable && sd.mic && mic == null && !micPaused) toggleMic(keep = false)
            }
            is CoreEvent.StreamStarted -> {
                ui.stream = e
                ui.notice = ""
                cursorView.setSource(e.sourceWidth, e.sourceHeight)
                streamHdr = e.hdr
                decoder?.hdr = e.hdr
            }
            is CoreEvent.StreamError -> {
                ui.notice = ""
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
            is CoreEvent.Role -> ui.role = e
            is CoreEvent.CursorShape -> cursorView.addShape(e.id, e.width, e.height, e.hotX, e.hotY, e.rgbaBase64)
            is CoreEvent.CursorState -> {
                cursorView.setState(e.shapeId, e.visible, e.x, e.y)
                cursorView.remotePosition()?.let { (rx, ry) -> gestures.hostCursor(rx, ry) }
            }
            is CoreEvent.Clipboard -> if (sd.clipboard) clipboard.fromHostText(e.text)
            is CoreEvent.ClipboardImage -> if (sd.clipboard) {
                if (clipboard.fromHostImage(e.path)) Toast.makeText(this, "已复制被控端的图片", Toast.LENGTH_SHORT).show()
            }
            is CoreEvent.PrintJob -> when (sd.printMode) {
                "ask" -> ui.printJob = e.path
                else -> handlePrint(File(e.path), sd.printMode)
            }
            is CoreEvent.FolderMount -> {
                ui.folderMount = e
                if (!e.mounted && e.message.isNotBlank()) Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
            is CoreEvent.UsbStatus -> usbSharing.status(e.busid, e.attached, e.message)
            is CoreEvent.Stats -> ui.stats = e.line
            is CoreEvent.FileOffer -> if (sd.clipboard) {
                ui.offers.removeAll { it.id == e.id }
                ui.offers += e
            }
            is CoreEvent.Transfer -> onTransfer(e)
            is CoreEvent.TransferCancelled -> markCancelled(e.id, e.message)
            is CoreEvent.FilesReceived -> saveReceived(e)
            is CoreEvent.Rumble -> gamepads.rumble(e.index, e.large, e.small) { InputDevice.getDevice(it) }
        }
    }

    private fun onConnected(e: CoreEvent.Connected) {
        ui.status = Status.Connected
        ui.needPairing = false
        ui.verifyFingerprint = null
        reverify = false
        ui.fileTransfer = e.fileTransfer
        ui.viaTcp = e.tcp
        ui.gamepad = e.gamepad
        ui.usb = e.usb
        ui.micFeature = e.microphone
        ui.vdSupported = e.virtualDisplay
        keys.textInput = e.textInput
        clipboard.images = e.clipboardImage
        clipboard.files = e.clipboardFiles
        // Saved (or added) under the name the host gives itself unless named here.
        val wasSaved = hosts.book().byAddress(address) != null
        val book = hosts.change { b ->
            var (nb, h) = b.connected(address, e.serverName, e.fingerprint, e.fingerprintShort, System.currentTimeMillis())
            val name = newName
            if (!wasSaved && name != null) nb = runCatching { nb.rename(h.id, name) }.getOrDefault(nb)
            nb
        }
        newName = null
        book.byAddress(address)?.let { ui.hostName = it.displayName }
        if (ui.showGuideOnConnect && !guideShown) {
            guideShown = true
            ui.guideOpen = true
        }
    }

    /** A setting changed during the session: keep it for this host (its own settings from now on). */
    private fun remember(f: (ConnSettings) -> ConnSettings) {
        sd = f(sd)
        hosts.change { it.editSettings(address, config.defaults, f) }
    }

    private fun startDecoder() {
        val s = session ?: return
        if (decoder != null || !surfaceReady) return
        decoder = VideoDecoder(s, surfaceView.holder.surface, decoders) { w, h ->
            main.post {
                surfaceView.holder.setFixedSize(w, h)
                viewport.setContent(w.toFloat(), h.toFloat())
            }
        }.also {
            it.hdr = streamHdr
            it.start()
        }
    }

    private fun stopDecoder() {
        decoder?.stop()
        decoder = null
    }

    private fun onTransfer(e: CoreEvent.Transfer) {
        val i = ui.transfers.indexOfFirst { it.t.id == e.id && it.t.upload == e.upload }
        // Cancelled: what the stopping tasks (and the host) report changes nothing.
        if (i >= 0 && ui.transfers[i].cancelled) return
        if (i >= 0) ui.transfers[i] = ui.transfers[i].copy(t = e) else ui.transfers += TransferItem(e)
        while (ui.transfers.size > 8) {
            val old = ui.transfers.indexOfFirst { it.t.finished }
            if (old < 0) break
            ui.transfers.removeAt(old)
        }
        // Finished uploads go away on their own after a while; downloads stay until saved.
        if (e.finished && e.upload && e.ok) {
            main.postDelayed({ ui.transfers.removeAll { it.t.id == e.id && it.t.upload && it.t.finished } }, 8000)
        }
    }

    /** Transfer [id] was stopped (here or by the host): its running rows say so. */
    private fun markCancelled(id: String, message: String) {
        for (i in ui.transfers.indices) {
            val item = ui.transfers[i]
            if (item.t.id == id && !item.t.finished) {
                ui.transfers[i] = item.copy(t = item.t.copy(finished = true, ok = false, message = message), cancelled = true)
            }
        }
    }

    private fun saveReceived(e: CoreEvent.FilesReceived) {
        val files = e.paths.map { File(it) }
        thread(name = "nya-save") {
            val r = runCatching { Downloads.saveAll(this, files) }
            main.post {
                val i = ui.transfers.indexOfFirst { it.t.id == e.id && !it.t.upload }
                r.onSuccess { where ->
                    if (i >= 0) {
                        ui.transfers[i] = ui.transfers[i].copy(savedTo = where)
                    } else {
                        Toast.makeText(this, "已保存 ${files.size} 个文件到 $where", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { ex -> Toast.makeText(this, "保存文件失败：${ex.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Files picked on the phone: open them here, the core sends them. */
    private fun sendPicked(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val picked = buildJsonArray {
            for (uri in uris) {
                val (name, size) = Downloads.describe(this@SessionActivity, uri) ?: continue
                val pfd = try {
                    contentResolver.openFileDescriptor(uri, "r")
                } catch (_: Exception) {
                    null
                } ?: continue
                val len = if (size >= 0) size else pfd.statSize
                if (len < 0) {
                    pfd.close()
                    continue
                }
                add(buildJsonObject {
                    put("fd", pfd.detachFd())
                    put("name", name)
                    put("size", len)
                })
            }
        }
        if (picked.isEmpty()) {
            Toast.makeText(this, "无法读取选中的文件", Toast.LENGTH_SHORT).show()
            return
        }
        session?.sendFiles(picked.toString())
    }

    /** A print job from the host: print, open in another app, or save. */
    private fun handlePrint(f: File, how: String) {
        when (how) {
            "print" -> PdfPrint.print(this, f)
            "open" -> try {
                val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "没有能打开 PDF 的应用，已改为保存", Toast.LENGTH_LONG).show()
                handlePrint(f, "save")
            }
            else -> thread(name = "nya-save") {
                val msg = try {
                    "打印内容已保存到 ${Downloads.saveAll(this, listOf(f))}"
                } catch (ex: Exception) {
                    "保存失败：${ex.message}"
                }
                main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startMic() {
        val s = session ?: return
        if (mic != null) return
        mic = MicCapture(s) { msg ->
            main.post {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                mic = null
                ui.micOn = false
            }
        }.also { it.start() }
        ui.micOn = true
    }

    private fun stopMic() {
        mic?.stop()
        mic = null
        ui.micOn = false
    }

    /** The microphone was on when the app went to the background: on again when it returns. */
    private var micPaused = false

    override fun onStart() {
        super.onStart()
        if (micPaused) {
            micPaused = false
            if (session != null) startMic()
        }
    }

    override fun onStop() {
        // Other apps (voice input, calls, assistants) get the microphone while we are not in front.
        if (mic != null) {
            mic?.stop()
            mic = null
            micPaused = true
        }
        super.onStop()
    }

    private fun closeSession() {
        micPaused = false
        stopMic()
        setMouseLock(false)
        if (::usbSharing.isInitialized) usbSharing.releaseAll()
        if (::gamepads.isInitialized) gamepads.clear()
        stopDecoder()
        audio?.stop()
        audio = null
        ui.offers.clear()
        ui.transfers.clear()
        ui.viaTcp = null
        ui.notice = ""
        val s = session ?: return
        session = null
        thread(name = "nya-close") { s.close() }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::gamepads.isInitialized) inputManager.unregisterInputDeviceListener(deviceListener)
        if (::usbSharing.isInitialized) usbSharing.close()
        keyOut.shutdown()
        if (::ui.isInitialized) closeSession()
        super.onDestroy()
    }

    // ------------------------------------------------------------ input

    /** Touch gestures turned into host input; a watcher's gestures only pan and zoom here. */
    private val remoteInput = object : RemoteInput {
        override fun moveTo(rx: Float, ry: Float) {
            if (ui.watching) return
            session?.mouseTo(rx, ry)
            cursorView.moveLocal(rx, ry)
        }

        override fun button(button: Int, down: Boolean) {
            if (!ui.watching) session?.mouseButton(button, down)
        }

        override fun wheel(dx: Int, dy: Int) {
            if (!ui.watching) session?.wheel(dx, dy)
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
        if (ui.watching) return true
        val (rx, ry) = viewport.toRemote(ev.x, ev.y)
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE,
            -> remoteInput.moveTo(rx, ry)
            MotionEvent.ACTION_SCROLL -> wheel(ev)
        }
        buttons(ev.buttonState)
        return true
    }

    /** Locked mouse: movement as it comes (relative), buttons and wheel as usual. */
    private fun onCapturedMouse(ev: MotionEvent): Boolean {
        if (ui.watching) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                // Captured pointers report deltas in x / y (history included).
                var dx = 0f
                var dy = 0f
                for (h in 0 until ev.historySize) {
                    dx += ev.getHistoricalX(h)
                    dy += ev.getHistoricalY(h)
                }
                dx += ev.x
                dy += ev.y
                relX += dx
                relY += dy
                val sx = relX.toInt()
                val sy = relY.toInt()
                relX -= sx
                relY -= sy
                if (sx != 0 || sy != 0) session?.mouseBy(sx, sy)
            }
            MotionEvent.ACTION_SCROLL -> wheel(ev)
        }
        buttons(ev.buttonState)
        return true
    }

    private var relX = 0f
    private var relY = 0f

    private fun wheel(ev: MotionEvent) {
        val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
        session?.wheel((h * 120).roundToInt(), (v * 120).roundToInt())
    }

    private fun buttons(now: Int) {
        for ((mask, button) in listOf(
            MotionEvent.BUTTON_PRIMARY to NativeCore.BUTTON_LEFT,
            MotionEvent.BUTTON_SECONDARY to NativeCore.BUTTON_RIGHT,
            MotionEvent.BUTTON_TERTIARY to NativeCore.BUTTON_MIDDLE,
        )) {
            if ((now and mask) != (mouseButtons and mask)) session?.mouseButton(button, now and mask != 0)
        }
        mouseButtons = now
    }

    private fun checkMouse() {
        ui.mouseConnected = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_MOUSE) } == true
        }
        if (!ui.mouseConnected && ui.mouseLocked) setMouseLock(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system drops the capture when the window loses focus; take it again on return.
        if (hasFocus && ui.mouseLocked && !root.hasPointerCapture()) root.requestPointerCapture()
    }

    // Hardware keyboards; the soft keyboard goes through RemoteKeyboardView's input connection.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        (ui.gamepad && gamepads.onKey(event)) || (fromKeyboard(event) && !ui.watching && keys.key(keyCode, true)) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        (ui.gamepad && gamepads.onKey(event)) || (fromKeyboard(event) && !ui.watching && keys.key(keyCode, false)) || super.onKeyUp(keyCode, event)

    // Controller sticks and triggers (pointer events are handled by the layout).
    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        (ui.gamepad && gamepads.onMotion(event)) || super.onGenericMotionEvent(event)

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
        gestures.mode = mode
        remember { it.copy(controlMode = mode) }
    }

    /** The keyboard was asked for (insets tell the truth on Android 11+ only). */
    private var imeWanted = false

    private fun imeVisible(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        } else {
            imeWanted
        }

    override fun toggleKeyboard() {
        if (ui.watching) return
        when {
            ui.pcKeyboardOpen -> hidePcKeyboard()
            imeVisible() || ui.keyboardOpen -> hideKeyboard()
            sd.pcKeyboard -> showPcKeyboard()
            else -> showKeyboard()
        }
    }

    override fun switchKeyboard() {
        if (ui.pcKeyboardOpen) {
            hidePcKeyboard()
            showKeyboard()
        } else {
            hideKeyboard()
            showPcKeyboard()
        }
        val pc = ui.pcKeyboardOpen
        remember { it.copy(pcKeyboard = pc) }
    }

    private fun showPcKeyboard() {
        ui.pcKeyboardOpen = true
    }

    private fun hidePcKeyboard() {
        ui.pcKeyboardOpen = false
        viewport.setBottomInset(0f)
    }

    override fun pcKeyboardHeight(px: Int) {
        if (!ui.pcKeyboardOpen) return
        viewport.setBottomInset(px.toFloat())
        viewport.ensureVisible(gestures.cursorX, gestures.cursorY, 48 * resources.displayMetrics.density)
    }

    private fun showKeyboard() {
        imeWanted = true
        if (Build.VERSION.SDK_INT < 30) ui.keyboardOpen = true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboardView.requestFocus()
        // The IME only serves the view once the focus change went through: ask on the next frame,
        // and once more shortly after if it still isn't up (some IMEs ignore the first request).
        keyboardView.post {
            WindowCompat.getInsetsController(window, keyboardView).show(WindowInsetsCompat.Type.ime())
            imm.showSoftInput(keyboardView, 0)
        }
        main.postDelayed({
            if (imeWanted && !imeVisible()) {
                keyboardView.requestFocus()
                imm.restartInput(keyboardView)
                imm.showSoftInput(keyboardView, 0)
            }
        }, 350)
    }

    private fun hideKeyboard() {
        imeWanted = false
        if (Build.VERSION.SDK_INT < 30) ui.keyboardOpen = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        WindowCompat.getInsetsController(window, keyboardView).hide(WindowInsetsCompat.Type.ime())
        imm.hideSoftInputFromWindow(keyboardView.windowToken, 0)
        keyboardView.clearFocus()
    }

    override fun setGameMode(game: Boolean) {
        if (sd.game == game) return
        remember { it.copy(mode = if (game) "game" else "office") }
        session?.setGameMode(game)
        ui.notice = if (game) "正在切换到游戏模式…" else "正在切换到办公模式…"
    }

    override fun setPolicy(policy: String) {
        if (sd.bitratePolicy == policy) return
        remember { it.copy(bitratePolicy = policy) }
        session?.updateStream(currentStreamOptions())
        ui.notice = "正在切换码率策略…"
    }

    override fun setTransport(mode: String) {
        if (sd.transport == mode) return
        remember { it.copy(transport = mode) }
        session?.setTransport(mode)
        // Only a move between UDP and TCP reconnects; the status card shows it.
        val moves = (mode == "tcp" && ui.viaTcp == false) || (mode == "udp" && ui.viaTcp == true)
        if (!moves) {
            ui.notice = "连接方式：${connectionModes.find { it.first == mode }?.second ?: mode}"
            main.postDelayed({ ui.notice = "" }, 2500)
        }
    }

    override fun setShowStats(show: Boolean) {
        ui.showStats = show
        config = configStore.update { it.copy(showStats = show) }
    }

    override fun setShowGuideOnConnect(show: Boolean) {
        ui.showGuideOnConnect = show
        config = configStore.update { it.copy(showGuideOnConnect = show) }
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
        Toast.makeText(this, clipboard.sendToHost(), Toast.LENGTH_SHORT).show()
    }

    override fun pickDisplay(id: Int) {
        if (ui.currentDisplay == id) return
        remember { it.copy(display = id) }
        session?.updateStream(currentStreamOptions())
        ui.notice = "正在切换显示器…"
    }

    override fun setDisplayChoice(c: DisplayChoice) {
        val was = sd.displayChoice
        if (c == was) return
        remember { it.withDisplayChoice(c) }
        session?.updateStream(currentStreamOptions())
        val now = sd.displayChoice
        ui.notice = when {
            now.count > was.count -> "正在新建虚拟显示器…"
            now.count < was.count && now.count == 0 -> "正在移除虚拟显示器…"
            now.count < was.count -> "正在减少虚拟显示器…"
            now.physicalOff != was.physicalOff -> if (now.physicalOff) "正在关闭被控端的物理显示器…" else "正在打开被控端的物理显示器…"
            else -> if (now.blockInput) "已屏蔽被控端本地键盘鼠标" else "已恢复被控端本地键盘鼠标"
        }
        if (now.count == was.count && now.physicalOff == was.physicalOff) main.postDelayed({ ui.notice = "" }, 2500)
    }

    override fun toggleMic() = toggleMic(keep = true)

    /** [keep]: remember the choice for this host. */
    private fun toggleMic(keep: Boolean) {
        if (mic != null) {
            stopMic()
            if (keep) remember { it.copy(mic = false) }
            return
        }
        if (keep) remember { it.copy(mic = true) }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMic()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun setMouseLock(on: Boolean) {
        if (!::root.isInitialized) return
        ui.mouseLocked = on
        relX = 0f
        relY = 0f
        if (on) {
            ui.panelOpen = false
            if (!keyboardView.hasFocus()) root.requestFocus()
            root.requestPointerCapture()
            Toast.makeText(this, "鼠标已锁定在画面里；在面板里可以解除", Toast.LENGTH_SHORT).show()
        } else if (root.hasPointerCapture()) {
            root.releasePointerCapture()
        }
    }

    override fun toggleUsb(item: UsbSharing.Item) = usbSharing.toggle(item)

    override fun printJob(how: String?) {
        val path = ui.printJob ?: return
        ui.printJob = null
        if (how != null) handlePrint(File(path), how)
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

    override fun checkAgain(retry: Boolean) {
        ui.pinChanged = null
        if (!retry) return disconnect()
        reverify = true
        closeSession()
        connect()
    }

    override fun confirmFingerprint(ok: Boolean) {
        ui.verifyFingerprint = null
        session?.confirmFingerprint(ok)
    }

    override fun retry() {
        closeSession()
        connect()
    }

    override fun pickFiles() {
        ui.panelOpen = false
        filePicker.launch(arrayOf("*/*"))
    }

    override fun acceptOffer(id: String) {
        ui.offers.removeAll { it.id == id }
        session?.requestFiles(id)
    }

    override fun dismissOffer(id: String) {
        ui.offers.removeAll { it.id == id }
    }

    override fun dismissTransfer(id: String) {
        ui.transfers.removeAll { it.t.id == id && it.t.finished }
    }

    override fun cancelTransfer(id: String) {
        markCancelled(id, "已取消")
        session?.cancelTransfer(id)
    }

    override fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "文件在“下载/${Downloads.FOLDER}”里", Toast.LENGTH_LONG).show()
        }
    }

    override fun disconnect() {
        ui.panelOpen = false
        session?.releaseAll()
        closeSession()
        finish()
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"

        /** Connect to [address]; [name] names a host that is not saved yet. */
        fun intent(context: Context, address: String, name: String?) =
            Intent(context, SessionActivity::class.java).putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_NAME, name)
    }
}
