package org.lior.gymbro

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.lior.gymbro.core.JsonCodec
import org.lior.gymbro.core.Stats
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The statistics page's export: everything in one file in Downloads (user: "exports all the user data to a single file
 * in the downloads folder ... include all the images and QR values"), for diagnostics. One JSON object:
 *
 *  format, formatVersion, exportedAt, exportedAtLocal
 *  app        version, package, install and update times
 *  device     maker, model, Android version, screen, locale, time zone, memory, free space, permissions
 *  storage    the data folder, whether it can be reached, the last write to it, how the data was loaded, every file of
 *             the app's copy and of the folder with its size and time
 *  summary    counts and totals, per station and per day
 *  qrCodes    every station's QR value and format (deleted stations too)
 *  data       the whole data file, exactly as saved (stations, sets)
 *  images     every photo: its file, the station it belongs to, its place, size, pixels and the JPEG in base64
 *  log        every line of the diagnostics log, oldest first (see [AppLog])
 *
 * Written as a stream, so that the photos never have to be in memory all at once.
 */
object Exporter {
    class Result(val fileName: String, val bytes: Long, val shareFile: File, val where: String)

    fun export(context: Context): Result {
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i("export", "export started")
        Repo.flush()
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date(now))
        val fileName = "GymBro_export_$stamp.json"
        val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName)
        val data = Repo.data
        val zone = ZoneId.systemDefault()
        var imageCount = 0
        var logLines = 0

        BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8), 1 shl 16).use { w ->
            fun field(name: String, json: String, last: Boolean = false) {
                w.write(JSONObject.quote(name)); w.write(":"); w.write(json); if (!last) w.write(",\n")
            }
            w.write("{\n")
            field("format", JSONObject.quote("gymbro-export"))
            field("formatVersion", "1")
            field("exportedAt", now.toString())
            field("exportedAtLocal", JSONObject.quote(Fmt.dateTime(now)))
            field("app", appInfo(context).toString(1))
            field("device", deviceInfo(context).toString(1))
            field("storage", storageInfo().toString(1))
            field("summary", summary(zone, now).toString(1))
            field("qrCodes", JSONArray().apply {
                data.stations.forEach { s ->
                    put(JSONObject().put("stationId", s.id).put("station", s.name).put("deleted", s.deleted)
                        .put("qrValue", s.qrValue ?: JSONObject.NULL).put("qrFormat", s.qrFormat ?: JSONObject.NULL))
                }
            }.toString(1))
            field("data", JsonCodec.toJson(data).toString(1))

            // the photos, one at a time
            w.write("\"images\":[\n")
            val owners = HashMap<String, Pair<String, Int>>()
            data.stations.forEach { s -> s.images.forEachIndexed { i, n -> owners[n] = s.id to i } }
            val files = (Repo.imagesDir.listFiles()?.toList() ?: emptyList()).sortedBy { it.name }
            val missing = owners.keys.filter { !Repo.imageFile(it).exists() }
            var first = true
            for (f in files) {
                if (!first) w.write(",\n")
                first = false
                val bytes = f.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val owner = owners[f.name]
                val meta = JSONObject()
                    .put("file", f.name)
                    .put("stationId", owner?.first ?: JSONObject.NULL)
                    .put("station", owner?.first?.let { data.station(it)?.name } ?: JSONObject.NULL)
                    .put("index", owner?.second ?: JSONObject.NULL)
                    .put("used", owner != null)
                    .put("bytes", bytes.size)
                    .put("width", bounds.outWidth)
                    .put("height", bounds.outHeight)
                    .put("modified", f.lastModified())
                    .put("mime", "image/jpeg")
                val m = meta.toString()
                w.write(m.substring(0, m.length - 1))
                w.write(",\"base64\":\"")
                w.write(Base64.encodeToString(bytes, Base64.NO_WRAP))
                w.write("\"}")
                imageCount++
            }
            for (n in missing) {
                if (!first) w.write(",\n")
                first = false
                w.write(JSONObject().put("file", n).put("stationId", owners[n]?.first).put("missing", true).toString())
            }
            w.write("\n],\n")

            // the log, oldest first; each line is already a JSON object
            AppLog.i("export", "writing the export", "file" to fileName, "images" to imageCount, "missingImages" to missing.size)
            w.write("\"log\":[\n")
            val lines = AppLog.readAllLines()
            lines.forEachIndexed { i, line ->
                val ok = try { JSONObject(line); true } catch (_: Exception) { false }
                w.write(if (ok) line else JSONObject().put("unparsedLine", line).toString())
                if (i < lines.size - 1) w.write(",\n")
            }
            logLines = lines.size
            w.write("\n]\n}\n")
        }

        val where = writeToDownloads(context, file, fileName)
        AppLog.i("export", "export done", "file" to fileName, "bytes" to file.length(), "images" to imageCount,
            "logLines" to logLines, "where" to where, "ms" to SystemClock.elapsedRealtime() - t0)
        return Result(fileName, file.length(), file, where)
    }

    private fun writeToDownloads(context: Context, file: File, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Downloads refused the file")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Downloads/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            return dest.path
        }
    }

    private fun appInfo(context: Context): JSONObject {
        val pi = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        return JSONObject()
            .put("package", context.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("buildType", BuildConfig.BUILD_TYPE)
            .put("firstInstall", pi?.firstInstallTime ?: JSONObject.NULL)
            .put("firstInstallLocal", pi?.firstInstallTime?.let { Fmt.dateTime(it) } ?: JSONObject.NULL)
            .put("lastUpdate", pi?.lastUpdateTime ?: JSONObject.NULL)
            .put("lastUpdateLocal", pi?.lastUpdateTime?.let { Fmt.dateTime(it) } ?: JSONObject.NULL)
            .put("logSession", AppLog.session)
    }

    private fun deviceInfo(context: Context): JSONObject {
        val o = JSONObject()
        ((context.applicationContext as? GymApp)?.deviceInfo() ?: emptyMap()).forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        o.put("memTotal", mi.totalMem).put("memAvail", mi.availMem).put("lowMemory", mi.lowMemory)
        o.put("appHeapMax", Runtime.getRuntime().maxMemory())
        try {
            val st = StatFs(context.filesDir.path)
            o.put("internalFree", st.availableBytes).put("internalTotal", st.totalBytes)
        } catch (_: Exception) {}
        o.put("cameraPermission", ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        o.put("brand", Build.BRAND).put("product", Build.PRODUCT).put("hardware", Build.HARDWARE).put("fingerprint", Build.FINGERPRINT)
        o.put("persistedFolderPermissions", JSONArray().apply {
            context.contentResolver.persistedUriPermissions.forEach {
                put(JSONObject().put("uri", it.uri.toString()).put("read", it.isReadPermission).put("write", it.isWritePermission).put("time", it.persistedTime))
            }
        })
        return o
    }

    private fun storageInfo(): JSONObject {
        val (internal, external) = Repo.fileListing()
        return JSONObject()
            .put("folder", Repo.folderDescription())
            .put("folderTreeUri", Repo.treeUri?.toString() ?: JSONObject.NULL)
            .put("folderReachable", Repo.folderReachable())
            .put("lastFolderWriteOk", Repo.lastFolderWriteOk ?: JSONObject.NULL)
            .put("lastFolderWriteAt", Repo.lastFolderWriteAt)
            .put("lastFolderError", Repo.lastFolderError ?: JSONObject.NULL)
            .put("loadReport", Repo.loadReport)
            .put("appFiles", JSONArray(internal))
            .put("folderFiles", JSONArray(external))
            .put("fileColumns", "path, bytes, modified (epoch ms)")
    }

    private fun summary(zone: ZoneId, now: Long): JSONObject {
        val d = Repo.data
        val per = Stats.perStation(d.uses, zone)
        val o = JSONObject()
            .put("stations", d.activeStations.size)
            .put("deletedStations", d.stations.count { it.deleted })
            .put("stationsWithQr", d.activeStations.count { it.hasQr })
            .put("sets", d.uses.size)
            .put("reps", d.uses.sumOf { it.reps })
            .put("volumeKg", d.uses.sumOf { it.reps * it.weight })
            .put("trainingDays", Stats.daily(d.uses, zone).size)
            .put("streakDays", Stats.streak(d.uses, now, zone))
            .put("dataCreatedAt", d.createdAt)
            .put("savedAt", d.savedAt)
            .put("saveCount", d.saveCount)
            .put("today", LocalDate.now(zone).toString())
        o.put("perStation", JSONArray().apply {
            d.stations.forEach { s ->
                val p = per[s.id]
                put(JSONObject().put("id", s.id).put("name", s.name).put("deleted", s.deleted).put("sets", p?.sets ?: 0)
                    .put("reps", p?.totalReps ?: 0).put("volumeKg", p?.totalVolume ?: 0.0).put("bestWeight", p?.bestWeight ?: 0.0)
                    .put("daysUsed", p?.daysUsed ?: 0).put("lastTime", p?.lastUse?.time ?: JSONObject.NULL))
            }
        })
        o.put("perDay", JSONArray().apply {
            Stats.daily(d.uses, zone).forEach { s ->
                put(JSONObject().put("date", s.date.toString()).put("sets", s.sets).put("reps", s.reps).put("volumeKg", s.volume).put("stations", s.stations))
            }
        })
        return o
    }
}
