package app.nya.remote.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import app.nya.remote.data.Dib
import app.nya.remote.data.Downloads
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The phone's clipboard and the host's: text both ways (as before), images
 * both ways (CF_DIB on the wire), and files copied on the phone offered for
 * pasting on the host (staged in the cache first: the core reads plain files).
 */
class ClipboardBridge(private val context: Context, private val session: () -> RemoteSession?) {
    var images = false
    var files = false
    private val cm get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun fromHostText(text: String) {
        cm.setPrimaryClip(ClipData.newPlainText("NyaRemoteControl", text))
    }

    /** An image copied on the host: PNG in the cache, shared through the FileProvider. */
    fun fromHostImage(dibPath: String): Boolean {
        val f = File(dibPath)
        val px = Dib.decode(f.readBytes()) ?: return false
        f.delete()
        val bmp = Bitmap.createBitmap(px.argb, px.width, px.height, Bitmap.Config.ARGB_8888)
        val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val png = File(dir, "电脑截图-${System.currentTimeMillis()}.png")
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", png)
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "NyaRemoteControl", uri))
        return true
    }

    /** Send what the phone's clipboard holds; returns a message for the user. */
    fun sendToHost(): String {
        val s = session() ?: return "未连接"
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return "手机剪贴板是空的"
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isNotEmpty()) {
            val first = uris.first()
            val type = context.contentResolver.getType(first) ?: ""
            if (uris.size == 1 && type.startsWith("image/") && images) {
                val dib = imageDib(first) ?: return "无法读取剪贴板里的图片"
                s.clipboardImage(dib)
                return "已把图片放到电脑剪贴板"
            }
            if (!files) return "电脑上的被控端版本不支持复制文件"
            val paths = stage(uris)
            if (paths.isEmpty()) return "无法读取剪贴板里的文件"
            s.clipboardFiles(JsonArray(paths.map { JsonPrimitive(it) }).toString())
            return "已复制 ${paths.size} 个文件，在电脑上粘贴即可"
        }
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return "手机剪贴板里没有文字"
        s.sendClipboard(text)
        return "已发送到电脑剪贴板"
    }

    private fun imageDib(uri: Uri): ByteArray? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        // Keep the DIB under ~64 MB (the host's limit): at most 4096 px a side.
        var sample = 1
        while (opts.outWidth / sample > 4096 || opts.outHeight / sample > 4096) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }
        bmp?.let {
            val argb = IntArray(it.width * it.height)
            it.getPixels(argb, 0, it.width, 0, 0, it.width, it.height)
            Dib.encode(Dib.Pixels(it.width, it.height, argb))
        }
    } catch (_: Exception) {
        null
    }

    /** Copy the documents into a fresh cache folder (offers older than a few copies are dropped by the core). */
    private fun stage(uris: List<Uri>): List<String> {
        val root = File(context.cacheDir, "clipout")
        root.listFiles()?.sortedBy { it.lastModified() }?.dropLast(4)?.forEach { it.deleteRecursively() }
        val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        return uris.mapNotNull { uri ->
            try {
                val name = Downloads.describe(context, uri)?.first ?: uri.lastPathSegment ?: "file"
                val target = File(dir, name.replace('/', '_'))
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                target.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }
}
