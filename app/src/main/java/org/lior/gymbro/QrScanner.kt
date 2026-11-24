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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
    )
    private var lastValue: String? = null
    private var lastSeen = 0L
    private var lastDelivered: String? = null
    private var blocked: String? = null
    private var blockedSeen = 0L
    private var frames = 0L
    private var binding = false

    @Volatile private var closed = false
    private var analysis: ImageAnalysis? = null

    init {
        // the page is gone: no more frames to the reader, and the reader is let go
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                closed = true
                analysis?.clearAnalyzer()
                scanner.close()
            }
        })
    }

    /** While false, codes are seen (and logged) but not delivered. */
    @Volatile var enabled = true

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun reset() {
        lastValue = null
    }

    /**
     * The code delivered last is not delivered again until it has been out of the camera's view for [GONE_MS]
     * (back on the launcher after a set, the machine's QR code may still be in view: it must not open the station again).
     */
    fun blockLast() {
        blocked = lastDelivered
        blockedSeen = SystemClock.elapsedRealtime()
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
            if (closed) {
                binding = false
                return@addListener
            }
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
                this.analysis = analysis
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
        if (img == null || closed) {
            proxy.close()
            return
        }
        frames++
        if (frames == 1L) AppLog.i("scan", "first camera frame", "where" to where, "size" to "${proxy.width}x${proxy.height}",
            "rotation" to proxy.imageInfo.rotationDegrees)
        try {
            val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { codes ->
                    val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                    if (code != null && !closed) deliver(code, codes.size)
                }
                .addOnFailureListener { e -> if (!closed) AppLog.e("scan", "the barcode reader failed on a frame", e, "where" to where) }
                .addOnCompleteListener { proxy.close() }
        } catch (e: Exception) {
            proxy.close()
            if (!closed) AppLog.e("scan", "a frame could not be read", e, "where" to where)
        }
    }

    private fun deliver(code: Barcode, count: Int) {
        val value = code.rawValue ?: return
        val now = SystemClock.elapsedRealtime()
        if (value == blocked) {
            if (now - blockedSeen < GONE_MS) {
                blockedSeen = now
                return
            }
            blocked = null
        }
        val same = value == lastValue && now - lastSeen < repeatMs
        lastSeen = now
        if (same) return
        lastValue = value
        val format = formatName(code.format)
        AppLog.i("scan", "code seen", "where" to where, "value" to value, "format" to format, "codesInFrame" to count,
            "valueType" to code.valueType, "box" to code.boundingBox?.toShortString(), "delivered" to enabled, "frames" to frames)
        if (enabled) {
            lastDelivered = value
            onCode(value, format)
        }
    }

    companion object {
        const val GONE_MS = 2000L

        /** One thread for the frames of every scanner (one page has the camera at a time). */
        private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "gymbro-scan") }

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
