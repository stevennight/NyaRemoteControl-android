package app.nya.remote.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/** Files received from the host: from the core's cache folder into the phone's Downloads. */
object Downloads {
    const val FOLDER = "NyaRemoteControl"

    /** Where the core receives files (moved away once a batch is complete). */
    fun receiveDir(context: Context) = File(context.cacheDir, "received")

    /**
     * Move [files] to Download/NyaRemoteControl (Android 10+: through MediaStore,
     * no permission needed; older: the app's own Download folder). Returns the
     * folder shown to the user.
     */
    fun saveAll(context: Context, files: List<File>): String {
        var shown = "下载/$FOLDER"
        for (f in files) {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                try {
                    resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER)
                dir.mkdirs()
                var target = File(dir, f.name)
                var n = 1
                while (target.exists()) target = File(dir, "${f.nameWithoutExtension} ($n)${if (f.extension.isEmpty()) "" else "." + f.extension}").also { n++ }
                f.copyTo(target)
                shown = dir.absolutePath
            }
            f.delete()
        }
        files.firstOrNull()?.parentFile?.delete()
        return shown
    }

    /** Name and size of a picked document. */
    fun describe(context: Context, uri: Uri): Pair<String, Long>? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val name = c.getString(0) ?: return null
            val size = if (c.isNull(1)) -1L else c.getLong(1)
            name to size
        }
}
