@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.hanizso.raktar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import hu.hanizso.raktar.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.hanizso.raktar.data.Move
import hu.hanizso.raktar.data.MoveType
import hu.hanizso.raktar.data.Product
import hu.hanizso.raktar.data.Settings
import hu.hanizso.raktar.data.StockLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val HU = Locale("hu", "HU")
private val dayFmt = DateTimeFormatter.ofPattern("yyyy. MMMM d., EEEE", HU)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", HU)
private val dtFmt = DateTimeFormatter.ofPattern("MM.dd. HH:mm", HU)
private fun zdt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
private fun ft(v: Double?) = if (v == null) "" else String.format(HU, "%,.0f Ft", v)

@Composable
fun MainScreen(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val message by vm.message.collectAsState()
    val tab by vm.tab.collectAsState()
    val summary by vm.summary.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    var stockFolder by rememberSaveable { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snack.showSnackbar(it); vm.clearMessage() }
    }

    if (showSettings) {
        SettingsScreen(settings, onBack = { showSettings = false }, onSave = { vm.saveSettings(it); showSettings = false }, onTest = vm::testConnection)
        return
    }

    val openNew: (String) -> Unit = { code ->
        editing = Product(id = UUID.randomUUID().toString(), ean = code, name = "", minQty = 2,
            category = if (tab == Tab.STOCK) stockFolder else "")
        editingNew = true
    }

    editing?.let { p ->
        ProductScreen(
            vm = vm, product = p, isNew = editingNew, folders = state.folders(),
            onClose = { editing = null },
            onSave = { np, q, photo -> vm.saveProduct(np, q, photo); editing = null; vm.clearScan() },
            onDelete = { vm.deleteProduct(p.id); editing = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HaNiZso Raktár", fontWeight = FontWeight.Bold)
                        val last = if (state.lastSync == 0L) "még nem volt szinkron" else "szinkron: " + zdt(state.lastSync).format(dtFmt)
                        val pend = if (state.pendingCount > 0) " · ${state.pendingCount} el nem küldött" else ""
                        Text(last + pend, style = MaterialTheme.typography.labelMedium,
                            color = if (state.pendingCount > 0) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (syncing) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.syncNow() }) { Icon(Icons.Filled.Refresh, "Szinkronizálás most") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Beállítások") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.OUT, { vm.tab.value = Tab.OUT; vm.clearScan() }, { Icon(Icons.Filled.ShoppingCart, null) }, label = { Text("Kiadás") })
                NavigationBarItem(tab == Tab.IN, { vm.tab.value = Tab.IN; vm.clearScan() }, { Icon(Icons.Filled.Add, null) }, label = { Text("Bevét") })
                val low = state.products().count { it.level != StockLevel.OK }
                NavigationBarItem(tab == Tab.STOCK, { vm.tab.value = Tab.STOCK }, { Icon(Icons.Filled.List, null) },
                    label = { Text(if (low > 0) "Készlet ($low)" else "Készlet") })
                NavigationBarItem(tab == Tab.LOG, { vm.tab.value = Tab.LOG }, { Icon(Icons.Filled.DateRange, null) }, label = { Text("Napló") })
            }
        },
        floatingActionButton = {
            if (tab == Tab.STOCK) FloatingActionButton(onClick = { openNew("") }) { Icon(Icons.Filled.Add, "Új termék") }
        },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // Halvány HaNiZso logó a háttérben
            Image(
                painter = painterResource(R.drawable.logo_watermark), contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                alpha = 0.06f, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.85f),
            )
        Column(Modifier.fillMaxSize()) {
            if (!vm.store.isConfigured) {
                Banner("Az első használat előtt add meg a web app címét és a kulcsot a Beállításokban.") { showSettings = true }
            }
            when (tab) {
                Tab.OUT, Tab.IN -> ScanScreen(vm, tab, onNew = openNew)
                Tab.STOCK -> StockScreen(
                    vm = vm, products = state.products(), folders = state.folders(),
                    folder = stockFolder, onFolder = { stockFolder = it },
                    onEdit = { editing = it; editingNew = false }, onAdjust = vm::quickAdjust,
                )
                Tab.LOG -> LogScreen(state.serverMoves, state.pendingMoves)
            }
        }
        }
    }

    summary?.let { SummaryDialog(it, onClose = vm::closeSummary) }
}

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
    Surface(color = WarnSoft, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(text, color = WarnAmber, modifier = Modifier.padding(12.dp))
    }
}

/* ======================= KIADÁS / BEVÉT ======================= */

