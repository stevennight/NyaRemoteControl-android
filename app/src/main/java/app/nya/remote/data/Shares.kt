package app.nya.remote.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings

/**
 * Phone folders shown on the host as a drive. The core serves them by path,
 * so the app needs file access: "all files access" on Android 11+, the
 * storage permission before that.
 */
object Shares {
    fun accessGranted(context: Context? = null): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        context == null -> true
        else -> context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 11+: the settings page that grants all-files access to this app. */
    fun accessSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /**
     * A folder picked with the system picker (tree URI) as a file system path:
     * "primary:DCIM/Camera" -> /storage/emulated/0/DCIM/Camera, "1234-ABCD:x" -> /storage/1234-ABCD/x.
     * Null for providers that are not local storage (cloud drives).
     */
    fun pathOf(tree: Uri): String? {
        if (tree.authority != "com.android.externalstorage.documents") return null
        val id = DocumentsContract.getTreeDocumentId(tree)
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /** A directory name for the host's drive: unique, no slashes. */
    fun uniqueName(wanted: String, taken: Collection<String>): String {
        val base = wanted.replace('/', '_').replace('\\', '_').ifBlank { "手机" }
        var name = base
        var n = 2
        while (taken.any { it.equals(name, ignoreCase = true) }) name = "$base ($n)".also { n++ }
        return name
    }
}
