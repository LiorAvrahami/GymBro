package org.lior.gymbro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = context.dpf(v)

/** Photos decoded off the main thread, at the size they are shown, and kept in a memory cache. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun decode(file: File, reqPx: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqPx && bounds.outHeight / (sample * 2) >= reqPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun key(file: File, reqPx: Int) = "${file.path}@${(reqPx / 64 + 1) * 64}"

    fun cached(file: File, reqPx: Int): Bitmap? = cache.get(key(file, reqPx))

    fun load(file: File, reqPx: Int, done: (Bitmap?) -> Unit) {
        val k = key(file, reqPx)
        cache.get(k)?.let { done(it); return }
        exec.execute {
            val bmp = try { decode(file, (reqPx / 64 + 1) * 64) } catch (e: Throwable) {
                AppLog.e("image", "could not decode a photo", e, "file" to file.name); null
            }
            if (bmp != null) cache.put(k, bmp)
            main.post { done(bmp) }
        }
    }

    fun into(iv: ImageView, file: File?, reqPx: Int, placeholder: Int = 0) {
        if (file == null) {
            iv.tag = null
            if (placeholder != 0) iv.setImageResource(placeholder) else iv.setImageDrawable(null)
            return
        }
        val k = key(file, reqPx)
        iv.tag = k
        cache.get(k)?.let { iv.setImageBitmap(it); return }
        if (placeholder != 0) iv.setImageResource(placeholder) else iv.setImageDrawable(null)
        load(file, reqPx) { bmp -> if (iv.tag == k && bmp != null) iv.setImageBitmap(bmp) }
    }
}

object Fmt {
    // java.time's formatters can be shared between threads (the export runs on its own)
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val date = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.getDefault())

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    fun time(ms: Long): String = time.format(at(ms))
    fun date(ms: Long): String = date.format(at(ms))
    fun dateTime(ms: Long): String = if (ms <= 0) "never" else dateTime.format(at(ms))

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - ms) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            s < 2 * 86400 -> "yesterday"
            s < 30 * 86400 -> "${s / 86400} days ago"
            else -> date(ms)
        }
    }

    fun volume(v: Double): String = if (v >= 10000) "${(v / 1000).roundToInt()}k" else v.roundToInt().toString()
}
