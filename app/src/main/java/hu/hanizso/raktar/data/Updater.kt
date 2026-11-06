package hu.hanizso.raktar.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import hu.hanizso.raktar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Frissítés a GitHub Releases-ből (nyilvános tároló: Johnnyka02/Raktar-app).
 * A kiadás címkéje "v1.0.<versionCode>", az APK a kiadás melléklete.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/Johnnyka02/Raktar-app/releases/latest"

    @Serializable
    data class Asset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    @Serializable
    data class Release(@SerialName("tag_name") val tag: String, val name: String? = null, val body: String? = null, val assets: List<Asset> = emptyList())

    data class Available(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String, val size: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).followRedirects(true).build()

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** null = nincs újabb verzió. Hiba esetén ApiException. */
    suspend fun check(): Available? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "HaNiZso-Raktar").build()
        val text = try {
            client.newCall(req).execute().use { r ->
                if (r.code == 404) return@withContext null      // még nincs kiadás (vagy privát a tároló)
                if (!r.isSuccessful) throw ApiException("A frissítés-ellenőrzés nem sikerült (${r.code}).")
                r.body?.string().orEmpty()
            }
        } catch (e: ApiException) { throw e } catch (e: Exception) {
            throw ApiException("Nincs internet, a frissítést most nem lehet ellenőrizni.")
        }
        val rel = Api.json.decodeFromString<Release>(text)
        val code = Regex("(\\d+)\\s*$").find(rel.tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
        val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return@withContext null
        if (code <= currentVersionCode) null
        else Available(code, rel.tag.removePrefix("v"), rel.body.orEmpty().trim(), apk.url, apk.size)
    }

    /** Letölti az APK-t; a progress 0..1 között. */
    suspend fun download(ctx: Context, a: Available, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "HaNiZso-Raktar-${a.versionName}.apk")
        val req = Request.Builder().url(a.apkUrl).header("User-Agent", "HaNiZso-Raktar").build()
        try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ApiException("A letöltés nem sikerült (${r.code}).")
                val body = r.body ?: throw ApiException("A letöltés üres.")
                val total = body.contentLength().takeIf { it > 0 } ?: a.size
                body.byteStream().use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var sum = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            os.write(buf, 0, read); sum += read
                            if (total > 0) progress((sum.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } catch (e: ApiException) { throw e } catch (e: Exception) {
            throw ApiException("A letöltés megszakadt. Ellenőrizd az internetet, és próbáld újra.")
        }
        out
    }

    /** Igaz, ha az app telepíthet APK-t (különben a beállításokat kell megnyitni). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        val i = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
