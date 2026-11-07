package com.e90os.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Line icons drawn on a 24x24 grid, so they stay sharp at any size and take any colour. */
enum class Ico { HOME, MUSIC, PIN, CAR, GEAR, GRID, BT, WIFI, CLOUD, BATTERY, COOLANT, THERMO, OIL, FUEL, FUEL_DROP, PENCIL, PLUS, PREV, NEXT, PLAY, PAUSE, NAV, SIGNAL, ADD }

class IconView(context: Context, icon: Ico, color: Int, private val strokeUnits: Float = 1.7f) : View(context) {

    var icon: Ico = icon
        set(v) { field = v; invalidate() }
    var color: Int = color
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()

    private fun stroke() { p.style = Paint.Style.STROKE; p.strokeWidth = strokeUnits }
    private fun fill() { p.style = Paint.Style.FILL }

    private fun Canvas.line(x1: Float, y1: Float, x2: Float, y2: Float) { stroke(); drawLine(x1, y1, x2, y2, p) }
    private fun Canvas.rrect(l: Float, t: Float, rr: Float, b: Float, rad: Float, filled: Boolean = false) {
        if (filled) fill() else stroke()
        r.set(l, t, rr, b); drawRoundRect(r, rad, rad, p)
    }
    private fun Canvas.circle(x: Float, y: Float, rad: Float, filled: Boolean = false) {
        if (filled) fill() else stroke(); drawCircle(x, y, rad, p)
    }
    private fun Canvas.poly(filled: Boolean, close: Boolean, vararg pts: Float) {
        path.reset(); path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        if (close) path.close()
        if (filled) fill() else stroke()
        drawPath(path, p)
    }
    private fun Canvas.arc(cx: Float, cy: Float, rad: Float, start: Float, sweep: Float) {
        stroke(); r.set(cx - rad, cy - rad, cx + rad, cy + rad); drawArc(r, start, sweep, false, p)
    }
    private fun Canvas.waves(y: Float) {
        path.reset(); path.moveTo(3f, y)
        var x = 3f
        while (x < 21f) { path.quadTo(x + 1.5f, y - 1.6f, x + 3f, y); path.quadTo(x + 4.5f, y + 1.6f, x + 6f, y); x += 6f }
        stroke(); drawPath(path, p)
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24 * s) / 2f, (height - 24 * s) / 2f)
        canvas.scale(s, s)
        p.color = color
        with(canvas) {
            when (icon) {
                Ico.HOME -> {
                    poly(false, false, 3f, 11.5f, 12f, 3.5f, 21f, 11.5f)
                    poly(false, false, 5.5f, 9.5f, 5.5f, 20.5f, 10f, 20.5f, 10f, 14.5f, 14f, 14.5f, 14f, 20.5f, 18.5f, 20.5f, 18.5f, 9.5f)
                }
                Ico.MUSIC -> {
                    poly(false, false, 9f, 18f, 9f, 5.5f, 20f, 3.5f, 20f, 16f)
                    line(9f, 9f, 20f, 7f)
                    circle(6.5f, 18f, 2.6f, true); circle(17.5f, 16f, 2.6f, true)
                }
                Ico.PIN -> {
                    path.reset()
                    path.moveTo(12f, 22f)
                    path.cubicTo(12f, 22f, 5f, 15f, 5f, 9.5f)
                    path.arcTo(RectF(5f, 2.5f, 19f, 16.5f), 180f, 180f)
                    path.cubicTo(19f, 15f, 12f, 22f, 12f, 22f)
                    stroke(); drawPath(path, p)
                    circle(12f, 9.5f, 2.6f)
                }
                Ico.CAR -> {
                    poly(false, false, 4.5f, 11f, 7f, 5.5f, 17f, 5.5f, 19.5f, 11f)
                    rrect(2.5f, 11f, 21.5f, 17.5f, 2.5f)
                    circle(7f, 17.5f, 2f, true); circle(17f, 17.5f, 2f, true)
                    line(5f, 14f, 7f, 14f); line(17f, 14f, 19f, 14f)
                }
                Ico.GEAR -> {
                    circle(12f, 12f, 3f)
                    circle(12f, 12f, 7f)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        line(12f + 7f * Math.cos(a).toFloat(), 12f + 7f * Math.sin(a).toFloat(),
                            12f + 9.6f * Math.cos(a).toFloat(), 12f + 9.6f * Math.sin(a).toFloat())
                    }
                }
                Ico.GRID -> {
                    rrect(4f, 4f, 10.5f, 10.5f, 1.5f); rrect(13.5f, 4f, 20f, 10.5f, 1.5f)
                    rrect(4f, 13.5f, 10.5f, 20f, 1.5f); rrect(13.5f, 13.5f, 20f, 20f, 1.5f)
                }
                Ico.BT -> poly(false, false, 7f, 7.5f, 17f, 16.5f, 12f, 21.5f, 12f, 2.5f, 17f, 7.5f, 7f, 16.5f)
                Ico.WIFI -> {
                    arc(12f, 20f, 15f, 225f, 90f); arc(12f, 20f, 10f, 225f, 90f); arc(12f, 20f, 5f, 225f, 90f)
                    circle(12f, 20f, 1.4f, true)
                }
                Ico.CLOUD -> {
                    path.reset()
                    path.moveTo(7f, 19f)
                    path.arcTo(RectF(2.5f, 11f, 10.5f, 19f), 90f, 160f)
                    path.arcTo(RectF(6.5f, 5f, 17f, 15.5f), 200f, 145f)
                    path.arcTo(RectF(13f, 10f, 22f, 19f), 270f, 180f)
                    path.close()
                    stroke(); drawPath(path, p)
                }
                Ico.BATTERY -> {
                    rrect(2.5f, 7.5f, 21.5f, 19.5f, 2f)
                    rrect(5.5f, 5f, 9f, 7.5f, 0.5f); rrect(15f, 5f, 18.5f, 7.5f, 0.5f)
                    line(6f, 13.5f, 10f, 13.5f); line(8f, 11.5f, 8f, 15.5f)
                    line(14f, 13.5f, 18f, 13.5f)
                }
                Ico.COOLANT -> {
                    rrect(10.5f, 2f, 13.5f, 13f, 1.5f)
                    circle(12f, 14.5f, 2.4f, true)
                    line(14.5f, 5f, 16.5f, 5f); line(14.5f, 8f, 16.5f, 8f); line(14.5f, 11f, 16.5f, 11f)
                    waves(19f); waves(22f)
                }
                Ico.THERMO -> {
                    rrect(10f, 2.5f, 14f, 15f, 2f)
                    circle(12f, 18f, 3.4f)
                    circle(12f, 18f, 1.6f, true)
                    line(12f, 9f, 12f, 16f)
                }
                Ico.OIL -> {
                    poly(false, true, 3f, 11f, 6f, 11f, 7.5f, 9f, 11f, 9f, 13f, 11f, 21f, 7.5f, 16f, 16f, 5f, 16f, 5f, 13.5f, 3f, 13f)
                    line(9f, 9f, 9f, 7f); line(7.5f, 7f, 10.5f, 7f)
                    path.reset(); path.moveTo(21f, 13f); path.quadTo(22.6f, 15.6f, 21f, 16.4f); path.quadTo(19.4f, 15.6f, 21f, 13f)
                    fill(); drawPath(path, p)
                }
                Ico.FUEL, Ico.FUEL_DROP -> {
                    rrect(4f, 3.5f, 13f, 21f, 1.5f)
                    rrect(6f, 6f, 11f, 10.5f, 0.8f, true)
                    line(2.5f, 21f, 14.5f, 21f)
                    poly(false, false, 13f, 10f, 15.5f, 10f, 15.5f, 16.5f)
                    path.reset(); path.moveTo(15.5f, 16.5f); path.quadTo(15.5f, 18.5f, 17.25f, 18.5f); path.quadTo(19f, 18.5f, 19f, 16.5f)
                    stroke(); drawPath(path, p)
                    poly(false, false, 19f, 16.5f, 19f, 8f, 16.5f, 5.5f)
                    if (icon == Ico.FUEL_DROP) {
                        path.reset(); path.moveTo(21.5f, 10.5f); path.quadTo(23.4f, 13.6f, 21.5f, 14.6f); path.quadTo(19.6f, 13.6f, 21.5f, 10.5f)
                        fill(); drawPath(path, p)
                    }
                }
                Ico.PENCIL -> {
                    poly(false, true, 4f, 20f, 5f, 15.5f, 15.5f, 5f, 19f, 8.5f, 8.5f, 19f)
                    line(13.5f, 7f, 17f, 10.5f)
                }
                Ico.PLUS -> {
                    rrect(3.5f, 3.5f, 20.5f, 20.5f, 3f)
                    line(12f, 8f, 12f, 16f); line(8f, 12f, 16f, 12f)
                }
                Ico.PREV -> { rrect(5f, 5f, 7.5f, 19f, 0.8f, true); poly(true, true, 19f, 5f, 8.5f, 12f, 19f, 19f) }
                Ico.NEXT -> { rrect(16.5f, 5f, 19f, 19f, 0.8f, true); poly(true, true, 5f, 5f, 15.5f, 12f, 5f, 19f) }
                Ico.PLAY -> poly(true, true, 7f, 4f, 20f, 12f, 7f, 20f)
                Ico.PAUSE -> { rrect(6f, 4f, 10f, 20f, 1f, true); rrect(14f, 4f, 18f, 20f, 1f, true) }
                Ico.ADD -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                Ico.SIGNAL -> {
                    rrect(3f, 16f, 6f, 21f, 0.8f, true); rrect(8f, 12f, 11f, 21f, 0.8f, true)
                    rrect(13f, 8f, 16f, 21f, 0.8f, true); rrect(18f, 3.5f, 21f, 21f, 0.8f, true)
                }
                Ico.NAV -> poly(false, true, 20f, 4f, 4f, 11f, 11.5f, 12.5f, 13f, 20f)
            }
        }
        canvas.restore()
    }
}
