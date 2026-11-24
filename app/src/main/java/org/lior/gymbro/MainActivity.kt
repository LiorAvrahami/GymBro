package org.lior.gymbro

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import com.google.android.material.snackbar.Snackbar
import org.lior.gymbro.core.Stats
import org.lior.gymbro.core.launcherOrder
import java.time.ZoneId

/**
 * The launcher: pick the station you are working out on, by tapping its picture or by showing its QR code to the camera
 * (the scanner is on all the while this page is on the screen). Each station shows the reps done on it today.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var grid: StationGrid
    private lateinit var empty: TextView
    private lateinit var banner: TextView
    private lateinit var cameraOff: TextView
    private lateinit var scanner: QrScanner

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        AppLog.i("main", "camera permission answered", "granted" to ok)
        updateCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Repo.hasFolder) {
            AppLog.i("main", "no data folder yet: the setup page first")
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.main_grid)
        empty = findViewById(R.id.main_empty)
        banner = findViewById(R.id.main_banner)
        cameraOff = findViewById(R.id.main_camera_off)
        findViewById<ImageButton>(R.id.main_stats).setOnClickListener {
            AppLog.i("main", "statistics tapped")
            startActivity(Intent(this, StatsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.main_add).setOnClickListener {
            AppLog.i("main", "add station tapped")
            startActivity(Intent(this, EditStationActivity::class.java))
        }
        banner.setOnClickListener {
            AppLog.i("main", "storage banner tapped")
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_CHANGE, true))
        }
        cameraOff.setOnClickListener {
            AppLog.i("main", "camera permission asked from the scanner frame")
            permission.launch(Manifest.permission.CAMERA)
        }
        scanner = QrScanner(this, findViewById<PreviewView>(R.id.main_preview), "launcher") { value, format -> onCode(value, format) }
        if (!scanner.hasPermission) permission.launch(Manifest.permission.CAMERA)
    }

    private fun updateCamera() {
        val ok = scanner.hasPermission
        cameraOff.visibility = if (ok) View.GONE else View.VISIBLE
        findViewById<View>(R.id.main_scan_hint).visibility = if (ok) View.VISIBLE else View.GONE
        if (ok) scanner.start()
    }

    override fun onResume() {
        super.onResume()
        scanner.enabled = true
        scanner.reset()
        updateCamera()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        scanner.enabled = false
    }

    private fun refresh() {
        val data = Repo.data
        val stations = launcherOrder(data.stations)
        val today = Stats.repsTodayByStation(data.uses, System.currentTimeMillis(), ZoneId.systemDefault())
        grid.removeAllViews()
        for (s in stations) {
            val tile = StationTile(this)
            tile.name = s.name
            tile.repsToday = today[s.id] ?: 0
            tile.contentDescription = s.name
            s.mainImage?.let { img ->
                val f = Repo.imageFile(img)
                ImageLoader.load(f, dp(200)) { bmp -> if (bmp != null) tile.bitmap = bmp }
            }
            tile.setOnClickListener {
                AppLog.i("main", "station tapped", "station" to s.name, "id" to s.id, "repsToday" to tile.repsToday)
                openStation(s.id, "grid")
            }
            tile.setOnLongClickListener {
                AppLog.i("main", "station long-pressed: edit", "station" to s.name, "id" to s.id)
                startActivity(Intent(this, EditStationActivity::class.java).putExtra(EditStationActivity.EXTRA_ID, s.id))
                true
            }
            grid.addView(tile)
        }
        empty.visibility = if (stations.isEmpty()) View.VISIBLE else View.GONE
        AppLog.i("main", "launcher shown", "stations" to stations.size, "withQr" to stations.count { it.hasQr },
            "repsToday" to today.values.sum())
        // is the user's data folder still there?
        Thread {
            val ok = Repo.folderReachable()
            val failed = Repo.lastFolderWriteOk == false
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    !ok -> showBanner("Your data folder (${Repo.folderDescription()}) cannot be reached, so the data is only on this phone's app storage. Tap to choose the folder again.")
                    failed -> showBanner("The last save to your data folder failed (${Repo.lastFolderError}). Tap to choose the folder again.")
                    else -> banner.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun showBanner(text: String) {
        if (banner.visibility != View.VISIBLE || banner.text != text) AppLog.w("main", "storage banner shown", "text" to text)
        banner.text = text
        banner.visibility = View.VISIBLE
    }

    private fun onCode(value: String, format: String) {
        val s = Repo.data.stationByQr(value)
        if (s != null) {
            AppLog.i("main", "QR matched a station", "station" to s.name, "id" to s.id, "value" to value)
            openStation(s.id, "qr")
        } else {
            AppLog.i("main", "QR of no station", "value" to value, "format" to format)
            Snackbar.make(grid, "Unknown code: $value", Snackbar.LENGTH_LONG)
                .setAction("Add station") {
                    AppLog.i("main", "add station from an unknown code", "value" to value)
                    startActivity(Intent(this, EditStationActivity::class.java)
                        .putExtra(EditStationActivity.EXTRA_QR, value).putExtra(EditStationActivity.EXTRA_QR_FORMAT, format))
                }.show()
        }
    }

    private fun openStation(id: String, source: String) {
        scanner.enabled = false
        startActivity(Intent(this, StationActivity::class.java)
            .putExtra(StationActivity.EXTRA_ID, id).putExtra(StationActivity.EXTRA_SOURCE, source))
    }
}
