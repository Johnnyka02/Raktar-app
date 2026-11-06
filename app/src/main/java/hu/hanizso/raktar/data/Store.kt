package hu.hanizso.raktar.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

data class SyncOutcome(val pushed: PushResult?, val pulled: Boolean, val photoError: String? = null)

/**
 * A telefon helyi adattára. Minden változás azonnal fájlba íródik,
 * így az app bezárása vagy a telefon újraindítása után sem vész el semmi.
 */
class Store private constructor(private val ctx: Context) {

    private val file = File(ctx.filesDir, "state.json")
    private val photoDir = File(ctx.filesDir, "photos").apply { mkdirs() }

    /** A termék helyi fotója (lehet, hogy nem létezik). */
    fun photoFile(productId: String) = File(photoDir, "$productId.jpg")
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val syncLock = Mutex()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<LocalState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): LocalState = try {
        if (file.exists()) Api.json.decodeFromString<LocalState>(file.readText()) else LocalState()
    } catch (e: Exception) {
        // sérült fájl esetén a biztonsági másolatból
        val bak = File(ctx.filesDir, "state.bak.json")
        try { Api.json.decodeFromString<LocalState>(bak.readText()) } catch (e2: Exception) { LocalState() }
    }

    private fun save(s: LocalState) {
        val tmp = File(ctx.filesDir, "state.tmp.json")
        tmp.writeText(Api.json.encodeToString(s))
        if (file.exists()) file.copyTo(File(ctx.filesDir, "state.bak.json"), overwrite = true)
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    private suspend fun update(f: (LocalState) -> LocalState): LocalState = lock.withLock {
        val n = f(_state.value)
        withContext(Dispatchers.IO) { save(n) }
        _state.value = n
        n
    }

    private fun loadSettings() = Settings(
        url = prefs.getString("url", "") ?: "",
        key = prefs.getString("key", "") ?: "",
        deviceName = prefs.getString("device", null) ?: defaultDeviceName(),
    )

    fun saveSettings(s: Settings) {
        prefs.edit().putString("url", s.url.trim()).putString("key", s.key.trim())
            .putString("device", s.deviceName.trim().ifBlank { defaultDeviceName() }).apply()
        _settings.value = loadSettings()
    }

    val isConfigured: Boolean get() = _settings.value.url.isNotBlank() && _settings.value.key.isNotBlank()

    /* ---------------- helyi műveletek ---------------- */

    suspend fun addMove(p: Product, delta: Int, type: String): Move {
        var created: Move? = null
        update { s ->
            val cur = s.products().firstOrNull { it.id == p.id }?.qty ?: p.qty
            val m = Move(
                id = UUID.randomUUID().toString(), productId = p.id, ean = p.ean, name = p.name,
                delta = delta, type = type, at = System.currentTimeMillis(), qtyAfter = cur + delta,
                device = _settings.value.deviceName,
            )
            created = m
            s.copy(pendingMoves = s.pendingMoves + m)
        }
        return created!!
    }

    /** Visszavonás: ha még nincs elküldve, egyszerűen töröljük; ha már igen, ellenmozgást rögzítünk. */
    suspend fun undoMove(m: Move) {
        val pending = _state.value.pendingMoves.any { it.id == m.id }
        if (pending) {
            update { s -> s.copy(pendingMoves = s.pendingMoves.filterNot { it.id == m.id }) }
        } else {
            val p = _state.value.products().firstOrNull { it.id == m.productId } ?: return
            addMove(p, -m.delta, "visszavonás")
        }
    }

    suspend fun saveProduct(p: Product, newQty: Int) {
        val old = _state.value.products().firstOrNull { it.id == p.id }
        update { s -> s.copy(pendingProducts = s.pendingProducts.filterNot { it.id == p.id } + p.copy(qty = 0)) }
        val oldQty = old?.qty ?: 0
        if (newQty != oldQty) {
            addMove(p, newQty - oldQty, if (old == null) MoveType.IN else MoveType.FIX)
        }
    }

    /** Új fotó a termékhez: helyben elmenti, a következő szinkronnál feltölti. */
    suspend fun setPhoto(productId: String, jpeg: ByteArray) {
        withContext(Dispatchers.IO) { photoFile(productId).writeBytes(jpeg) }
        update { s -> s.copy(pendingPhotos = (s.pendingPhotos - productId) + productId) }
    }

    /** Ha a fotó nincs meg a telefonon, de a Drive-on igen, letölti. */
    suspend fun ensurePhoto(p: Product): File? {
        val f = photoFile(p.id)
        if (f.exists()) return f
        if (p.photoId.isBlank() || !isConfigured) return null
        return try {
            val bytes = Api.downloadPhoto(_settings.value, p.photoId)
            withContext(Dispatchers.IO) { f.writeBytes(bytes) }
            f
        } catch (e: Exception) { null }
    }

    suspend fun addFolder(path: String) {
        val clean = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
        if (clean.isEmpty()) return
        update { s -> s.copy(pendingFolders = (s.pendingFolders + clean).distinct(), pendingFolderDeletes = s.pendingFolderDeletes - clean) }
    }

    suspend fun deleteFolder(path: String) {
        update { s ->
            s.copy(
                pendingFolders = s.pendingFolders.filterNot { it == path || it.startsWith("$path/") },
                pendingFolderDeletes = (s.pendingFolderDeletes + path).distinct(),
            )
        }
    }

    suspend fun deleteProduct(id: String) {
        withContext(Dispatchers.IO) { photoFile(id).delete() }
        update { s -> s.copy(pendingPhotos = s.pendingPhotos - id) }
        update { s ->
            val onServer = s.serverProducts.any { it.id == id }
            if (onServer) {
                s.copy(
                    pendingDeletes = (s.pendingDeletes + id).distinct(),
                    pendingProducts = s.pendingProducts.filterNot { it.id == id },
                    pendingMoves = s.pendingMoves.filterNot { it.productId == id },
                )
            } else {
                s.copy(
                    pendingProducts = s.pendingProducts.filterNot { it.id == id },
                    pendingMoves = s.pendingMoves.filterNot { it.productId == id },
                )
            }
        }
    }

    /* ---------------- szinkronizálás ---------------- */

    /**
     * Ha van el nem küldött változás, elküldi (a válaszban friss készlet jön),
     * különben csak letölti a friss készletet.
     */
    suspend fun sync(): SyncOutcome = syncLock.withLock {
        val set = _settings.value
        val snap = _state.value
        if (snap.pendingCount == 0) {
            val r = Api.pull(set)
            update { s -> s.copy(serverProducts = r.products ?: s.serverProducts, serverMoves = r.moves ?: s.serverMoves,
                serverFolders = r.folders ?: s.serverFolders, lastSync = System.currentTimeMillis()) }
            return SyncOutcome(null, true)
        }
        var result: PushResult? = null
        val needPush = snap.pendingMoves.isNotEmpty() || snap.pendingProducts.isNotEmpty() || snap.pendingDeletes.isNotEmpty() ||
            snap.pendingFolders.isNotEmpty() || snap.pendingFolderDeletes.isNotEmpty()
        if (needPush) {
            val req = PushRequest(
                key = set.key, device = set.deviceName,
                products = snap.pendingProducts, deletes = snap.pendingDeletes, moves = snap.pendingMoves,
                folders = snap.pendingFolders, folderDeletes = snap.pendingFolderDeletes,
            )
            val r = Api.push(set, req)
            result = r.result
            val sentMoves = snap.pendingMoves.map { it.id }.toSet()
            val sentProducts = snap.pendingProducts.toSet()
            val sentDeletes = snap.pendingDeletes.toSet()
            val sentFolders = snap.pendingFolders.toSet()
            val sentFolderDeletes = snap.pendingFolderDeletes.toSet()
            update { s ->
                s.copy(
                    serverProducts = r.products ?: s.serverProducts,
                    serverMoves = r.moves ?: s.serverMoves,
                    serverFolders = r.folders ?: s.serverFolders,
                    pendingMoves = s.pendingMoves.filterNot { it.id in sentMoves },
                    pendingProducts = s.pendingProducts.filterNot { it in sentProducts },
                    pendingDeletes = s.pendingDeletes.filterNot { it in sentDeletes },
                    pendingFolders = s.pendingFolders.filterNot { it in sentFolders },
                    pendingFolderDeletes = s.pendingFolderDeletes.filterNot { it in sentFolderDeletes },
                    lastSync = System.currentTimeMillis(),
                )
            }
        }
        // Fotók feltöltése egyenként (ha egy nem sikerül, a többi még mehet, ez marad a sorban)
        var photoError: String? = null
        for (pid in _state.value.pendingPhotos.toList()) {
            val f = photoFile(pid)
            if (!f.exists() || _state.value.serverProducts.none { it.id == pid }) {
                if (!f.exists()) update { s -> s.copy(pendingPhotos = s.pendingPhotos - pid) }
                continue
            }
            try {
                val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                val photoId = Api.uploadPhoto(set, pid, bytes)
                update { s -> s.copy(
                    pendingPhotos = s.pendingPhotos - pid,
                    serverProducts = s.serverProducts.map { if (it.id == pid) it.copy(photoId = photoId) else it },
                ) }
            } catch (e: Exception) {
                photoError = e.message
            }
        }
        if (!needPush) update { s -> s.copy(lastSync = System.currentTimeMillis()) }
        SyncOutcome(result, false, photoError)
    }

    suspend fun testConnection(s: Settings) { Api.ping(s) }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(ctx.applicationContext).also { inst = it }
        }

        fun defaultDeviceName(): String {
            val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return if (model.startsWith(m, ignoreCase = true)) model else "$m $model"
        }
    }
}
