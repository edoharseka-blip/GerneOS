package com.e90os.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Round analog gauge in the COCKPIT style: amber ticks on a 240° arc, glowing needle,
 * big digital readout in the centre-bottom. [value] = null means "no data":
 * the needle rests at the minimum and the readout shows a dash.
 */
class GaugeView(
    context: Context,
    private val maxValue: Float,
    private val majorStep: Float,
    private val minorPerMajor: Int,
    private val labelDivider: Float,
    private val unit: String,
    private val redFrom: Float? = null,
) : View(context) {

    var value: Float? = null
        set(v) { field = v; invalidate() }
    var readout: String = "—"
        set(v) { field = v; invalidate() }
    var caption: String = ""
        set(v) { field = v; invalidate() }

    private val amber = Color.parseColor("#F3A43D")
    private val amberDim = Color.parseColor("#7A5226")
    private val red = Color.parseColor("#E0482E")
    private val textCol = Color.parseColor("#F7EEE4")
    private val muted = Color.parseColor("#B6A99B")
    private val d = resources.displayMetrics.density

    private val startAngle = 150f
    private val sweep = 240f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // needed for glow shadows
    }

    private fun angleFor(v: Float) = startAngle + sweep * (v / maxValue).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - 6 * d

        // Face: dark disc with soft amber glow at the rim.
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, intArrayOf(Color.parseColor("#17110C"), Color.parseColor("#0B0807"), Color.parseColor("#2A1A0C")),
            floatArrayOf(0f, 0.82f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null

        // Outer ring.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = amberDim
        p.setShadowLayer(8 * d, 0f, 0f, amber)
        canvas.drawCircle(cx, cy, r, p)
        p.clearShadowLayer()

        // Red zone.
        if (redFrom != null) {
            val rr = r - 10 * d
            arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
            p.strokeWidth = 6 * d
            p.color = red
            canvas.drawArc(arc, angleFor(redFrom), angleFor(maxValue) - angleFor(redFrom), false, p)
        }

        // Ticks and labels.
        val minorStep = majorStep / minorPerMajor
        var v = 0f
        p.textAlign = Paint.Align.CENTER
        while (v <= maxValue + 0.001f) {
            val major = (Math.round(v / minorStep) % minorPerMajor) == 0
            val a = Math.toRadians(angleFor(v).toDouble())
            val outer = r - 6 * d
            val inner = outer - (if (major) 14 else 7) * d
            p.style = Paint.Style.STROKE
            p.strokeWidth = (if (major) 2.5f else 1.2f) * d
            p.color = if (redFrom != null && v >= redFrom) red else if (major) amber else amberDim
            canvas.drawLine(cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner,
                cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer, p)
            if (major) {
                val lr = inner - 14 * d
                p.style = Paint.Style.FILL
                p.textSize = size * 0.065f
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = if (redFrom != null && v >= redFrom) red else textCol
                val txt = (v / labelDivider).toInt().toString()
                canvas.drawText(txt, cx + cos(a).toFloat() * lr, cy + sin(a).toFloat() * lr + p.textSize / 3, p)
            }
            v += minorStep
        }

        // Unit under centre.
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT
        p.textSize = size * 0.055f
        p.color = muted
        canvas.drawText(unit, cx, cy - r * 0.28f, p)

        // Needle.
        val na = Math.toRadians(angleFor(value ?: 0f).toDouble())
        val len = r - 22 * d
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 3.5f * d
        p.color = if (value == null) amberDim else amber
        if (value != null) p.setShadowLayer(10 * d, 0f, 0f, amber)
        canvas.drawLine(cx - cos(na).toFloat() * 14 * d, cy - sin(na).toFloat() * 14 * d,
            cx + cos(na).toFloat() * len, cy + sin(na).toFloat() * len, p)
        p.clearShadowLayer()
        p.strokeCap = Paint.Cap.BUTT

        // Hub.
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#2A1D10")
        canvas.drawCircle(cx, cy, 11 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = amber
        canvas.drawCircle(cx, cy, 11 * d, p)

        // Digital readout.
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = size * 0.15f
        p.color = textCol
        p.setShadowLayer(6 * d, 0f, 0f, amber)
        canvas.drawText(readout, cx, cy + r * 0.55f, p)
        p.clearShadowLayer()
        p.typeface = Typeface.DEFAULT
        p.textSize = size * 0.05f
        p.color = muted
        canvas.drawText(caption, cx, cy + r * 0.72f, p)
    }
}

/** Track progress line with a round knob. */
class SeekLine(context: Context) : View(context) {
    var fraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var active: Boolean = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val d = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val knob = 7 * d
        val y = height / 2f
        val x0 = knob
        val x1 = width - knob
        val x = x0 + (x1 - x0) * fraction
        p.strokeWidth = 4 * d
        p.color = Color.parseColor("#3A2E24")
        canvas.drawLine(x0, y, x1, y, p)
        if (!active) return
        p.color = Color.parseColor("#F3A43D")
        canvas.drawLine(x0, y, x, y, p)
        p.setShadowLayer(6 * d, 0f, 0f, Color.parseColor("#F3A43D"))
        canvas.drawCircle(x, y, knob, p)
        p.clearShadowLayer()
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
}

/** Segmented level bar; [fraction] = null draws all segments dimmed. */
class SegmentBar(context: Context, private val segments: Int = 10) : View(context) {
    var fraction: Float? = null
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val gap = 3 * d
        val w = (width - gap * (segments - 1)) / segments
        val lit = ((fraction ?: 0f).coerceIn(0f, 1f) * segments).toInt()
        val skew = height * 0.35f
        val path = android.graphics.Path()
        for (i in 0 until segments) {
            val x = i * (w + gap)
            path.reset()
            path.moveTo(x + skew, 0f); path.lineTo(x + w, 0f)
            path.lineTo(x + w - skew, height.toFloat()); path.lineTo(x, height.toFloat()); path.close()
            p.color = Color.parseColor(if (fraction != null && i < lit) "#F3A43D" else "#2E2318")
            canvas.drawPath(path, p)
        }
    }
}
