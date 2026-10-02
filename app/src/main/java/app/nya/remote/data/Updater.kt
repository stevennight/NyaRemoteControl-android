package app.nya.remote.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from GitHub Releases (the release workflow publishes the signed
 * APK and its .sha256). Downloaded into the cache, checked, then handed to
 * the system installer (the user confirms).
 */
object Updater {
    const val REPO = "stevennight/NyaRemoteControl-android"

    data class Release(val version: String, val apkUrl: String, val sha256Url: String?, val notes: String, val page: String = "")

    /** Latest non-prerelease, or null (offline, none yet). Blocking: call off the main thread. */
    fun latest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) return null
            val o = Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
            val assets = o["assets"]?.jsonArray?.map { it.jsonObject } ?: return null
            fun url(suffix: String) = assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(suffix) == true }
                ?.get("browser_download_url")?.jsonPrimitive?.content
            val apk = url(".apk") ?: return null
            return Release(
                tag.removePrefix("v"),
                apk,
                url(".apk.sha256"),
                o["body"]?.jsonPrimitive?.content ?: "",
                o["html_url"]?.jsonPrimitive?.content ?: "",
            )
        } finally {
            c.disconnect()
        }
    }

    /** a > b for MAJOR.MINOR.PATCH[-pre] (a prerelease is older than its release). */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until 3) if (pa[i] != pb[i]) return pa[i] > pb[i]
        return !a.contains('-') && b.contains('-')
    }

    /** Download and verify; returns the APK. Blocking. */
    fun download(context: Context, r: Release, progress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "NyaRemoteControl-Android_${r.version}.apk")
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        val total = c.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { input ->
            apk.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
        r.sha256Url?.let { u ->
            val want = URL(u).readText().trim().substringBefore(' ').lowercase()
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (want != got) {
                apk.delete()
                throw IllegalStateException("下载的安装包校验失败")
            }
        }
        return apk
    }

    /** Open the system installer for [apk]. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
