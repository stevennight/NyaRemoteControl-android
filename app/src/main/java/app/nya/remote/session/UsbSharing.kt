package app.nya.remote.session

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat

/**
 * USB devices plugged into the phone (OTG), shared with the host on request:
 * the app asks Android for permission, opens the device, claims every
 * interface (Android's own drivers let go) and hands the descriptor to the
 * core, which serves it as USB/IP through the session (see rust/src/usb.rs).
 */
class UsbSharing(private val context: Context, private val session: () -> RemoteSession?, private val toast: (String) -> Unit) {
    data class Item(val busid: String, val deviceId: Int, val name: String, val shared: Boolean, val status: String)

    val items = mutableStateListOf<Item>()
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val open = HashMap<String, Pair<UsbDevice, UsbDeviceConnection>>()
    private var waiting: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val busid = waiting ?: return
                    waiting = null
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        items.find { it.busid == busid }?.let { share(it) }
                    } else {
                        toast("没有获得 USB 设备的使用权限")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    private fun busid(d: UsbDevice) = "1-${d.deviceId}"

    private fun label(d: UsbDevice): String {
        val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { d.deviceName }
        return "%s (%04x:%04x)".format(name, d.vendorId, d.productId)
    }

    fun refresh() {
        val now = usb.deviceList.values.toList()
        // Devices that went away stop being shared.
        open.keys.filter { id -> now.none { busid(it) == id } }.forEach { unshare(it) }
        val old = items.associateBy { it.busid }
        items.clear()
        now.forEach { d ->
            val id = busid(d)
            items += Item(id, d.deviceId, label(d), open.containsKey(id), old[id]?.status ?: "")
        }
    }

    fun toggle(item: Item) = if (item.shared) unshare(item.busid) else share(item)

    private fun share(item: Item) {
        val s = session() ?: return
        val d = usb.deviceList.values.find { busid(it) == item.busid } ?: return refresh()
        if (!usb.hasPermission(d)) {
            waiting = item.busid
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
            usb.requestPermission(d, pi)
            return
        }
        val conn = usb.openDevice(d) ?: return toast("无法打开 ${item.name}")
        for (i in 0 until d.interfaceCount) {
            if (!conn.claimInterface(d.getInterface(i), true)) {
                conn.close()
                return toast("无法占用 ${item.name}（第 ${i + 1} 个接口）")
            }
        }
        val raw = conn.rawDescriptors ?: ByteArray(0)
        if (!s.usbShare(item.busid, d.deviceId, conn.fileDescriptor, raw, item.name)) {
            conn.close()
            return toast("无法共享 ${item.name}")
        }
        open[item.busid] = d to conn
        update(item.busid, shared = true, status = "等待电脑连接…")
    }

    private fun unshare(busid: String) {
        session()?.usbUnshare(busid)
        open.remove(busid)?.let { (d, conn) ->
            for (i in 0 until d.interfaceCount) conn.releaseInterface(d.getInterface(i))
            conn.close()
        }
        update(busid, shared = false, status = "")
    }

    /** What the host says about a device. */
    fun status(busid: String, attached: Boolean, message: String) {
        update(busid, status = if (attached) "已连接到电脑" else message.ifBlank { "未连接" })
        if (!attached && message.isNotBlank()) toast(message)
    }

    private fun update(busid: String, shared: Boolean? = null, status: String? = null) {
        val i = items.indexOfFirst { it.busid == busid }
        if (i >= 0) items[i] = items[i].copy(shared = shared ?: items[i].shared, status = status ?: items[i].status)
    }

    /** Stop sharing everything (session over). */
    fun releaseAll() {
        open.keys.toList().forEach { unshare(it) }
    }

    fun close() {
        releaseAll()

        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_PERMISSION = "app.nya.remote.USB_PERMISSION"
    }
}
