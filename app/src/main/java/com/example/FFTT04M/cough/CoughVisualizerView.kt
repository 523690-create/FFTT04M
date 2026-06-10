package com.example.FFTT04M.cough

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws one cough's "squiggle": the tracked ridge points (cyan) and the fitted parabola
 * f ≈ a·t² + b·t + c (magenta) over a time × frequency plot (300–1000 Hz band). Matches the app's
 * magenta/cyan theme. Set data via [show].
 */
class CoughVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var points: List<RidgeExtractor.RidgePoint> = emptyList()
    private var ridge: RidgeFeatures? = null
    private var loHz = 300.0
    private var hiHz = 1000.0
    private var caption = "No cough selected"

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; strokeWidth = 2f }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22FFFFFF") }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5FF"); style = Paint.Style.FILL }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00FF"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 28f }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f }

    fun show(points: List<RidgeExtractor.RidgePoint>, ridge: RidgeFeatures, loHz: Double, hiHz: Double, caption: String) {
        this.points = points
        this.ridge = ridge
        this.loHz = loHz
        this.hiHz = hiHz
        this.caption = caption
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 84f; val padR = 24f; val padT = 48f; val padB = 56f
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        canvas.drawText(caption, padL, 32f, title)

        // Axes
        canvas.drawLine(padL, padT, padL, padT + plotH, axis)
        canvas.drawLine(padL, padT + plotH, padL + plotW, padT + plotH, axis)

        // Frequency grid + labels (loHz .. hiHz)
        val steps = 4
        for (i in 0..steps) {
            val frac = i.toDouble() / steps
            val y = padT + plotH * (1 - frac).toFloat()
            canvas.drawLine(padL, y, padL + plotW, y, grid)
            val hz = (loHz + frac * (hiHz - loHz)).toInt()
            canvas.drawText("$hz", 8f, y + 10f, label)
        }
        canvas.drawText("Hz", 8f, padT - 16f, label)
        canvas.drawText("time →", padL + plotW - 90f, padT + plotH + 44f, label)

        val pts = points
        if (pts.isEmpty()) {
            canvas.drawText("No ridge points", padL + 24f, padT + plotH / 2, label)
            return
        }
        val tMin = pts.first().timeSec
        val tMax = pts.last().timeSec.coerceAtLeast(tMin + 1e-6)
        fun sx(t: Double) = padL + plotW * ((t - tMin) / (tMax - tMin)).toFloat()
        fun sy(f: Double) = padT + plotH * (1 - ((f - loHz) / (hiHz - loHz)).toFloat()).coerceIn(0f, 1f)

        // Fitted parabola
        val r = ridge
        if (r != null && r.valid) {
            val path = android.graphics.Path()
            var started = false
            var i = 0
            while (i <= 100) {
                val t = tMin + (tMax - tMin) * i / 100.0
                val f = r.curvature * t * t + r.slope * t + r.intercept
                val x = sx(t); val y = sy(f)
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                i++
            }
            canvas.drawPath(path, curve)
        }

        // Ridge points
        for (p in pts) canvas.drawCircle(sx(p.timeSec), sy(p.freqHz), 5f, dot)
    }
}
