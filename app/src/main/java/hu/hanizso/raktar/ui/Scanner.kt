package hu.hanizso.raktar.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Élő kamerás vonalkódolvasó. Ugyanazt a kódot csak akkor jelzi újra,
 * ha legalább 1,5 másodpercig nem volt a képen (így két egyforma termék
 * egymás után is beolvasható: elveszed, majd újra odatartod).
 */
@Composable
fun CameraScanner(modifier: Modifier = Modifier, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onCode)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text("A vonalkódolvasáshoz engedélyezni kell a kamerát.")
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Kamera engedélyezése")
                }
            }
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE,
            ).build()
        )
    }
    // COMPATIBLE (TextureView): a kép nem lóg ki a dobozból a többi elem fölé
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(ctx)
        // kódonként: mikor láttuk utoljára, és mikor számoltuk utoljára
        val seen = HashMap<String, Long>()
        val counted = HashMap<String, Long>()
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(img)
                    .addOnSuccessListener { codes ->
                        val v = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        val now = System.currentTimeMillis()
                        if (v != null) {
                            val gone = now - (seen[v] ?: 0L) > 1200      // közben kikerült a képből
                            val waited = now - (counted[v] ?: 0L) > 2500  // és eltelt legalább 2,5 mp
                            if (gone && waited) {
                                counted[v] = now
                                ContextCompat.getMainExecutor(ctx).execute { callback(v) }
                            }
                            seen[v] = now
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            try { future.get().unbindAll() } catch (_: Exception) {}
        }
    }

    Box(modifier.clipToBounds().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // célzó keret
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.75f)
                .fillMaxHeight(0.45f)
                .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
        )
    }
}
