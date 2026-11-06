package hu.hanizso.raktar.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.hanizso.raktar.data.ImageUtil
import hu.hanizso.raktar.data.Product
import hu.hanizso.raktar.data.StockLevel
import hu.hanizso.raktar.data.Store
import java.text.Normalizer
import java.util.Locale

internal val HU_LOCALE = Locale("hu", "HU")

/** Ékezet- és kisbetű-független összehasonlításhoz: "Sötét" → "sotet" */
fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(HU_LOCALE), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

internal fun ftHu(v: Double?) = if (v == null) "" else String.format(HU_LOCALE, "%,.0f Ft", v)

/** Az elérési út utolsó tagja: "Mosószer/Mosógél" → "Mosógél" */
internal fun folderName(path: String) = path.substringAfterLast('/')

internal fun inFolder(p: Product, folder: String) =
    folder.isEmpty() || p.category == folder || p.category.startsWith("$folder/")

@Composable
fun StockScreen(
    vm: AppViewModel,
    products: List<Product>,
    folders: List<String>,
    folder: String,
    onFolder: (String) -> Unit,
    onEdit: (Product) -> Unit,
    onAdjust: (Product, Int) -> Unit,
) {
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var newFolder by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf<String?>(null) }
    var scanSearch by remember { mutableStateOf(false) }

    val scope = products.filter { inFolder(it, folder) }
    val units = scope.sumOf { it.qty.coerceAtLeast(0) }
    val value = scope.sumOf { it.qty.coerceAtLeast(0) * (it.buyPrice ?: 0.0) }

    // Keresés vagy szűrő esetén: lapos lista az aktuális mappa teljes tartalmából (almappákkal együtt)
    val flat = q.isNotBlank() || filter != "all"
    val fq = fold(q.trim())
    val list = if (flat) {
        scope.filter {
            when (filter) { "low" -> it.level != StockLevel.OK; "out" -> it.level == StockLevel.OUT; else -> true }
        }.filter {
            fq.isEmpty() || listOf(it.name, it.ean, it.sku, it.note, it.category).any { f -> fold(f).contains(fq) }
        }.sortedWith(compareBy<Product>({ if (filter == "all") 0 else it.qty }, { fold(it.name) }))
    } else {
        products.filter { it.category == folder }.sortedBy { fold(it.name) }
    }
    val subfolders = if (flat) emptyList() else folders.filter { f ->
        if (folder.isEmpty()) !f.contains('/') else f.startsWith("$folder/") && !f.removePrefix("$folder/").contains('/')
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Útvonal (morzsamenü)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Crumb("Összes", folder.isEmpty()) { onFolder("") }
                val parts = folder.split('/').filter { it.isNotEmpty() }
                parts.forEachIndexed { i, part ->
                    Icon(Icons.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    val path = parts.subList(0, i + 1).joinToString("/")
                    Crumb(part, i == parts.lastIndex) { onFolder(path) }
                }
            }
            Text("${scope.size} termék · $units db · készletérték ${ftHu(value)}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text(if (folder.isEmpty()) "Keresés: név, márka, EAN, cikkszám" else "Keresés ebben a mappában") },
                    singleLine = true, modifier = Modifier.weight(1f))
                // Csak keresés vonalkóddal: nem von le semmit a készletből
                OutlinedButton(onClick = { scanSearch = true }) { Text("Beolvasás") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(filter == "all", { filter = "all" }, { Text("Mind") })
                FilterChip(filter == "low", { filter = "low" }, { Text("Kevés") })
                FilterChip(filter == "out", { filter = "out" }, { Text("Elfogyott") })
                Spacer(Modifier.weight(1f))
                if (!flat) TextButton(onClick = { newFolder = true }) { Text("+ Új mappa") }
            }
        }

        if (products.isEmpty() && folders.isEmpty()) {
            EmptyText("Még nincs termék. Töltsd le a készletet (frissítés gomb fent), importáld a StoDo exportot a táblázatban, vagy vegyél fel egyet a + gombbal.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(subfolders, key = { "f:$it" }) { f ->
                    val inside = products.count { inFolder(it, f) }
                    val hasSub = folders.any { it.startsWith("$f/") }
                    Row(Modifier.fillMaxWidth().clickable { onFolder(f) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("📁", fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folderName(f), fontWeight = FontWeight.SemiBold)
                            Text("$inside termék", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (inside == 0 && !hasSub) {
                            IconButton(onClick = { deleteFolder = f }) { Icon(Icons.Filled.Delete, "Mappa törlése", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Icon(Icons.Filled.KeyboardArrowRight, null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (list.isEmpty() && subfolders.isEmpty()) {
                    item { EmptyText(if (flat) "Nincs a szűrésnek megfelelő termék." else "Ez a mappa üres. A + gombbal ide vehetsz fel terméket.") }
                }
                items(list, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onEdit(p) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProductThumb(p, 44)
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(p.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(
                                if (flat && p.category.isNotBlank()) "📁 ${p.category.replace("/", " › ")}" else null,
                                p.ean.ifBlank { null }, "min. ${p.minQty}",
                            ).joinToString(" · ")
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onAdjust(p, -1) }) { Text("−", fontSize = 20.sp) }
                        QtyPill(p)
                        TextButton(onClick = { onAdjust(p, 1) }) { Text("+", fontSize = 20.sp) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    if (scanSearch) {
        Dialog(onDismissRequest = { scanSearch = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth().padding(16.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Keresés vonalkóddal", style = MaterialTheme.typography.titleMedium)
                Text("Csak megkeresi a terméket, a készletből nem von le semmit.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                CameraScanner(Modifier.fillMaxWidth().height(300.dp)) { code ->
                    if (scanSearch) {
                        scanSearch = false
                        val hit = vm.findByCode(code)
                        if (hit != null) { onFolder(""); filter = "all"; q = hit.ean.ifBlank { hit.name } }
                        else { onFolder(""); filter = "all"; q = code }
                    }
                }
                TextButton(onClick = { scanSearch = false }, modifier = Modifier.align(Alignment.End)) { Text("Mégse") }
            }
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("Új mappa") },
            text = {
                Column {
                    if (folder.isNotEmpty()) Text("Helye: ${folder.replace("/", " › ")}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it.replace("/", "") }, label = { Text("Mappa neve, pl. Mosógél") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(enabled = name.isNotBlank(), onClick = {
                    val path = if (folder.isEmpty()) name.trim() else "$folder/${name.trim()}"
                    vm.addFolder(path); newFolder = false
                }) { Text("Létrehozás") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Mégse") } },
        )
    }
    deleteFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteFolder = null },
            title = { Text("Mappa törlése") },
            text = { Text("Törlöd a(z) „${folderName(f)}” mappát? Üres, termék nincs benne.") },
            confirmButton = { Button(onClick = { vm.deleteFolder(f); deleteFolder = null },
                colors = ButtonDefaults.buttonColors(containerColor = BadRed)) { Text("Törlés") } },
            dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text("Mégse") } },
        )
    }
}

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    Text(text, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !current, onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
}

/** A termék fotója, ha megvan a telefonon (különben nem rajzol semmit). */
@Composable
fun ProductThumb(p: Product, sizeDp: Int) {
    val ctx = LocalContext.current
    val file = remember(p.id) { Store.get(ctx).photoFile(p.id) }
    var bmp by remember(p.id, file.lastModified()) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(p.id, file.lastModified()) { bmp = ImageUtil.thumbnail(file, sizeDp * 3) }
    bmp?.let {
        Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop,
            modifier = Modifier.padding(end = 8.dp).size(sizeDp.dp).clip(RoundedCornerShape(8.dp)))
    }
}

@Composable
fun QtyPill(p: Product) {
    val (bg, fg) = when (p.level) { StockLevel.OUT -> BadSoft to BadRed; StockLevel.LOW -> WarnSoft to WarnAmber; StockLevel.OK -> OkSoft to OkGreen }
    Box(Modifier.background(bg, RoundedCornerShape(8.dp)).widthIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text("${p.qty}", color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EmptyText(t: String) {
    Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
}
