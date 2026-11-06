package hu.hanizso.raktar.ui

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.hanizso.raktar.data.LocalState
import hu.hanizso.raktar.data.Move
import hu.hanizso.raktar.data.MoveType
import hu.hanizso.raktar.data.Product
import hu.hanizso.raktar.data.Settings
import hu.hanizso.raktar.data.StockLevel
import hu.hanizso.raktar.data.Store
import hu.hanizso.raktar.data.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Tab { OUT, IN, STOCK, LOG }

sealed class ScanResult {
    /** sessionTotal: ebből a termékből ebben a csomagolásban összesen (pl. -4) */
    data class Done(val product: Product, val move: Move, val qtyAfter: Int, val minQty: Int, val undone: Boolean = false, val sessionTotal: Int = move.delta) : ScanResult()
    data class Unknown(val code: String) : ScanResult()
    data class Info(val text: String) : ScanResult()
}

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: Updater.Available) : UpdateState()
    data class Downloading(val info: Updater.Available, val progress: Float) : UpdateState()
    data class Error(val text: String) : UpdateState()
}

data class DaySummaryLine(val name: String, val units: Int)
data class Summary(
    val lines: List<DaySummaryLine>,
    val totalUnits: Int,
    val low: List<Product>,
    val syncError: String?,
    val pushedMoves: Int,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store.get(app)
    val state: StateFlow<LocalState> = store.state
    val settings: StateFlow<Settings> = store.settings

    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _scan = MutableStateFlow<ScanResult?>(null)
    val scan = _scan.asStateFlow()

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary = _summary.asStateFlow()

    val tab = MutableStateFlow(Tab.OUT)
    val inQty = MutableStateFlow(1)

    private val tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 85) } catch (e: Exception) { null }

    val update = MutableStateFlow<UpdateState>(UpdateState.Idle)

    init {
        if (store.isConfigured) syncNow(silent = true)
        // naponta egyszer magától is megnézi, van-e új verzió
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("upd_last", 0) > 20 * 3600_000L) {
            prefs.edit().putLong("upd_last", System.currentTimeMillis()).apply()
            checkUpdate(manual = false)
        }
    }

    fun checkUpdate(manual: Boolean = true) {
        if (update.value is UpdateState.Checking || update.value is UpdateState.Downloading) return
        viewModelScope.launch {
            update.value = UpdateState.Checking
            update.value = try {
                Updater.check()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: Exception) {
                if (manual) UpdateState.Error(e.message ?: "Nem sikerült ellenőrizni.") else UpdateState.Idle
            }
        }
    }

    fun startUpdate() {
        val ctx = getApplication<Application>()
        val st = update.value
        val info = (st as? UpdateState.Available)?.info ?: return
        if (!Updater.canInstall(ctx)) {
            _message.value = "Engedélyezd a telepítést ennek az appnak, majd koppints újra a frissítésre."
            Updater.openInstallPermission(ctx)
            return
        }
        viewModelScope.launch {
            update.value = UpdateState.Downloading(info, 0f)
            try {
                val f = Updater.download(ctx, info) { p -> update.value = UpdateState.Downloading(info, p) }
                update.value = UpdateState.Available(info)
                Updater.install(ctx, f)
            } catch (e: Exception) {
                update.value = UpdateState.Error(e.message ?: "A frissítés nem sikerült.")
            }
        }
    }

    fun clearMessage() { _message.value = null }
    fun clearScan() { _scan.value = null }
    fun closeSummary() { _summary.value = null }

    /* ---------------- vonalkód ---------------- */

    fun findByCode(raw: String): Product? {
        val c = raw.trim().replace(Regex("\\s+"), "")
        if (c.isEmpty()) return null
        val cz = c.trimStart('0')
        val list = state.value.products()
        return list.firstOrNull { it.ean.isNotBlank() && (it.ean == c || (cz.isNotEmpty() && it.ean.trimStart('0') == cz)) }
            ?: list.firstOrNull { it.sku.isNotBlank() && it.sku.equals(c, ignoreCase = true) }
    }

    /** Ebben a csomagolásban termékenként és módonként összesen (kulcs: "kiadás:<id>"), a „Csomagolás vége” nullázza. */
    private val sessionTotals = mutableMapOf<String, Int>()
    private var lastCameraProduct: String? = null
    private var lastCameraTime = 0L

    fun onCode(raw: String, fromCamera: Boolean = false) {
        val code = raw.trim().replace(Regex("\\s+"), "")
        if (code.isEmpty()) return
        val p = findByCode(code)
        if (p == null) {
            feedback(false)
            _scan.value = ScanResult.Unknown(code)
            return
        }
        // Kamera: ugyanazt a terméket 2,5 mp-en belül nem vonjuk le újra (pl. két vonalkód a dobozon)
        val now = System.currentTimeMillis()
        if (fromCamera) {
            if (p.id == lastCameraProduct && now - lastCameraTime < 2500) return
            lastCameraProduct = p.id; lastCameraTime = now
        }
        val delta = if (tab.value == Tab.IN) inQty.value.coerceAtLeast(1) else -1
        val type = if (tab.value == Tab.IN) MoveType.IN else MoveType.OUT
        viewModelScope.launch {
            val m = store.addMove(p, delta, type)
            val after = m.qtyAfter ?: (p.qty + delta)
            val key = "$type:${p.id}"
            val total = (sessionTotals[key] ?: 0) + delta
            sessionTotals[key] = total
            feedback(true, warn = after <= p.minQty)
            _scan.value = ScanResult.Done(p, m, after, p.minQty, sessionTotal = total)
        }
    }

    fun undo(r: ScanResult.Done) {
        viewModelScope.launch {
            store.undoMove(r.move)
            val now = state.value.products().firstOrNull { it.id == r.product.id }?.qty ?: (r.qtyAfter - r.move.delta)
            val key = "${r.move.type}:${r.product.id}"
            val total = (sessionTotals[key] ?: r.move.delta) - r.move.delta
            sessionTotals[key] = total
            lastCameraProduct = null
            _scan.value = r.copy(qtyAfter = now, undone = true, sessionTotal = total)
        }
    }

    fun quickAdjust(p: Product, delta: Int) {
        viewModelScope.launch { store.addMove(p, delta, MoveType.MANUAL) }
    }

    private fun feedback(ok: Boolean, warn: Boolean = false) {
        try {
            tone?.startTone(if (!ok) ToneGenerator.TONE_SUP_ERROR else if (warn) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, if (ok) 120 else 300)
        } catch (_: Exception) {}
        try {
            val ctx = getApplication<Application>()
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(if (ok) VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                        else VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        } catch (_: Exception) {}
    }

    /* ---------------- termékek ---------------- */

    fun saveProduct(p: Product, qty: Int, photo: ByteArray? = null) = viewModelScope.launch {
        store.saveProduct(p, qty)
        if (photo != null) store.setPhoto(p.id, photo)
        _message.value = "Mentve"
    }
    fun addFolder(path: String) = viewModelScope.launch { store.addFolder(path); _message.value = "Mappa létrehozva" }
    fun deleteFolder(path: String) = viewModelScope.launch { store.deleteFolder(path); _message.value = "Mappa törölve" }
    fun deleteProduct(id: String) = viewModelScope.launch { store.deleteProduct(id); _message.value = "Termék törölve" }

    /* ---------------- szinkron ---------------- */

    fun syncNow(silent: Boolean = false) {
        if (_syncing.value) return
        if (!store.isConfigured) { if (!silent) _message.value = "Előbb add meg a web app címét és a kulcsot a Beállításokban."; return }
        viewModelScope.launch {
            _syncing.value = true
            try {
                val r = store.sync()
                if (r.photoError != null) _message.value = "Egy fotót nem sikerült feltölteni, a következő szinkronnál újra próbálja."
                else if (!silent) _message.value = r.pushed?.let { "Elküldve: ${it.applied} mozgás. Készlet frissítve." } ?: "Készlet frissítve."
            } catch (e: Exception) {
                _message.value = e.message ?: "Nem sikerült a szinkronizálás."
            } finally {
                _syncing.value = false
            }
        }
    }

    fun endPacking() {
        if (_syncing.value) return
        viewModelScope.launch {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val s = state.value
            val all = (s.serverMoves + s.pendingMoves).distinctBy { it.id }
                .filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today && it.type == MoveType.OUT }
            val lines = all.groupBy { it.productId }.map { (_, ms) -> DaySummaryLine(ms.first().name, -ms.sumOf { it.delta }) }
                .filter { it.units != 0 }.sortedByDescending { it.units }
            val pendingBefore = s.pendingMoves.size
            var err: String? = null
            if (store.isConfigured) {
                _syncing.value = true
                try { store.sync() } catch (e: Exception) { err = e.message } finally { _syncing.value = false }
            } else err = "Nincs beállítva a szerver, ezért nem lett elküldve."
            val low = state.value.products().filter { it.level != StockLevel.OK }.sortedBy { it.qty }
            sessionTotals.clear()
            _summary.value = Summary(lines, lines.sumOf { it.units }, low, err, if (err == null) pendingBefore else 0)
        }
    }

    fun saveSettings(s: Settings) { store.saveSettings(s); _message.value = "Beállítások mentve"; syncNow(silent = false) }

    fun testConnection(s: Settings) {
        viewModelScope.launch {
            _message.value = try { store.testConnection(s); "A kapcsolat működik." } catch (e: Exception) { e.message }
        }
    }

    override fun onCleared() { tone?.release(); super.onCleared() }
}
