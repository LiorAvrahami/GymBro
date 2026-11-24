package org.lior.gymbro

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import org.lior.gymbro.core.Limits
import org.lior.gymbro.core.Station
import org.lior.gymbro.core.StationUse
import org.lior.gymbro.core.Stats
import org.lior.gymbro.core.fmtWeight
import org.lior.gymbro.core.stepWeight
import java.time.ZoneId

/**
 * One station, opened from the launcher (a tap or its QR code): the reps (6 to 15), the difficulty and the weight of the
 * set just done; Submit registers the set ("StationUse") and goes back to the launcher. Everything starts at what was
 * done last time on this station.
 */
class StationActivity : AppCompatActivity() {
    private lateinit var station: Station
    private lateinit var reps: Slider
    private lateinit var difficulty: Slider
    private lateinit var weightText: TextView
    private var weight = 0.0
    private var openedAt = 0L
    private var source = ""
    private var submitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val s = id?.let { Repo.data.station(it) }
        if (s == null || s.deleted) {
            AppLog.w("station", "opened for a station that is not there", "id" to id)
            finish()
            return
        }
        station = s
        source = intent.getStringExtra(EXTRA_SOURCE) ?: ""
        openedAt = savedInstanceState?.getLong("openedAt") ?: System.currentTimeMillis()
        setContentView(R.layout.activity_station)
        reps = findViewById(R.id.station_reps)
        difficulty = findViewById(R.id.station_difficulty)
        weightText = findViewById(R.id.station_weight)

        val last = Stats.lastUse(Repo.data.uses, s.id)
        reps.value = (savedInstanceState?.getInt("reps") ?: last?.reps ?: Limits.REPS_DEFAULT).coerceIn(Limits.REPS_MIN, Limits.REPS_MAX).toFloat()
        difficulty.value = (savedInstanceState?.getInt("difficulty") ?: last?.difficulty ?: Limits.DIFFICULTY_DEFAULT)
            .coerceIn(Limits.DIFFICULTY_MIN, Limits.DIFFICULTY_MAX).toFloat()
        weight = savedInstanceState?.getDouble("weight") ?: last?.weight ?: 0.0
        AppLog.i("station", "opened", "station" to s.name, "id" to s.id, "source" to source, "qr" to s.qrValue,
            "increment" to s.weightIncrement, "lastUse" to last?.let { "${it.reps}x${fmtWeight(it.weight)} d${it.difficulty} at ${it.time}" })

        val repsValue = findViewById<TextView>(R.id.station_reps_value)
        val diffValue = findViewById<TextView>(R.id.station_difficulty_value)
        repsValue.text = reps.value.toInt().toString()
        diffValue.text = difficulty.value.toInt().toString()
        reps.addOnChangeListener { _, v, _ -> repsValue.text = v.toInt().toString() }
        difficulty.addOnChangeListener { _, v, _ -> diffValue.text = v.toInt().toString() }
        reps.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = AppLog.i("station", "reps set", "reps" to slider.value.toInt())
        })
        difficulty.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = AppLog.i("station", "difficulty set", "difficulty" to slider.value.toInt())
        })

        findViewById<ImageButton>(R.id.station_weight_up).setOnClickListener { changeWeight(1) }
        findViewById<ImageButton>(R.id.station_weight_down).setOnClickListener { changeWeight(-1) }
        weightText.setOnClickListener { typeWeight() }
        findViewById<ImageButton>(R.id.station_back).setOnClickListener {
            AppLog.i("station", "back tapped (nothing saved)", "station" to station.name)
            finish()
        }
        findViewById<ImageView>(R.id.station_image).setOnClickListener {
            AppLog.i("station", "picture tapped: gallery", "images" to station.images.size)
            if (station.images.isEmpty()) Toast.makeText(this, "No pictures yet: add them with the pencil", Toast.LENGTH_SHORT).show()
            else startActivity(Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_ID, station.id))
        }
        findViewById<ImageButton>(R.id.station_edit).setOnClickListener {
            AppLog.i("station", "edit tapped", "station" to station.name)
            startActivity(Intent(this, EditStationActivity::class.java).putExtra(EditStationActivity.EXTRA_ID, station.id))
        }
        findViewById<android.view.View>(R.id.station_submit).setOnClickListener { submit() }
        show()
    }

    override fun onResume() {
        super.onResume()
        // back from editing: the station may have a new name, picture or increment, or be gone
        val s = Repo.data.station(station.id)
        if (s == null || s.deleted) { finish(); return }
        if (s != station) { station = s; show() }
    }

    private fun show() {
        findViewById<TextView>(R.id.station_name).text = station.name
        val iv = findViewById<ImageView>(R.id.station_image)
        ImageLoader.into(iv, station.mainImage?.let { Repo.imageFile(it) }, dp(110), R.drawable.ic_dumbbell)
        val data = Repo.data
        val zone = ZoneId.systemDefault()
        val last = Stats.lastUse(data.uses, station.id)
        val today = data.uses.filter { it.stationId == station.id && Stats.dayOf(it.time, zone) == Stats.dayOf(System.currentTimeMillis(), zone) }
        val info = StringBuilder()
        if (today.isNotEmpty()) info.append("Today: ${today.size} sets, ${today.sumOf { it.reps }} reps\n")
        if (last != null) info.append("Last: ${last.reps} × ${fmtWeight(last.weight)} kg, difficulty ${last.difficulty}, ${Fmt.ago(last.time)}")
        else info.append("First time on this station")
        findViewById<TextView>(R.id.station_info).text = info
        findViewById<TextView>(R.id.station_increment).text = "steps of ${fmtWeight(station.weightIncrement)} kg · tap the number to type it"
        weightText.text = fmtWeight(weight)
    }

    private fun changeWeight(steps: Int) {
        weight = stepWeight(weight, station.weightIncrement, steps)
        weightText.text = fmtWeight(weight)
        AppLog.i("station", if (steps > 0) "weight up" else "weight down", "weight" to weight)
    }

    private fun typeWeight() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(fmtWeight(weight))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Weight used (kg)")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (v != null && v >= 0 && !v.isInfinite()) {
                    weight = v
                    weightText.text = fmtWeight(weight)
                    AppLog.i("station", "weight typed", "weight" to weight)
                } else AppLog.w("station", "weight typed but not a number", "text" to input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submit() {
        if (submitted) return
        submitted = true
        val now = System.currentTimeMillis()
        val u = StationUse(
            id = Repo.newId(),
            stationId = station.id,
            time = now,
            reps = reps.value.toInt(),
            weight = weight,
            difficulty = difficulty.value.toInt(),
            stationName = station.name,
            source = source,
            openedAt = openedAt,
        )
        AppLog.i("station", "submit tapped", "secondsOnPage" to (now - openedAt) / 1000)
        Repo.addUse(u)
        Toast.makeText(this, "${station.name}: ${u.reps} × ${fmtWeight(u.weight)} kg saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::reps.isInitialized) return
        outState.putLong("openedAt", openedAt)
        outState.putInt("reps", reps.value.toInt())
        outState.putInt("difficulty", difficulty.value.toInt())
        outState.putDouble("weight", weight)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_SOURCE = "source"
    }
}
