package org.lior.gymbro

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The app's diagnostics log: every screen, tap, scan, save and error, one JSON object per line, in the app's files
 * (gymbro_log.jsonl) and copied to the user's data folder. The statistics page's export puts all of it in the export
 * file, so that the user can hand it over for diagnostics (user: "make sure to log a lot").
 *
 * Each line: t (epoch ms), ts (local time), s (this process's session id), n (sequence number in the session),
 * lvl (I/W/E), tag, msg, d (details, an object), err (a stack trace).
 */
object AppLog {
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val KEEP_BYTES = 3L * 1024 * 1024

    private var file: File? = null
    private val lock = Any()
    val session: String = UUID.randomUUID().toString().substring(0, 8)
    private var seq = 0L
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(dir: File) {
        file = File(dir, "gymbro_log.jsonl")
    }

    val logFile: File? get() = file

    fun i(tag: String, msg: String, vararg d: Pair<String, Any?>) = write("I", tag, msg, d, null)
    fun w(tag: String, msg: String, vararg d: Pair<String, Any?>) = write("W", tag, msg, d, null)
    fun e(tag: String, msg: String, err: Throwable? = null, vararg d: Pair<String, Any?>) = write("E", tag, msg, d, err)

    private fun write(level: String, tag: String, msg: String, d: Array<out Pair<String, Any?>>, err: Throwable?) {
        val now = System.currentTimeMillis()
        val line = try {
            val o = JSONObject()
            synchronized(lock) {
                o.put("t", now)
                o.put("ts", tsFormat.format(Date(now)))
                o.put("s", session)
                o.put("n", seq++)
            }
            o.put("lvl", level)
            o.put("tag", tag)
            o.put("msg", msg)
            if (d.isNotEmpty()) {
                val details = JSONObject()
                for ((k, v) in d) details.put(k, jsonValue(v))
                o.put("d", details)
            }
            if (err != null) o.put("err", stackTrace(err))
            o.toString()
        } catch (t: Throwable) {
            "{\"t\":$now,\"lvl\":\"E\",\"tag\":\"log\",\"msg\":${JSONObject.quote("could not log: $msg: $t")}}"
        }
        when (level) {
            "E" -> Log.e("GymBro/$tag", msg, err)
            "W" -> Log.w("GymBro/$tag", msg)
            else -> Log.i("GymBro/$tag", msg)
        }
        val f = file ?: return
        synchronized(lock) {
            try {
                f.appendText(line + "\n")
                if (f.length() > MAX_BYTES) trim(f)
            } catch (t: Throwable) {
                Log.e("GymBro/log", "could not write the log", t)
            }
        }
    }

    /** Keeps the newest [KEEP_BYTES] of the log, cut at a line's start. */
    private fun trim(f: File) {
        val bytes = f.readBytes()
        var from = (bytes.size - KEEP_BYTES).toInt().coerceAtLeast(0)
        while (from < bytes.size && bytes[from] != '\n'.code.toByte()) from++
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes.copyOfRange((from + 1).coerceAtMost(bytes.size), bytes.size))
        tmp.renameTo(f)
        f.appendText(JSONObject().put("t", System.currentTimeMillis()).put("lvl", "I").put("tag", "log")
            .put("msg", "log trimmed to its newest ${KEEP_BYTES / 1024} KB").toString() + "\n")
    }

    fun readAllLines(): List<String> = synchronized(lock) {
        val f = file ?: return emptyList()
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }
    }

    fun snapshotBytes(): ByteArray = synchronized(lock) {
        val f = file
        if (f == null || !f.exists()) ByteArray(0) else f.readBytes()
    }

    private fun jsonValue(v: Any?): Any {
        if (v == null) return JSONObject.NULL
        return when (v) {
            is Number, is Boolean, is String, is JSONObject, is org.json.JSONArray -> v
            is Collection<*> -> org.json.JSONArray().also { a -> for (x in v) a.put(jsonValue(x)) }
            is Map<*, *> -> JSONObject().also { o -> for (e in v.entries) o.put(e.key.toString(), jsonValue(e.value)) }
            else -> v.toString()
        }
    }

    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
}
