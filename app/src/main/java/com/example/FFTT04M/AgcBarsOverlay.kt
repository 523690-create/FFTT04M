package com.example.FFTT04M

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * AUTO-mode visualization drawn ON TOP of the (grayed-out) 5-band manual EQ sliders. It renders the
 * live band-AGC de-tilt curve as 10 yellow bars — TWO bars over each of the 5 EQ columns, matching
 * the 5↔10 mapping (each manual EQ band spans two AGC bands). A taller bar = that band is being
 * boosted more by the adaptive de-tilt (i.e. it was quieter and got pulled up).
 *
 * Levels (0..1, length = number of AGC bands, expected 10) are pulled from [levelsProvider]; the view
 * self-refreshes ~15 fps while it's visible. Set the provider once and toggle visibility from AUTO/MANUAL.
 */
class AgcBarsOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Returns the current per-band levels (0..1), or null when there's nothing to draw yet. */
    var levelsProvider: (() -> FloatArray?)? = null

    private val columns = 5                 // manual EQ sliders the bars sit over
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; alpha = 210; style = Paint.Style.FILL
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 230; style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Keep the refresh loop alive FIRST — even on frames with no data yet (e.g. right after a
        // MANUAL→AUTO switch). Returning early before this is what made the bars stop reappearing.
        if (isShown) postInvalidateDelayed(66)   // ~15 fps while visible
        val levels = levelsProvider?.invoke() ?: return
        if (levels.isEmpty()) return
        val h = height.toFloat()
        val w = width.toFloat()
        val colW = w / columns
        val barW = colW * 0.20f          // each of the 2 bars in a column
        val gap = colW * 0.10f           // gap between the pair, centered on the column
        for (b in levels.indices) {
            val col = b / 2               // 0..4 EQ column
            val sub = b % 2               // 0 = left bar, 1 = right bar of the pair
            val colCenter = (col + 0.5f) * colW
            val x = if (sub == 0) colCenter - gap / 2 - barW else colCenter + gap / 2
            val lvl = levels[b].coerceIn(0f, 1f)
            val barH = lvl * h
            val top = h - barH
            canvas.drawRect(x, top, x + barW, h, barPaint)
            // bright cap line so even small values read as a distinct bar
            canvas.drawRect(x, (top - 3f).coerceAtLeast(0f), x + barW, top, capPaint)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
