package org.lior.gymbro

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * The camera's view in a small frame, read for QR codes (and other barcodes) all the while the page is on the screen:
 * CameraX stops the camera when the page is stopped (another page, the screen off) and starts it again after.
 * [onCode] gets each code once; the same code again only after [repeatMs] without seeing it, or after [reset].
 */
class QrScanner(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val where: String,
    private val repeatMs: Long = 4000,
    private val onCode: (value: String, format: String) -> Unit,
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
    )
    private var lastValue: String? = null
    private var lastSeen = 0L
    private var frames = 0L
    private var binding = false

    /** While false, codes are seen (and logged) but not delivered. */
    @Volatile var enabled = true

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun reset() {
        lastValue = null
    }

    /**
     * Binds the camera to the page. Called at every onResume: another page's camera (the photo page) unbinds everything,
     * so the scanner binds itself again whenever its page comes back.
     */
    fun start() {
        if (binding || !hasPermission) return
        binding = true
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                AppLog.i("scan", "scanner started", "where" to where)
            } catch (e: Exception) {
                AppLog.e("scan", "the scanner could not start", e, "where" to where)
            }
            binding = false
        }, ContextCompat.getMainExecutor(activity))
    }

    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(proxy: ImageProxy) {
        val img = proxy.image
        if (img == null) {
            proxy.close()
            return
        }
        frames++
        if (frames == 1L) AppLog.i("scan", "first camera frame", "where" to where, "size" to "${proxy.width}x${proxy.height}",
            "rotation" to proxy.imageInfo.rotationDegrees)
        val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { codes ->
                val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                if (code != null) deliver(code, codes.size)
            }
            .addOnFailureListener { e -> AppLog.e("scan", "the barcode reader failed on a frame", e, "where" to where) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun deliver(code: Barcode, count: Int) {
        val value = code.rawValue ?: return
        val now = SystemClock.elapsedRealtime()
        val same = value == lastValue && now - lastSeen < repeatMs
        lastSeen = now
        if (same) return
        lastValue = value
        val format = formatName(code.format)
        AppLog.i("scan", "code seen", "where" to where, "value" to value, "format" to format, "codesInFrame" to count,
            "valueType" to code.valueType, "box" to code.boundingBox?.toShortString(), "delivered" to enabled, "frames" to frames)
        if (enabled) onCode(value, format)
    }

    companion object {
        fun formatName(f: Int): String = when (f) {
            Barcode.FORMAT_QR_CODE -> "QR_CODE"
            Barcode.FORMAT_AZTEC -> "AZTEC"
            Barcode.FORMAT_CODABAR -> "CODABAR"
            Barcode.FORMAT_CODE_39 -> "CODE_39"
            Barcode.FORMAT_CODE_93 -> "CODE_93"
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
            Barcode.FORMAT_EAN_8 -> "EAN_8"
            Barcode.FORMAT_EAN_13 -> "EAN_13"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_UPC_A -> "UPC_A"
            Barcode.FORMAT_UPC_E -> "UPC_E"
            else -> "FORMAT_$f"
        }
    }
}
