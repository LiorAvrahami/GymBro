package org.lior.gymbro

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import org.lior.gymbro.core.Station
import org.lior.gymbro.core.fmtWeight
import org.lior.gymbro.core.parseIncrement
import java.io.File

/**
 * Adds a station, or edits one ([EXTRA_ID]): its name, its pictures (square; the first is the station's picture, the
 * plus adds more), its QR code (the scanner on the right reads it and shows its value) and the weight increment of the
 * machine. A QR code may come from the launcher ([EXTRA_QR]: "Add station" on an unknown code).
 */
class EditStationActivity : AppCompatActivity() {
    /** A picture: one already kept ([name]) or one just taken, still in the cache ([tmp]). */
    private data class Img(val name: String?, val tmp: String?) {
        fun file(): File = if (name != null) Repo.imageFile(name) else File(tmp!!)
        fun encode() = if (name != null) "n:$name" else "t:$tmp"
        companion object {
            fun decode(s: String) = if (s.startsWith("n:")) Img(s.substring(2), null) else Img(null, s.substring(2))
        }
    }

    private var editing: Station? = null
    private val images = mutableListOf<Img>()
    private var qrValue: String? = null
    private var qrFormat: String? = null
    private var captureMode = "add"
    private var initialState = ""

    private lateinit var name: EditText
    private lateinit var increment: EditText
    private lateinit var photo: ImageView
    private lateinit var qrText: TextView
    private lateinit var scanner: QrScanner

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra(CaptureActivity.EXTRA_PATH)
        if (r.resultCode != RESULT_OK || path == null) {
            AppLog.i("edit", "photo cancelled", "mode" to captureMode)
            return@registerForActivityResult
        }
        val img = Img(null, path)
        if (captureMode == "replace" && images.isNotEmpty()) images[0] = img else images.add(img)
        AppLog.i("edit", "photo added", "mode" to captureMode, "count" to images.size, "bytes" to File(path).length())
        showImages()
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        AppLog.i("edit", "camera permission answered", "granted" to ok)
        updateCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_station)
        name = findViewById(R.id.edit_name)
        increment = findViewById(R.id.edit_increment)
        photo = findViewById(R.id.edit_photo_image)
        qrText = findViewById(R.id.edit_qr_text)

        editing = intent.getStringExtra(EXTRA_ID)?.let { Repo.data.station(it) }
        val e = editing
        if (savedInstanceState != null) {
            name.setText(savedInstanceState.getString("name"))
            increment.setText(savedInstanceState.getString("increment"))
            savedInstanceState.getStringArrayList("images")?.forEach { images.add(Img.decode(it)) }
            qrValue = savedInstanceState.getString("qr")
            qrFormat = savedInstanceState.getString("qrFormat")
            captureMode = savedInstanceState.getString("captureMode") ?: "add"
        } else if (e != null) {
            name.setText(e.name)
            increment.setText(fmtWeight(e.weightIncrement))
            e.images.forEach { images.add(Img(it, null)) }
            qrValue = e.qrValue
            qrFormat = e.qrFormat
        } else {
            qrValue = intent.getStringExtra(EXTRA_QR)
            qrFormat = intent.getStringExtra(EXTRA_QR_FORMAT)
        }
        AppLog.i("edit", if (e == null) "new station page" else "edit station page", "station" to e?.name, "id" to e?.id,
            "qrFromLauncher" to intent.getStringExtra(EXTRA_QR))
        initialState = if (savedInstanceState != null) savedInstanceState.getString("initial") ?: "" else state()

        findViewById<View>(R.id.edit_photo).setOnClickListener {
            captureMode = if (images.isEmpty()) "add" else "replace"
            AppLog.i("edit", "photo tapped", "mode" to captureMode)
            capture.launch(Intent(this, CaptureActivity::class.java))
        }
        findViewById<View>(R.id.edit_photo_add).setOnClickListener {
            captureMode = "add"
            AppLog.i("edit", "add another photo tapped", "count" to images.size)
            capture.launch(Intent(this, CaptureActivity::class.java))
        }
        findViewById<ImageButton>(R.id.edit_qr_clear).setOnClickListener {
            AppLog.i("edit", "QR removed", "was" to qrValue)
            qrValue = null
            qrFormat = null
            scanner.reset()
            showQr()
        }
        findViewById<View>(R.id.edit_camera_off).setOnClickListener { permission.launch(Manifest.permission.CAMERA) }
        findViewById<View>(R.id.edit_save).setOnClickListener { save() }
        findViewById<View>(R.id.edit_cancel).setOnClickListener { leave() }
        val delete = findViewById<ImageButton>(R.id.edit_delete)
        if (e != null) {
            delete.visibility = View.VISIBLE
            delete.setOnClickListener { confirmDelete(e) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        scanner = QrScanner(this, findViewById<PreviewView>(R.id.edit_preview), "new-station", repeatMs = 1500) { v, f -> onCode(v, f) }
        if (!scanner.hasPermission) permission.launch(Manifest.permission.CAMERA)
        showImages()
        showQr()
    }

    override fun onResume() {
        super.onResume()
        updateCamera()
    }

    private fun updateCamera() {
        val ok = scanner.hasPermission
        findViewById<View>(R.id.edit_camera_off).visibility = if (ok) View.GONE else View.VISIBLE
        if (ok) scanner.start()
    }

    private fun state() = listOf(name.text.toString(), increment.text.toString(), images.joinToString { it.encode() }, qrValue ?: "").joinToString("|")

    private fun onCode(value: String, format: String) {
        if (value == qrValue) return
        AppLog.i("edit", "QR scanned for the station", "value" to value, "format" to format, "was" to qrValue)
        qrValue = value
        qrFormat = format
        showQr()
    }

    private fun showQr() {
        val v = qrValue
        val clear = findViewById<ImageButton>(R.id.edit_qr_clear)
        if (v == null) {
            qrText.text = "Point at the station's QR code (if it has one)"
            clear.visibility = View.GONE
        } else {
            val owner = Repo.data.stationByQr(v)?.takeIf { it.id != editing?.id }
            qrText.text = "QR: $v" + (owner?.let { "\n(now on \"${it.name}\")" } ?: "")
            clear.visibility = View.VISIBLE
        }
    }

    private fun showImages() {
        val main = images.firstOrNull()
        val hint = findViewById<TextView>(R.id.edit_photo_hint)
        if (main == null) {
            photo.scaleType = ImageView.ScaleType.CENTER_INSIDE
            photo.setPadding(dp(40), dp(40), dp(40), dp(40))
            photo.setImageResource(R.drawable.ic_camera)
            hint.visibility = View.VISIBLE
        } else {
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            photo.setPadding(0, 0, 0, 0)
            ImageLoader.into(photo, main.file(), dp(200))
            hint.visibility = View.GONE
        }
        findViewById<View>(R.id.edit_photo_add).visibility = if (main == null) View.GONE else View.VISIBLE
        val strip = findViewById<LinearLayout>(R.id.edit_thumbs)
        strip.removeAllViews()
        findViewById<View>(R.id.edit_thumbs_label).visibility = if (images.size > 1) View.VISIBLE else View.GONE
        if (images.size <= 1) return
        images.forEachIndexed { i, img ->
            val frame = FrameLayout(this)
            val lp = LinearLayout.LayoutParams(dp(76), dp(76))
            lp.marginEnd = dp(8)
            frame.layoutParams = lp
            frame.setBackgroundResource(if (i == 0) R.drawable.bg_submit else R.drawable.bg_tile)
            frame.setPadding(dp(4), dp(4), dp(4), dp(4))
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            frame.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            ImageLoader.into(iv, img.file(), dp(76))
            frame.setOnClickListener {
                if (i == 0) return@setOnClickListener
                AppLog.i("edit", "picture made the main one", "index" to i)
                images.add(0, images.removeAt(i))
                showImages()
            }
            frame.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("Remove this picture?")
                    .setPositiveButton("Remove") { _, _ ->
                        AppLog.i("edit", "picture removed", "index" to i, "name" to img.name)
                        images.removeAt(i)
                        showImages()
                    }.setNegativeButton("Keep", null).show()
                true
            }
            strip.addView(frame)
        }
    }

    private fun save() {
        val inc = parseIncrement(increment.text.toString())
        if (inc == null) {
            increment.error = "A number above 0"
            AppLog.w("edit", "save refused: bad increment", "text" to increment.text.toString())
            return
        }
        val qr = qrValue
        val owner = if (qr == null) null else Repo.data.stationByQr(qr)?.takeIf { it.id != editing?.id }
        if (qr != null && owner != null) {
            AppLog.i("edit", "the QR is on another station: asking", "qr" to qr, "owner" to owner.name)
            AlertDialog.Builder(this)
                .setMessage("This QR code belongs to \"${owner.name}\". Move it to this station?")
                .setPositiveButton("Move it") { _, _ ->
                    AppLog.i("edit", "QR moved to this station", "from" to owner.name)
                    Repo.clearQr(qr, editing?.id)
                    commit(inc)
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        commit(inc)
    }

    private fun commit(inc: Double) {
        val now = System.currentTimeMillis()
        val names = images.map { img -> img.name ?: Repo.addImage(File(img.tmp!!)) }
        val typed = name.text.toString().trim()
        val e = editing
        if (e == null) {
            val n = typed.ifEmpty { "Station ${Repo.data.activeStations.size + 1}" }
            val s = Station(id = Repo.newId(), name = n, qrValue = qrValue, qrFormat = qrFormat, weightIncrement = inc,
                images = names, createdAt = now)
            Repo.addStation(s)
            Toast.makeText(this, "Station \"$n\" added", Toast.LENGTH_SHORT).show()
        } else {
            val s = e.copy(name = typed.ifEmpty { e.name }, qrValue = qrValue, qrFormat = qrFormat, weightIncrement = inc,
                images = names, updatedAt = now)
            Repo.updateStation(s)
            Toast.makeText(this, "Station saved", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun confirmDelete(s: Station) {
        AppLog.i("edit", "delete tapped", "station" to s.name)
        AlertDialog.Builder(this)
            .setTitle("Delete \"${s.name}\"?")
            .setMessage("The station leaves the launcher. Its sets stay in your history and statistics.")
            .setPositiveButton("Delete") { _, _ ->
                Repo.deleteStation(s.id)
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> AppLog.i("edit", "delete cancelled") }
            .show()
    }

    private fun leave() {
        if (state() == initialState) {
            AppLog.i("edit", "left without changes")
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Leave without saving?")
            .setPositiveButton("Leave") { _, _ -> AppLog.i("edit", "left without saving (changes dropped)"); finish() }
            .setNegativeButton("Stay", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("name", name.text.toString())
        outState.putString("increment", increment.text.toString())
        outState.putStringArrayList("images", ArrayList(images.map { it.encode() }))
        outState.putString("qr", qrValue)
        outState.putString("qrFormat", qrFormat)
        outState.putString("captureMode", captureMode)
        outState.putString("initial", initialState)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_QR = "qr"
        const val EXTRA_QR_FORMAT = "qrFormat"
    }
}