@Composable
private fun ScanScreen(vm: AppViewModel, tab: Tab, onNew: (String) -> Unit) {
    val result by vm.scan.collectAsState()
    val inQty by vm.inQty.collectAsState()
    val state by vm.state.collectAsState()
    var camera by rememberSaveable { mutableStateOf(true) }
    var manual by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    val today = LocalDate.now()
    val todayOut = (state.serverMoves + state.pendingMoves).distinctBy { it.id }
        .filter { it.type == MoveType.OUT && zdt(it.at).toLocalDate() == today }.sumOf { -it.delta }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (tab == Tab.IN) "Bevételezés" else "Kiadás csomagoláshoz", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (tab == Tab.IN) "Állítsd be a mennyiséget, majd olvasd be a kódot." else "Minden beolvasás 1 db-ot levon. Ma kiadva: $todayOut db",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Kamera", style = MaterialTheme.typography.labelMedium)
            Switch(camera, { camera = it }, Modifier.padding(start = 6.dp))
        }

        if (camera) {
            CameraScanner(Modifier.fillMaxWidth().height(240.dp).background(Color.Black, RoundedCornerShape(12.dp))) { vm.onCode(it) }
        }

        if (tab == Tab.IN) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mennyiség:")
                OutlinedButton(onClick = { vm.inQty.value = (inQty - 1).coerceAtLeast(1) }) { Text("−") }
                Text("$inQty db", style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 56.dp))
                OutlinedButton(onClick = { vm.inQty.value = inQty + 1 }) { Text("+") }
                OutlinedButton(onClick = { vm.inQty.value = inQty + 10 }) { Text("+10") }
            }
        }

        // Kézi beírás / Bluetooth vonalkódolvasó (billentyűzetként ide ír)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val submit = { if (manual.isNotBlank()) { vm.onCode(manual); manual = "" } }
            OutlinedTextField(
                value = manual, onValueChange = { manual = it.replace("\n", "") },
                label = { Text("Vonalkód vagy cikkszám") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                    if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyUp) { submit(); true } else false
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            )
            Button(onClick = submit) { Text("OK") }
        }

        result?.let { ResultCard(it, onUndo = vm::undo, onNew = onNew) }

        if (tab == Tab.OUT) {
            Spacer(Modifier.height(4.dp))
            Button(onClick = vm::endPacking, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Done, null); Spacer(Modifier.width(8.dp)); Text("Csomagolás vége – küldés", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ResultCard(r: ScanResult, onUndo: (ScanResult.Done) -> Unit, onNew: (String) -> Unit) {
    when (r) {
        is ScanResult.Unknown -> ColoredCard(BadSoft, BadRed) {
            Text("Ismeretlen vonalkód", fontWeight = FontWeight.Bold, color = BadRed)
            Text(r.code, fontFamily = FontFamily.Monospace)
            Button(onClick = { onNew(r.code) }, modifier = Modifier.padding(top = 6.dp)) { Text("Új termék ezzel a kóddal") }
        }
        is ScanResult.Info -> ColoredCard(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface) { Text(r.text) }
        is ScanResult.Done -> {
            val (bg, fg) = when {
                r.undone -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
                r.qtyAfter <= 0 -> BadSoft to BadRed
                r.qtyAfter <= r.minQty -> WarnSoft to WarnAmber
                else -> OkSoft to OkGreen
            }
            ColoredCard(bg, fg) {
                ProductThumb(r.product, 96)
                Text(r.product.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.let { if (bg == MaterialTheme.colorScheme.surfaceVariant) it else Color(0xFF16201C) })
                if (r.undone) {
                    Text("Visszavonva. Készlet: ${r.qtyAfter} db")
                } else {
                    Text((if (r.move.delta > 0) "+" else "") + "${r.move.delta} db", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = fg)
                    Text("Maradt: ${r.qtyAfter} db", color = Color(0xFF16201C))
                    when {
                        r.qtyAfter < 0 -> Text("Figyelem: a készlet mínuszba ment. Érdemes leltározni ezt a terméket.", color = BadRed)
                        r.qtyAfter == 0 -> Text("Elfogyott.", color = BadRed)
                        r.qtyAfter <= r.minQty -> Text("Kevés van belőle, ideje utánrendelni.", color = WarnAmber)
                    }
                    OutlinedButton(onClick = { onUndo(r) }, modifier = Modifier.padding(top = 6.dp)) { Text("Visszavonás") }
                }
            }
        }
    }
}

@Composable
private fun ColoredCard(bg: Color, fg: Color, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

/* ======================= NAPLÓ ======================= */

private val typeLabel = mapOf(MoveType.OUT to "Kiadás", MoveType.IN to "Bevét", MoveType.FIX to "Korrekció", MoveType.MANUAL to "Kézi", "import" to "Import", "visszavonás" to "Visszavonás")

@Composable
private fun LogScreen(server: List<Move>, pending: List<Move>) {
    var q by rememberSaveable { mutableStateOf("") }
    val pendingIds = pending.map { it.id }.toSet()
    val all = (server + pending).distinctBy { it.id }
        .filter { q.isBlank() || fold(it.name).contains(fold(q.trim())) || it.ean.contains(q.trim()) || fold(it.device).contains(fold(q.trim())) }
        .sortedByDescending { it.at }
    val byDay = all.groupBy { zdt(it.at).toLocalDate() }
    val expanded = remember { mutableStateListOf<LocalDate>().apply { byDay.keys.firstOrNull()?.let { add(it) } } }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, { q = it }, label = { Text("Keresés: termék, EAN, telefon") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (all.isEmpty()) {
            EmptyText(if (q.isBlank()) "Még nincs mozgás. Itt jelenik meg napokra bontva minden kiadás és bevét, azzal együtt, melyik telefonról történt." else "Nincs találat.")
            return
        }
        Text("Az elmúlt 90 nap látszik. A régebbiek a táblázat Mozgások lapján vannak.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            byDay.forEach { (day, moves) ->
                val outs = moves.filter { it.type == MoveType.OUT }
                val units = -outs.sumOf { it.delta }
                val items = outs.map { it.productId }.distinct().size
                val ins = moves.filter { it.type == MoveType.IN }.sumOf { it.delta }
                val open = day in expanded
                item(key = "d$day") {
                    Row(Modifier.fillMaxWidth().clickable { if (open) expanded.remove(day) else expanded.add(day) }
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(day.format(dayFmt).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                            val parts = mutableListOf<String>()
                            if (units > 0) parts += "kiadva $items termék, $units db"
                            if (ins > 0) parts += "bevét $ins db"
                            if (parts.isEmpty()) parts += "${moves.size} mozgás"
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                    }
                }
                if (open) {
                    // Napi összesítő termékenként
                    val perProduct = outs.groupBy { it.productId }.map { (_, ms) -> ms.first().name to -ms.sumOf { it.delta } }.filter { it.second != 0 }.sortedByDescending { it.second }
                    if (perProduct.isNotEmpty()) item(key = "s$day") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Összesítve", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            perProduct.forEach { (n, u) ->
                                Row { Text(n, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("$u db", fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                    items(moves, key = { "m" + it.id }) { m ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(zdt(m.at).format(timeFmt), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val meta = listOfNotNull(typeLabel[m.type] ?: m.type, m.qtyAfter?.let { "maradt $it" }, m.device.ifBlank { null },
                                    if (m.id in pendingIds) "nincs elküldve" else null).joinToString(" · ")
                                Text(meta, style = MaterialTheme.typography.bodySmall, color = if (m.id in pendingIds) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text((if (m.delta > 0) "+" else "") + m.delta, fontWeight = FontWeight.Bold, color = if (m.delta < 0) BadRed else OkGreen)
                        }
                    }
                }
            }
        }
    }
}

/* ======================= CSOMAGOLÁS VÉGE ======================= */

@Composable
private fun SummaryDialog(s: Summary, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Mai csomagolás") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.syncError != null) {
                    Text("Nem sikerült elküldeni: ${s.syncError}\nA mozgások a telefonon vannak, és később automatikusan elmennek.", color = BadRed)
                } else {
                    Text("Elküldve, a táblázat frissítve.", color = OkGreen)
                }
                Text("Ma kiadva: ${s.lines.size} termék, ${s.totalUnits} db", fontWeight = FontWeight.SemiBold)
                s.lines.forEach { l -> Row { Text(l.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${l.units} db") } }
                if (s.low.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Utánrendelni (minimum alatt):", fontWeight = FontWeight.SemiBold, color = WarnAmber)
                    s.low.forEach { p -> Row { Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${p.qty} db (min. ${p.minQty})") } }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("Rendben") } },
    )
}

/* ======================= BEÁLLÍTÁSOK ======================= */

@Composable
private fun SettingsScreen(current: Settings, onBack: () -> Unit, onSave: (Settings) -> Unit, onTest: (Settings) -> Unit) {
    var url by remember { mutableStateOf(current.url) }
    var key by remember { mutableStateOf(current.key) }
    var dev by remember { mutableStateOf(current.deviceName) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Beállítások") }, navigationIcon = { TextButton(onClick = onBack) { Text("Vissza") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it.trim() }, label = { Text("Web app címe (…/exec)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it.trim() }, label = { Text("API kulcs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(dev, { dev = it }, label = { Text("A telefon neve a naplóban") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Pl. „Zsolt telefonja”. Ebből látszik a naplóban, ki adta ki a terméket.") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onTest(Settings(url, key, dev)) }) { Text("Kapcsolat tesztelése") }
                Button(onClick = { onSave(Settings(url, key, dev)) }) { Text("Mentés") }
            }
            Text("A címet és a kulcsot a Google-táblázatban találod: Raktár menü → API kulcs megjelenítése, illetve a web app közzétételekor kapott cím.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
