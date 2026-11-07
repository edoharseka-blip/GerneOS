package com.e90os.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun alphaOf(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

/**
 * Round analog gauge in the COCKPIT style: soft amber halo, warm metallic bezel, glowing scale
 * arc, tapered needle that eases to new values, big digital readout. [value] = null means
 * "no data": the needle rests at the minimum and the readout shows a dash.
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
        set(v) {
            if (field == v) return
            field = v
            animateTo(v ?: 0f)
        }
    var readout: String = "—"
        set(v) { field = v; invalidate() }
    var caption: String = ""
        set(v) { field = v; invalidate() }

    private var shown = 0f
    private var anim: ValueAnimator? = null

    private fun animateTo(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun c(hex: String) = Color.parseColor(hex)
    private val amber = c("#F3A43D")
    private val amberHot = c("#FFC46B")
    private val amberDim = c("#6B4722")
    private val red = c("#E5492D")
    private val labelCol = c("#F7A341")
    private val muted = c("#B6A99B")
    private val d = resources.displayMetrics.density

    private val startAngle = 150f
    private val sweep = 240f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val path = Path()
    private val blurS = BlurMaskFilter(4 * d, BlurMaskFilter.Blur.NORMAL)
    private val blurL = BlurMaskFilter(10 * d, BlurMaskFilter.Blur.NORMAL)
    private val blurXL = BlurMaskFilter(18 * d, BlurMaskFilter.Blur.NORMAL)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // blur mask filters need a software layer
    }

    private fun angleFor(v: Float) = startAngle + sweep * (v / maxValue).coerceIn(0f, 1f)

    private fun arcGlow(canvas: Canvas, cx: Float, cy: Float, rr: Float, from: Float, sw: Float, color: Int, width: Float) {
        arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
        glow.style = Paint.Style.STROKE
        glow.strokeCap = Paint.Cap.ROUND
        glow.strokeWidth = width * 2.4f
        glow.color = alphaOf(color, 130)
        glow.maskFilter = blurL
        canvas.drawArc(arc, from, sw, false, glow)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = width
        p.color = color
        canvas.drawArc(arc, from, sw, false, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - 16 * d

        // Dark face with a faint warm centre.
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r,
            intArrayOf(c("#1A120C"), c("#0C0907"), c("#080605")), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null

        // Faint full ring (the part of the bezel outside the scale stays dark).
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = c("#3A2616")
        canvas.drawCircle(cx, cy, r, p)

        // Glowing orange arc along the scale on the outer edge: wide soft halo + bright core.
        arc.set(cx - r, cy - r, cx + r, cy + r)
        glow.style = Paint.Style.STROKE
        glow.strokeCap = Paint.Cap.ROUND
        glow.maskFilter = blurL
        glow.maskFilter = blurXL
        glow.strokeWidth = 24 * d
        glow.color = alphaOf(c("#FF7A1A"), 70)
        canvas.drawArc(arc, startAngle, sweep, false, glow)
        glow.maskFilter = blurL
        glow.strokeWidth = 9 * d
        glow.color = alphaOf(c("#FF9A2E"), 110)
        canvas.drawArc(arc, startAngle, sweep, false, glow)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 2.5f * d
        p.alpha = 220
        p.shader = SweepGradient(cx, cy, intArrayOf(c("#FFB24D"), c("#FF8A1F"), c("#FFC66E"), c("#FFB24D")), null)
        canvas.drawArc(arc, startAngle, sweep, false, p)
        p.shader = null
        p.strokeCap = Paint.Cap.BUTT

        // Inner hairline ring.
        p.strokeWidth = 1 * d
        p.color = c("#2A1C12")
        canvas.drawCircle(cx, cy, r - 10 * d, p)

        // Red zone and the lit part of the scale up to the current value.
        val trackR = r - 10 * d
        if (shown > 0f) arcGlow(canvas, cx, cy, trackR, startAngle, angleFor(shown) - startAngle, amber, 2.5f * d)
        if (redFrom != null) arcGlow(canvas, cx, cy, trackR - 2 * d, angleFor(redFrom), angleFor(maxValue) - angleFor(redFrom), red, 5 * d)

        // Ticks and labels.
        val minorStep = majorStep / minorPerMajor
        p.textAlign = Paint.Align.CENTER
        val dense = maxValue / majorStep > 10
        var v = 0f
        while (v <= maxValue + 0.001f) {
            val major = (Math.round(v / minorStep) % minorPerMajor) == 0
            val a = Math.toRadians(angleFor(v).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val outer = trackR - 3 * d
            val inner = outer - (if (major) 12 else 6) * d
            val hot = redFrom != null && v >= redFrom
            if (major) {
                glow.style = Paint.Style.STROKE
                glow.strokeWidth = 5 * d
                glow.color = alphaOf(if (hot) red else amber, 110)
                glow.maskFilter = blurS
                canvas.drawLine(cx + ca * inner, cy + sa * inner, cx + ca * outer, cy + sa * outer, glow)
            }
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = (if (major) 2.4f else 1.1f) * d
            p.color = if (hot) red else if (major) amberHot else amberDim
            canvas.drawLine(cx + ca * inner, cy + sa * inner, cx + ca * outer, cy + sa * outer, p)
            if (major) {
                val lr = inner - (if (dense) 12 else 14) * d
                p.style = Paint.Style.FILL
                p.textSize = size * (if (dense) 0.058f else 0.085f)
                p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                p.color = if (hot) red else labelCol
                canvas.drawText((v / labelDivider).toInt().toString(), cx + ca * lr, cy + sa * lr + p.textSize / 3, p)
            }
            v += minorStep
        }
        p.strokeCap = Paint.Cap.BUTT

        // Unit.
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT
        p.textSize = size * 0.048f
        p.color = alphaOf(amber, 220)
        p.letterSpacing = 0.08f
        canvas.drawText(unit, cx, cy - r * 0.3f, p)
        p.letterSpacing = 0f

        // Needle: sharp angular wedge with a flat tail, soft glow underneath.
        val na = Math.toRadians(angleFor(shown).toDouble())
        val ca = cos(na).toFloat()
        val sa = sin(na).toFloat()
        val len = trackR - 14 * d
        val tail = 12 * d
        val half = 7.5f * d
        val tipX = cx + ca * len
        val tipY = cy + sa * len
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(cx - sa * half, cy + ca * half)
        path.lineTo(cx - ca * tail - sa * half * 0.6f, cy - sa * tail + ca * half * 0.6f)
        path.lineTo(cx - ca * tail + sa * half * 0.6f, cy - sa * tail - ca * half * 0.6f)
        path.lineTo(cx + sa * half, cy - ca * half)
        path.close()
        glow.style = Paint.Style.FILL
        glow.maskFilter = blurL
        glow.color = alphaOf(c("#FF7A1A"), 140)
        canvas.drawPath(path, glow)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(cx, cy, tipX, tipY, c("#D9620F"), c("#FFC27A"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, p)
        p.shader = null
        // Bright centre ridge for a faceted look.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1 * d
        p.color = alphaOf(c("#FFE2B8"), 200)
        canvas.drawLine(cx, cy, tipX, tipY, p)

        // Hub.
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, 11 * d, c("#3A2614"), c("#0E0A07"), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 10 * d, p)
        p.shader = null

        // Digital readout with a soft glow.
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = size * 0.21f
        glow.style = Paint.Style.FILL
        glow.textAlign = Paint.Align.CENTER
        glow.typeface = p.typeface
        glow.textSize = p.textSize
        glow.color = alphaOf(amber, 90)
        glow.maskFilter = blurL
        canvas.drawText(readout, cx, cy + r * 0.62f, glow)
        p.color = c("#FFFFFF")
        canvas.drawText(readout, cx, cy + r * 0.62f, p)
        p.typeface = Typeface.DEFAULT
        p.textSize = size * 0.045f
        p.color = muted
        canvas.drawText(caption, cx, cy + r * 0.8f, p)
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
    private val d = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val gap = 3 * d
        val w = (width - gap * (segments - 1)) / segments
        val lit = ((fraction ?: 0f).coerceIn(0f, 1f) * segments).toInt()
        val skew = height * 0.35f
        val path = Path()
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

/** Horizontal hairline that fades out at both ends. */
class FadeLine(context: Context, private val color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
            intArrayOf(Color.TRANSPARENT, color, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}
