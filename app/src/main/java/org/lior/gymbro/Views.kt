package org.lior.gymbro

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import org.lior.gymbro.core.GridFit
import kotlin.math.max
import kotlin.math.min

/** A frame as tall as it is wide. */
class SquareFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}

/**
 * A station's button on the launcher: the station's photo ("for now each workstation is the picture I take of it"), its
 * name small at the bottom, and the reps done on it today in a round badge at the bottom right. Drawn by hand so that
 * everything scales with the tile, however many stations share the screen.
 */
class StationTile(context: Context) : View(context) {
    var name: String = ""
        set(v) { field = v; invalidate() }
    var repsToday: Int = 0
        set(v) { field = v; invalidate() }
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    private val tileColor = ContextCompat.getColor(context, R.color.tile)
    private val borderColor = ContextCompat.getColor(context, R.color.tile_border)
    private val badgeColor = ContextCompat.getColor(context, R.color.badge)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val src = Rect()

    init {
        isClickable = true
        isFocusable = true
        foreground = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, ColorDrawable(Color.WHITE))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val r = dpf(6f)
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        fill.color = tileColor
        fill.shader = null
        canvas.drawRect(rect, fill)
        val b = bitmap
        val small = min(w, h)
        if (b != null) {
            // centre crop
            val scale = max(w / b.width, h / b.height)
            val sw = (w / scale).toInt()
            val sh = (h / scale).toInt()
            src.set((b.width - sw) / 2, (b.height - sh) / 2, (b.width + sw) / 2, (b.height + sh) / 2)
            canvas.drawBitmap(b, src, rect, bmpPaint)
        }
        // the name: big in the middle without a photo, small on a dark strip with one
        if (b == null) {
            text.textSize = (small * 0.16f).coerceIn(dpf(10f), dpf(26f))
            text.textAlign = Paint.Align.CENTER
            drawWrapped(canvas, name, w / 2, h / 2, w * 0.86f)
        } else if (name.isNotEmpty()) {
            text.textSize = (small * 0.11f).coerceIn(dpf(9f), dpf(16f))
            val strip = text.textSize * 1.9f
            fill.shader = LinearGradient(0f, h - strip * 1.4f, 0f, h, 0x00000000, 0xC0000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h - strip * 1.4f, w, h, fill)
            fill.shader = null
            text.textAlign = Paint.Align.LEFT
            val badgeSpace = badgeSize(small) + dpf(8f)
            val shown = TextUtils.ellipsize(name, text, w - badgeSpace - dpf(8f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, dpf(6f), h - strip * 0.45f, text)
        }
        canvas.restore()
        // the border
        stroke.color = borderColor
        stroke.strokeWidth = dpf(3f)
        rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, stroke)
        // today's reps
        val d = badgeSize(small)
        val cx = w - d / 2 - dpf(5f)
        val cy = h - d / 2 - dpf(5f)
        fill.color = badgeColor
        fill.alpha = if (repsToday > 0) 255 else 170
        canvas.drawCircle(cx, cy, d / 2, fill)
        fill.alpha = 255
        stroke.color = if (repsToday > 0) Color.WHITE else 0x80FFFFFF.toInt()
        stroke.strokeWidth = dpf(1.5f)
        canvas.drawCircle(cx, cy, d / 2 - stroke.strokeWidth / 2, stroke)
        val label = repsToday.toString()
        text.textAlign = Paint.Align.CENTER
        text.textSize = d * (if (label.length >= 3) 0.36f else 0.46f)
        text.alpha = if (repsToday > 0) 255 else 150
        canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
        text.alpha = 255
    }

    private fun badgeSize(small: Float) = (small * 0.3f).coerceIn(dpf(22f), dpf(52f))

    private fun drawWrapped(canvas: Canvas, s: String, cx: Float, cy: Float, maxW: Float) {
        val words = s.split(" ")
        val lines = mutableListOf<String>()
        var cur = ""
        for (wd in words) {
            val t = if (cur.isEmpty()) wd else "$cur $wd"
            if (text.measureText(t) <= maxW || cur.isEmpty()) cur = t else { lines.add(cur); cur = wd }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        val shown = lines.take(3).map { TextUtils.ellipsize(it, text, maxW, TextUtils.TruncateAt.END).toString() }
        val lh = text.textSize * 1.2f
        var y = cy - lh * (shown.size - 1) / 2 - (text.descent() + text.ascent()) / 2
        for (l in shown) { canvas.drawText(l, cx, y, text); y += lh }
    }
}

