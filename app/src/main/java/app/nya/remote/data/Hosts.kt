package app.nya.remote.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** `ip`, `ip:port`, host name; port 47100 when absent. */
    val address: String,
    /** Pinned certificate fingerprint (hex) after the first successful connection. */
    val fingerprint: String? = null,
    val fingerprintShort: String? = null,
    /** Milliseconds; 0 = never. */
    val lastConnected: Long = 0,
    /** The name the host gave itself at the last connection. */
    val serverName: String = "",
    /** Named on this phone: keep [name] instead of following [serverName]. Entries from 0.1.x keep theirs. */
    val customName: Boolean = true,
    /** Own connection settings; null = the defaults. */
    val settings: ConnSettings? = null,
) {
    val displayName: String get() = name.ifBlank { address }
    val paired: Boolean get() = !fingerprint.isNullOrBlank()
}

/**
 * The saved hosts and the rules for their names (as the Windows client's
 * `ClientConfig`): a host not named here shows the name it gives itself;
 * names stay unique. Pure: every change returns a new book.
 */
data class HostBook(val hosts: List<Host>) {
    fun byAddress(address: String): Host? = hosts.find { it.address == address }

    fun byId(id: String): Host? = hosts.find { it.id == id }

    /** The settings to connect to [address] with. */
    fun settingsFor(address: String, defaults: ConnSettings): ConnSettings = byAddress(address)?.settings ?: defaults

    /** [base], or `base (2)` … so that no host other than [except] has it. */
    private fun uniqueName(base: String, except: String?): String {
        fun taken(n: String) = hosts.any { it.id != except && (it.name == n || it.address == n) }
        var name = base
        var n = 2
        while (taken(name)) name = "$base (${n++})"
        return name
    }

    private fun automaticName(h: Host): String = uniqueName(h.serverName.trim().ifBlank { h.address }, h.id)

    private fun replace(h: Host) = HostBook(hosts.map { if (it.id == h.id) h else it })

    /** Save a host added by hand; an empty name follows the host's own name once connected. */
    fun add(address: String, name: String): HostBook {
        val a = address.trim()
        require(a.isNotEmpty()) { "请输入地址" }
        require(byAddress(a) == null) { "这个地址已经保存过了" }
        val custom = name.isNotBlank()
        val h = Host(name = uniqueName(if (custom) name.trim() else a, null), address = a, customName = custom)
        return HostBook(hosts + h)
    }

    /** Rename; an empty name goes back to the name the host gives itself. */
    fun rename(id: String, name: String): HostBook {
        val h = byId(id) ?: throw IllegalArgumentException("设备不存在")
        val n = name.trim()
        if (n.isEmpty()) return replace(h.copy(name = automaticName(h), customName = false))
        require(hosts.none { it.id != id && (it.name == n || it.address == n) }) { "已有同名的设备" }
        return replace(h.copy(name = n, customName = true))
    }

    fun remove(id: String) = HostBook(hosts.filterNot { it.id == id })

    /**
     * A successful connection: matched by address, added if new. Unless named
     * here, the host shows under the name it gives itself.
     */
    fun connected(address: String, serverName: String, fingerprint: String, fingerprintShort: String, now: Long): Pair<HostBook, Host> {
        var book = this
        val old = byAddress(address) ?: Host(name = "", address = address, customName = false).also { book = HostBook(hosts + it) }
        var h = old.copy(
            fingerprint = fingerprint,
            fingerprintShort = fingerprintShort,
            lastConnected = maxOf(now, old.lastConnected),
            serverName = serverName.trim(),
        )
        book = book.replace(h)
        if (!h.customName || h.name.isBlank()) {
            h = h.copy(name = book.automaticName(h))
            book = book.replace(h)
        }
        return book to h
    }

    /** Own settings of [id] (null = back to the defaults). */
    fun setSettings(id: String, s: ConnSettings?) = byId(id)?.let { replace(it.copy(settings = s)) } ?: this

    /** Change the settings of a saved host, starting from the defaults. */
    fun editSettings(address: String, defaults: ConnSettings, f: (ConnSettings) -> ConnSettings): HostBook {
        val h = byAddress(address) ?: return this
        return replace(h.copy(settings = f(h.settings ?: defaults)))
    }
}

/** Saved hosts in the app's private storage (no secrets: pairing keys never leave the host). */
class HostStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "hosts.json"))

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Synchronized
    fun book(): HostBook = HostBook(
        try {
            if (file.exists()) json.decodeFromString<List<Host>>(file.readText()) else emptyList()
        } catch (_: Exception) {
            emptyList()
        },
    )

    fun all(): List<Host> = book().hosts

    fun get(id: String): Host? = book().byId(id)

    /** Apply [f] and save; returns the new book. Exceptions from [f] (bad names…) leave the file as it was. */
    @Synchronized
    fun change(f: (HostBook) -> HostBook): HostBook = f(book()).also { save(it.hosts) }

    private fun save(list: List<Host>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
