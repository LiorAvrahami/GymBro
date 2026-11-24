package org.lior.gymbro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.lior.gymbro.core.DaySummary
import org.lior.gymbro.core.Station
import org.lior.gymbro.core.StationSummary
import org.lior.gymbro.core.Stats
import org.lior.gymbro.core.fmtWeight
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Statistics: the export of all the data (for diagnostics), today / this week / all time, sets per day over the last
 * 30 days, each station's progress (tap a station for its weight over time and its last sets), the history of sets
 * (long-press a set to delete it), and where the data is kept.
 */
class StatsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private val zone: ZoneId = ZoneId.systemDefault()
    private val expanded = mutableSetOf<String>()
    private var exportStatus: TextView? = null
    private var exporting = false

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        AppLog.i("stats", "storage permission answered", "granted" to ok)
        if (ok) export() else Toast.makeText(this, "GymBro needs this permission to write to Downloads", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        body = findViewById(R.id.stats_body)
        findViewById<ImageButton>(R.id.stats_back).setOnClickListener { AppLog.i("stats", "back tapped"); finish() }
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        body.removeAllViews()
        val data = Repo.data
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        AppLog.i("stats", "statistics shown", "stations" to data.activeStations.size, "sets" to data.uses.size)

        // ---- export
        val exportCard = card()
        val exportBtn = Button(this).apply {
            text = "Export all data"
            textSize = 18f
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_download, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            setOnClickListener { AppLog.i("stats", "export tapped"); exportClicked() }
        }
        exportCard.addView(exportBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
        exportStatus = small("One file in Downloads with all your data, every photo, every QR code and the app's log, for diagnostics.").also {
            exportCard.addView(it)
        }
        body.addView(exportCard)

        // ---- summary
        title("Overview")
        val weekStart = today.minusDays(6)
        val t = Stats.total(data.uses, zone, today, today)
        val w = Stats.total(data.uses, zone, weekStart, today)
        val days = Stats.daily(data.uses, zone)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(statCard("Today", "${t.sets} sets", "${t.reps} reps · ${Fmt.volume(t.volume)} kg"), weighted())
        row1.addView(statCard("Last 7 days", "${w.sets} sets", "${w.reps} reps · ${daysWith(days, weekStart, today)} days"), weighted(true))
        body.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        row2.addView(statCard("All time", "${data.uses.size} sets", "${data.uses.sumOf { it.reps }} reps · ${days.size} days"), weighted())
        row2.addView(statCard("Streak", "${Stats.streak(data.uses, now, zone)} days", "in a row, up to today"), weighted(true))
        body.addView(row2)

        // ---- sets per day
        title("Sets per day, last 30 days")
        val last = Stats.lastDays(data.uses, now, zone, 30)
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val chart = BarChartView(this)
        chart.set(last.map { it.sets.toFloat() }, last.map { it.date.format(fmt) })
        body.addView(card().apply { addView(chart) })

        // ---- stations
        title("Stations")
        val per = Stats.perStation(data.uses, zone)
        val stations = data.stations.filter { !it.deleted || per.containsKey(it.id) }
            .sortedByDescending { per[it.id]?.lastUse?.time ?: it.createdAt }
        if (stations.isEmpty()) body.addView(small("No stations yet."))
        for (s in stations) body.addView(stationRow(s, per[s.id]))

        // ---- history
        title("History")
        body.addView(small("Long-press a set to delete it."))
        val recent = data.uses.sortedByDescending { it.time }.take(150)
        if (recent.isEmpty()) body.addView(small("No sets yet. Pick a station on the main page, then Submit."))
        var lastDay: LocalDate? = null
        for (u in recent) {
            val d = Stats.dayOf(u.time, zone)
            if (d != lastDay) {
                lastDay = d
                body.addView(TextView(this).apply {
                    text = Fmt.date(u.time)
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(12), 0, dp(4))
                })
            }
            val name = data.station(u.stationId)?.name ?: u.stationName
            val row = TextView(this).apply {
                text = "${Fmt.time(u.time)}   $name   ${u.reps} × ${fmtWeight(u.weight)} kg   difficulty ${u.difficulty}"
                setTextColor(ContextCompat.getColor(context, R.color.text))
                textSize = 15f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundResource(R.drawable.bg_card)
                setOnLongClickListener {
                    AppLog.i("stats", "set long-pressed", "id" to u.id)
                    AlertDialog.Builder(this@StatsActivity)
                        .setMessage("Delete this set?\n\n$name: ${u.reps} × ${fmtWeight(u.weight)} kg at ${Fmt.dateTime(u.time)}")
                        .setPositiveButton("Delete") { _, _ -> Repo.deleteUse(u.id); build() }
                        .setNegativeButton("Keep", null).show()
                    true
                }
            }
            body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        if (data.uses.size > recent.size) body.addView(small("…and ${data.uses.size - recent.size} older sets (all of them are in the export)."))

        // ---- storage
        title("Your data")
        val storage = card()
        val status = small("Folder: ${Repo.folderDescription()}\nChecking…")
        storage.addView(status)
        storage.addView(Button(this).apply {
            text = "Change data folder"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_folder, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            setOnClickListener {
                AppLog.i("stats", "change folder tapped")
                startActivity(Intent(this@StatsActivity, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_CHANGE, true))
            }
        })
        body.addView(storage)
        Thread {
            val ok = Repo.folderReachable()
            val text = "Folder: ${Repo.folderDescription()} (${if (ok) "reachable" else "NOT reachable"})\n" +
                "Last save to the folder: ${if (Repo.lastFolderWriteAt > 0) Fmt.dateTime(Repo.lastFolderWriteAt) else "not this session"}" +
                (if (Repo.lastFolderWriteOk == false) " — FAILED: ${Repo.lastFolderError}" else "") + "\n" +
                "Data saved ${Fmt.dateTime(data.savedAt)} (save #${data.saveCount}), ${data.stations.sumOf { it.images.size }} photos\n" +
                "Daily backups are kept in the folder's backups/ for 60 days."
            runOnUiThread { status.text = text }
        }.start()
        body.addView(small("GymBro ${BuildConfig.VERSION_NAME}").apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
    }

    private fun stationRow(s: Station, p: StationSummary?): View {
        val c = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        top.addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)))
        ImageLoader.into(iv, s.mainImage?.let { Repo.imageFile(it) }, dp(56), R.drawable.ic_dumbbell)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        texts.addView(TextView(this).apply {
            text = s.name + if (s.deleted) " (deleted)" else ""
            setTextColor(ContextCompat.getColor(context, R.color.text))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        val line = if (p == null) "Not used yet" else {
            val l = p.lastUse!!
            "Best ${fmtWeight(p.bestWeight)} kg · ${p.sets} sets on ${p.daysUsed} days\nLast: ${l.reps} × ${fmtWeight(l.weight)} kg, ${Fmt.ago(l.time)}"
        }
        texts.addView(small(line))
        top.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(top)
        if (p != null && s.id in expanded) {
            c.addView(small("Heaviest weight of each day (kg)").apply { setPadding(0, dp(10), 0, 0) })
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            val chart = LineChartView(this)
            chart.set(p.maxWeightByDay.map { it.second.toFloat() }, p.maxWeightByDay.map { it.first.format(fmt) })
            c.addView(chart)
            val sets = Repo.data.uses.filter { it.stationId == s.id }.sortedByDescending { it.time }.take(10)
            c.addView(small("Last sets:\n" + sets.joinToString("\n") {
                "${Fmt.dateTime(it.time)}   ${it.reps} × ${fmtWeight(it.weight)} kg   difficulty ${it.difficulty}"
            }).apply { setPadding(0, dp(8), 0, 0) })
            c.addView(small("Total: ${p.totalReps} reps, ${Fmt.volume(p.totalVolume)} kg lifted · since ${Fmt.date(p.firstTime)}" +
                (s.qrValue?.let { "\nQR: $it" } ?: "")).apply { setPadding(0, dp(8), 0, 0) })
        }
        c.setOnClickListener {
            if (p == null) return@setOnClickListener
            if (!expanded.remove(s.id)) expanded.add(s.id)
            AppLog.i("stats", "station row tapped", "station" to s.name, "expanded" to (s.id in expanded))
            val scroll = findViewById<View>(R.id.stats_scroll)
            val y = scroll.scrollY
            build()
            scroll.post { scroll.scrollTo(0, y) }
        }
        return c
    }

    private fun daysWith(days: List<DaySummary>, from: LocalDate, to: LocalDate) =
        days.count { !it.date.isBefore(from) && !it.date.isAfter(to) }

    private fun exportClicked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        export()
    }

    private fun export() {
        if (exporting) return
        exporting = true
        exportStatus?.text = "Exporting…"
        Thread {
            try {
                val r = Exporter.export(this)
                runOnUiThread {
                    exporting = false
                    val mb = String.format(Locale.US, "%.1f", r.bytes / 1048576.0)
                    exportStatus?.text = "Saved ${r.where} ($mb MB)"
                    AlertDialog.Builder(this)
                        .setTitle("Exported")
                        .setMessage("${r.where}\n$mb MB: all your data, photos, QR codes and the app's log.")
                        .setPositiveButton("Share") { _, _ -> share(r) }
                        .setNegativeButton("Close", null)
                        .show()
                }
            } catch (e: Throwable) {
                AppLog.e("export", "the export failed", e)
                runOnUiThread {
                    exporting = false
                    exportStatus?.text = "The export failed: $e"
                }
            }
        }.start()
    }

    private fun share(r: Exporter.Result) {
        AppLog.i("stats", "share export tapped", "file" to r.fileName)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", r.shareFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, r.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share the GymBro export"))
    }

    // ---- small builders

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun title(s: String) = body.addView(TextView(this).apply {
        text = s
        setTextAppearance(R.style.SectionTitle)
        setPadding(0, dp(20), 0, dp(2))
    })

    private fun small(s: String) = TextView(this).apply {
        text = s
        setTextColor(ContextCompat.getColor(context, R.color.text_dim))
        textSize = 13f
        setLineSpacing(dpf(2f), 1f)
    }

    private fun statCard(label: String, big: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(small(label))
        addView(TextView(context).apply {
            text = big
            setTextColor(ContextCompat.getColor(context, R.color.text))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(small(sub))
    }

    private fun weighted(second: Boolean = false) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (second) marginStart = dp(10) }
}