/** Lays out its children as a grid that shows all of them at once, as big as they can be (see [GridFit]). */
class StationGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val gap = dpf(10f)
    var lastCols = 0
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val g = GridFit.fit(childCount, w.toFloat(), h.toFloat(), gap)
        lastCols = g.cols
        val cw = MeasureSpec.makeMeasureSpec(g.cellW.toInt(), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(g.cellH.toInt(), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount == 0) return
        val g = GridFit.fit(childCount, (r - l).toFloat(), (b - t).toFloat(), gap)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = (i % g.cols) * (g.cellW + gap)
            val y = (i / g.cols) * (g.cellH + gap)
            c.layout(x.toInt(), y.toInt(), (x + g.cellW).toInt(), (y + g.cellH).toInt())
        }
    }
}

/** Bars, one per value, with a label under the first, middle and last ones (the statistics page). */
class BarChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var values: List<Float> = emptyList()
    private var labels: List<String> = emptyList()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; strokeWidth = dpf(1f) }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.text_dim); textSize = dpf(11f) }

    fun set(values: List<Float>, labels: List<String>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(140))
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val top = dpf(16f)
        val bottom = height - dpf(20f)
        val w = width.toFloat()
        val maxV = max(1f, values.max())
        val slot = w / values.size
        val bw = slot * 0.7f
        for ((i, v) in values.withIndex()) {
            val x = i * slot + (slot - bw) / 2
            val bh = (bottom - top) * v / maxV
            if (v > 0) canvas.drawRoundRect(x, bottom - max(bh, dpf(2f)), x + bw, bottom, dpf(2f), dpf(2f), bar)
        }
        canvas.drawLine(0f, bottom, w, bottom, axis)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("max ${maxV.toInt()}", 0f, top - dpf(4f), text)
        val idx = listOf(0, values.size / 2, values.size - 1).distinct()
        for (i in idx) {
            val l = labels.getOrNull(i) ?: continue
            text.textAlign = when (i) { 0 -> Paint.Align.LEFT; values.size - 1 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val x = when (i) { 0 -> 0f; values.size - 1 -> w; else -> i * slot + slot / 2 }
            canvas.drawText(l, x, height - dpf(4f), text)
        }
    }
}

/** A line through points (the heaviest weight of each day on a station), with dots and the first and last dates. */
class LineChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var values: List<Float> = emptyList()
    private var labels: List<String> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent); style = Paint.Style.STROKE; strokeWidth = dpf(2.5f)
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.text_dim); textSize = dpf(11f) }
    private val path = Path()

    fun set(values: List<Float>, labels: List<String>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(130))
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val left = dpf(36f)
        val right = width - dpf(10f)
        val top = dpf(10f)
        val bottom = height - dpf(20f)
        val lo = values.min()
        val hi = values.max()
        val span = if (hi - lo < 1e-3f) 1f else hi - lo
        fun x(i: Int) = if (values.size == 1) (left + right) / 2 else left + (right - left) * i / (values.size - 1)
        fun y(v: Float) = if (hi - lo < 1e-3f) (top + bottom) / 2 else bottom - (bottom - top) * (v - lo) / span
        path.reset()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        canvas.drawPath(path, line)
        values.forEachIndexed { i, v -> canvas.drawCircle(x(i), y(v), dpf(3f), dot) }
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(org.lior.gymbro.core.fmtWeight(hi.toDouble()), left - dpf(6f), y(hi) + dpf(4f), text)
        if (hi - lo >= 1e-3f) canvas.drawText(org.lior.gymbro.core.fmtWeight(lo.toDouble()), left - dpf(6f), y(lo) + dpf(4f), text)
        text.textAlign = Paint.Align.LEFT
        labels.firstOrNull()?.let { canvas.drawText(it, left, height - dpf(4f), text) }
        if (labels.size > 1) {
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(labels.last(), right, height - dpf(4f), text)
        }
    }
}
