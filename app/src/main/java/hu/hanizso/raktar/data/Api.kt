package hu.hanizso.raktar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

@Serializable
data class PushRequest(
    val key: String,
    val action: String = "push",
    val device: String,
    val products: List<Product>,
    val deletes: List<String>,
    val moves: List<Move>,
    val folders: List<String> = emptyList(),
    val folderDeletes: List<String> = emptyList(),
    val logDays: Int = 90,
)

@Serializable
data class PhotoRequest(val key: String, val action: String = "photo", val productId: String, val mime: String = "image/jpeg", val data: String)

@Serializable
data class PhotoGetRequest(val key: String, val action: String = "photoGet", val photoId: String)

@Serializable
data class SimpleRequest(val key: String, val action: String, val logDays: Int = 90)

@Serializable
data class PushResult(
    val created: Int = 0, val updated: Int = 0, val deleted: Int = 0,
    val applied: Int = 0, val skipped: Int = 0, val unknown: Int = 0,
)

@Serializable
data class ServerResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val time: Long = 0,
    val products: List<Product>? = null,
    val moves: List<Move>? = null,
    val result: PushResult? = null,
    val folders: List<String>? = null,
    val photoId: String? = null,
    val data: String? = null,
)

object Api {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val JSON_TYPE = "text/plain; charset=utf-8".toMediaType()

    suspend fun pull(s: Settings): ServerResponse = post(s, json.encodeToString(SimpleRequest(s.key, "pull")))

    suspend fun ping(s: Settings): ServerResponse = post(s, json.encodeToString(SimpleRequest(s.key, "ping")))

    suspend fun push(s: Settings, req: PushRequest): ServerResponse = post(s, json.encodeToString(req))

    suspend fun uploadPhoto(s: Settings, productId: String, jpeg: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        return post(s, json.encodeToString(PhotoRequest(s.key, productId = productId, data = b64))).photoId
            ?: throw ApiException("A fotó feltöltése nem sikerült.")
    }

    suspend fun downloadPhoto(s: Settings, photoId: String): ByteArray {
        val r = post(s, json.encodeToString(PhotoGetRequest(s.key, photoId = photoId)))
        return android.util.Base64.decode(r.data ?: throw ApiException("A fotó nem tölthető le."), android.util.Base64.DEFAULT)
    }

    private suspend fun post(s: Settings, body: String): ServerResponse = withContext(Dispatchers.IO) {
        if (s.url.isBlank() || s.key.isBlank()) throw ApiException("Nincs beállítva a web app címe vagy a kulcs (Beállítások).")
        val req = Request.Builder().url(s.url.trim()).post(body.toRequestBody(JSON_TYPE)).build()
        val text = try {
            client.newCall(req).execute().use { resp ->
                val t = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException("A szerver hibát adott (${resp.code}).")
                t
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("Nincs kapcsolat a szerverrel. Ellenőrizd az internetet.")
        }
        val r = try {
            json.decodeFromString<ServerResponse>(text)
        } catch (e: Exception) {
            throw ApiException("Érthetetlen válasz a szervertől. Jó a web app címe? (A /exec végű címet kell megadni.)")
        }
        if (!r.ok) throw ApiException(
            when (r.error) {
                "unauthorized" -> "Hibás API kulcs. Másold be újra a táblázat Raktár → API kulcs menüjéből."
                "busy" -> "A táblázat épp foglalt. Próbáld újra pár másodperc múlva."
                "unknown_product" -> "A termék még nincs a táblázatban."
                else -> "Szerverhiba: ${r.message ?: r.error ?: "ismeretlen"}"
            }
        )
        r
    }
}
