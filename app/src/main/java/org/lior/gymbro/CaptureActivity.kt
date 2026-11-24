package org.lior.gymbro

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Takes one square photo of a station (user: "its important that the picture is square") and returns its file (a JPEG
 * in the app's cache) as [EXTRA_PATH]. The square on the screen is exactly what is kept: the middle of the camera's
 * picture.
 */
class CaptureActivity : AppCompatActivity() {
    private var capture: ImageCapture? = null
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var shutter: ImageButton
    private var busy = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        AppLog.i("capture", "camera permission answered", "granted" to ok)
        if (ok) startCamera() else { Toast.makeText(this, "GymBro needs the camera to take the station's picture", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        status = findViewById(R.id.capture_status)
        shutter = findViewById(R.id.capture_shutter)
        findViewById<ImageButton>(R.id.capture_back).setOnClickListener { AppLog.i("capture", "back tapped"); finish() }
        shutter.setOnClickListener { take() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(findViewById<PreviewView>(R.id.capture_preview).surfaceProvider) }
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                capture = ic
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                AppLog.i("capture", "camera started")
            } catch (e: Exception) {
                AppLog.e("capture", "the camera could not start", e)
                Toast.makeText(this, "The camera could not start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun take() {
        val ic = capture ?: return
        if (busy) return
        busy = true
        shutter.isEnabled = false
        status.text = "Saving…"
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i("capture", "shutter tapped")
        ic.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    val w = raw.width
                    val h = raw.height
                    image.close()
                    val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                    val bytes = square(raw, rotation, file)
                    AppLog.i("capture", "photo taken", "raw" to "${w}x$h", "rotation" to rotation, "bytes" to bytes,
                        "ms" to SystemClock.elapsedRealtime() - t0)
                    runOnUiThread {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, file.path))
                        finish()
                    }
                } catch (e: Throwable) {
                    try { image.close() } catch (_: Throwable) {}
                    fail(e)
                }
            }

            override fun onError(exception: ImageCaptureException) = fail(exception)
        })
    }

    private fun fail(e: Throwable) {
        AppLog.e("capture", "the photo failed", e)
        runOnUiThread {
            busy = false
            shutter.isEnabled = true
            status.text = ""
            Toast.makeText(this, "The photo failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Upright, the middle square, at most [SIZE] px, as a JPEG; returns its size in bytes. */
    private fun square(raw: Bitmap, rotation: Int, out: File): Long {
        val side = min(raw.width, raw.height)
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val scale = if (side > SIZE) SIZE.toFloat() / side else 1f
        m.postScale(scale, scale)
        val sq = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side, m, true)
        out.outputStream().use { sq.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (sq !== raw) sq.recycle()
        raw.recycle()
        return out.length()
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val SIZE = 1080
    }
}
