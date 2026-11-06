package com.example.peersync.desktop

import com.example.peersync.text.PsText
import com.example.peersync.AppInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.example.peersync.log.PsLog
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "${AppInfo.NAME}/QrScan"

/**
 * Separate window that scans QR codes with the webcam (old Windows CameraScanner + HomePage scan UI).
 * Captures frames with OpenCV (bundled natives) and decodes with ZXing every ~150 ms.
 */
@Composable
fun QrScanWindow(onClose: () -> Unit, onFound: (String) -> Unit) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val stop = AtomicBoolean(false)
        val worker = thread(name = "qr-scan", isDaemon = true) {
            try {
                nu.pattern.OpenCV.loadLocally()
                val cap = org.opencv.videoio.VideoCapture(0)
                if (!cap.isOpened) {
                    error = "Couldn't open the webcam."
                    PsLog.w(TAG, "camera open failed")
                    return@thread
                }
                PsLog.d(TAG, "camera opened")
                val mat = org.opencv.core.Mat()
                val reader = MultiFormatReader().apply {
                    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                }
                var lastDecode = 0L
                while (!stop.get()) {
                    if (!cap.read(mat) || mat.empty()) continue
                    val w = mat.cols()
                    val h = mat.rows()
                    val bytes = ByteArray(w * h * 3)
                    mat.get(0, 0, bytes)
                    // Same flow as the old Windows version (0.1.1, CameraScanner.cs): explicitly repack the frame
                    // into a pixel array with a fixed channel order (like Bgra8), then pass the same array
                    // to both the preview and ZXing. OpenCV frames are 3-byte BGR, so take
                    // B, G, R and build ARGB ints.
                    // 0.2.0–0.2.6 passed a TYPE_3BYTE_BGR BufferedImage straight to
                    // toComposeImageBitmap(), but Compose (Skia) reads the bytes as RGB,
                    // so red and blue were swapped (a red shirt showed up blue).
                    val pixels = IntArray(w * h)
                    var i = 0
                    for (p in 0 until w * h) {
                        val b = bytes[i].toInt() and 0xFF
                        val g = bytes[i + 1].toInt() and 0xFF
                        val r = bytes[i + 2].toInt() and 0xFF
                        pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        i += 3
                    }
                    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                    img.setRGB(0, 0, w, h, pixels, 0, w)
                    frame = img.toComposeImageBitmap()

                    val now = System.currentTimeMillis()
                    if (now - lastDecode > 150) {
                        lastDecode = now
                        val source = RGBLuminanceSource(w, h, pixels)
                        val result = try {
                            reader.decode(com.google.zxing.BinaryBitmap(HybridBinarizer(source)))
                        } catch (_: Exception) {
                            null
                        } finally {
                            reader.reset()
                        }
                        if (result != null) {
                            PsLog.d(TAG, "qr found")
                            stop.set(true)
                            cap.release()
                            onFound(result.text)
                            return@thread
                        }
                    }
                }
                cap.release()
            } catch (t: Throwable) {
                PsLog.w(TAG, "scanner failed: ${t.message ?: t}")
                error = "Couldn't start the camera: ${t.message}"
            }
        }
        onDispose {
            stop.set(true)
            runCatching { worker.join(1000) }
        }
    }

    Window(
        onCloseRequest = onClose,
        title = "${PsText.addByQr} — ${PsText.scanQrPrompt}",
        state = rememberWindowState(width = 640.dp, height = 560.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val f = frame
                when {
                    error != null -> Text(error!!)
                    f != null -> Image(f, contentDescription = "Camera preview", Modifier.fillMaxSize())
                    else -> CircularProgressIndicator()
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text(PsText.cancel) }
        }
    }
}
