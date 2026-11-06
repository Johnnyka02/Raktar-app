@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.hanizso.raktar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import hu.hanizso.raktar.data.ImageUtil
import hu.hanizso.raktar.data.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ProductScreen(
    vm: AppViewModel,
    product: Product,
    isNew: Boolean,
    folders: List<String>,
    onClose: () -> Unit,
    onSave: (Product, Int, ByteArray?) -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(product.name) }
    var ean by remember { mutableStateOf(product.ean) }
    var sku by remember { mutableStateOf(product.sku) }
    var category by remember { mutableStateOf(product.category) }
    var qty by remember { mutableStateOf(if (isNew) "0" else product.qty.toString()) }
    var min by remember { mutableStateOf(product.minQty.toString()) }
    var buy by remember { mutableStateOf(product.buyPrice?.let { "%.0f".format(it) } ?: "") }
    var sell by remember { mutableStateOf(product.sellPrice?.let { "%.0f".format(it) } ?: "") }
    var note by remember { mutableStateOf(product.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanEan by remember { mutableStateOf(false) }

    var newPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var photoBmp by remember { mutableStateOf<Bitmap?>(null) }
    var photoBusy by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    // Meglévő fotó betöltése (ha kell, letöltés a Drive-ról)
    LaunchedEffect(product.id) {
        if (!isNew) {
            val f = vm.store.ensurePhoto(product)
            if (f != null) photoBmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) }
        }
    }

    fun usePhoto(uri: Uri?) {
        if (uri == null) return
        photoBusy = true
        scope.launch {
            val bytes = ImageUtil.prepareJpeg(ctx, uri)
            photoBusy = false
            if (bytes == null) { error = "A képet nem sikerült betölteni."; return@launch }
            newPhoto = bytes
            photoBmp = ImageUtil.decode(bytes)
        }
    }

    val captureFile = remember { File(ctx.cacheDir, "camera").apply { mkdirs() }.let { File(it, "capture.jpg") } }
    val captureUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", captureFile) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) usePhoto(captureUri) }
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) takePicture.launch(captureUri) else error = "A fotózáshoz engedélyezni kell a kamerát."
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> usePhoto(uri) }

    fun startCamera() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePicture.launch(captureUri)
        else camPermission.launch(Manifest.permission.CAMERA)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (isNew) "Új termék" else "Termék szerkesztése") },
            navigationIcon = { TextButton(onClick = onClose) { Text("Mégse") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Fotó
            Box(
                Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val b = photoBmp
                if (b != null) Image(b.asImageBitmap(), "Termékfotó", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Text(if (photoBusy) "Kép feldolgozása…" else "Nincs fotó", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::startCamera, modifier = Modifier.weight(1f)) { Text(if (photoBmp == null) "Fotó készítése" else "Új fotó") }
                OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f)) { Text("Galériából") }
            }

            OutlinedTextField(name, { name = it }, label = { Text("Megnevezés (márkával együtt) *") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ean, { ean = it.trim() }, label = { Text("EAN / vonalkód") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                OutlinedButton(onClick = { scanEan = true }) { Text("Beolvasás") }
            }
            FolderPicker(folders, category) { category = it }
            OutlinedTextField(sku, { sku = it }, label = { Text("Cikkszám") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(qty, { qty = it.filter { c -> c.isDigit() || c == '-' } }, label = { Text("Készlet (db)") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(min, { min = it.filter(Char::isDigit) }, label = { Text("Minimum (db)") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(buy, { buy = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Beszerzési ár (Ft)") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(sell, { sell = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, label = { Text("Eladási ár (Ft)") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            OutlinedTextField(note, { note = it }, label = { Text("Rövid leírás") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            if (!isNew && qty != product.qty.toString()) {
                Text("A készlet módosítása „korrekció”-ként kerül a naplóba.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = BadRed) }

            Button(
                onClick = {
                    val taken = ean.takeIf { it.isNotBlank() }?.let { vm.findByCode(it) }?.takeIf { it.id != product.id }
                    when {
                        name.isBlank() -> error = "Add meg a megnevezést."
                        taken != null -> error = "Ez a vonalkód már a(z) „${taken.name}” terméknél szerepel."
                        else -> onSave(
                            product.copy(name = name.trim(), ean = ean, sku = sku.trim(), category = category,
                                minQty = min.toIntOrNull() ?: 0, buyPrice = buy.replace(',', '.').toDoubleOrNull(),
                                sellPrice = sell.replace(',', '.').toDoubleOrNull(), note = note.trim()),
                            qty.toIntOrNull() ?: 0, newPhoto,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Mentés") }

            if (!isNew) {
                if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Termék törlése", color = BadRed) }
                else Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = BadRed), modifier = Modifier.fillMaxWidth()) {
                    Text("Biztosan törlöd? A naplóban megmaradnak a korábbi mozgásai.")
                }
            }
        }
    }

    if (scanEan) {
        Dialog(onDismissRequest = { scanEan = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth().padding(16.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tartsd a vonalkódot a keretbe", style = MaterialTheme.typography.titleMedium)
                CameraScanner(Modifier.fillMaxWidth().height(300.dp)) { code ->
                    if (scanEan) { ean = code; scanEan = false; error = null }
                }
                TextButton(onClick = { scanEan = false }, modifier = Modifier.align(Alignment.End)) { Text("Mégse") }
            }
        }
    }
}

@Composable
private fun FolderPicker(folders: List<String>, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label: (String) -> String = { if (it.isEmpty()) "(nincs mappában)" else it.replace("/", " › ") }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = label(value), onValueChange = {}, readOnly = true, label = { Text("Mappa") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf("") + folders).forEach { f ->
                DropdownMenuItem(text = { Text(label(f)) }, onClick = { onChange(f); open = false })
            }
        }
    }
    if (folders.isEmpty()) {
        Text("Mappát a Készlet fülön az „+ Új mappa” gombbal hozhatsz létre.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
