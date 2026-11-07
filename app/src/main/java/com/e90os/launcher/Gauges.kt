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
    private val labelCol = c("#FFDDA8")
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
        val r = size / 2f - 12 * d

        // Soft outer halo.
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r + 12 * d,
            intArrayOf(Color.TRANSPARENT, alphaOf(amber, 80), Color.TRANSPARENT),
            floatArrayOf(0.86f, 0.93f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r + 12 * d, p)

        // Bezel: warm metallic sweep.
        p.shader = SweepGradient(cx, cy,
            intArrayOf(c("#3A2410"), c("#C98432"), c("#3A2410"), c("#7A4C1C"), c("#3A2410")), null)
        canvas.drawCircle(cx, cy, r, p)

        // Face.
        p.shader = RadialGradient(cx, cy - r * 0.2f, r,
            intArrayOf(c("#1E1610"), c("#0D0A08"), c("#060504")), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r - 4 * d, p)
        p.shader = null

        // Thin glowing rim inside the bezel.
        glow.style = Paint.Style.STROKE
        glow.strokeWidth = 4 * d
        glow.color = alphaOf(amber, 110)
        glow.maskFilter = blurS
        canvas.drawCircle(cx, cy, r - 5 * d, glow)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.color = alphaOf(amberHot, 200)
        canvas.drawCircle(cx, cy, r - 5 * d, p)

        // Scale track and lit arc up to the current value.
        val trackR = r - 14 * d
        arc.set(cx - trackR, cy - trackR, cx + trackR, cy + trackR)
        p.strokeWidth = 3 * d
        p.color = c("#2A1E14")
        canvas.drawArc(arc, startAngle, sweep, false, p)
        if (shown > 0f) arcGlow(canvas, cx, cy, trackR, startAngle, angleFor(shown) - startAngle, amber, 3 * d)
        if (redFrom != null) arcGlow(canvas, cx, cy, trackR, angleFor(redFrom), angleFor(maxValue) - angleFor(redFrom), red, 4 * d)

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
            val outer = trackR - 6 * d
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
                p.textSize = size * (if (dense) 0.05f else 0.07f)
                p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

        // Needle: tapered, with a wide soft glow underneath.
        val na = Math.toRadians(angleFor(shown).toDouble())
        val ca = cos(na).toFloat()
        val sa = sin(na).toFloat()
        val len = trackR - 8 * d
        val tail = 16 * d
        val half = 3.2f * d
        path.reset()
        path.moveTo(cx + ca * len, cy + sa * len)
        path.lineTo(cx - sa * half - ca * tail, cy + ca * half - sa * tail)
        path.lineTo(cx + sa * half - ca * tail, cy - ca * half - sa * tail)
        path.close()
        glow.style = Paint.Style.FILL
        glow.color = alphaOf(amber, 170)
        glow.maskFilter = blurL
        canvas.drawPath(path, glow)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(cx - ca * tail, cy - sa * tail, cx + ca * len, cy + sa * len,
            c("#B5651A"), c("#FFD08A"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, p)
        p.shader = null

        // Hub.
        p.shader = RadialGradient(cx - 3 * d, cy - 3 * d, 13 * d, c("#5A3A1C"), c("#120D09"), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 12 * d, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = amber
        canvas.drawCircle(cx, cy, 12 * d, p)

        // Digital readout with a soft glow.
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        p.textSize = size * 0.2f
        glow.style = Paint.Style.FILL
        glow.textAlign = Paint.Align.CENTER
        glow.typeface = p.typeface
        glow.textSize = p.textSize
        glow.color = alphaOf(amber, 160)
        glow.maskFilter = blurL
        canvas.drawText(readout, cx, cy + r * 0.6f, glow)
        p.color = c("#FFF1DD")
        canvas.drawText(readout, cx, cy + r * 0.6f, p)
        p.typeface = Typeface.DEFAULT
        p.textSize = size * 0.045f
        p.color = muted
        canvas.drawText(caption, cx, cy + r * 0.78f, p)
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
