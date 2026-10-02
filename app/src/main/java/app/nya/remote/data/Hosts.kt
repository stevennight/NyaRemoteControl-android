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
    val lastConnected: Long = 0,
) {
    val displayName: String get() = name.ifBlank { address }
    val paired: Boolean get() = !fingerprint.isNullOrBlank()
}

/** Saved hosts in the app's private storage (no secrets: pairing keys never leave the host). */
class HostStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "hosts.json"))

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun all(): List<Host> = try {
        if (file.exists()) json.decodeFromString<List<Host>>(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun get(id: String): Host? = all().find { it.id == id }

    @Synchronized
    fun put(host: Host) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list.add(host)
        save(list)
    }

    @Synchronized
    fun remove(id: String) = save(all().filterNot { it.id == id })

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
