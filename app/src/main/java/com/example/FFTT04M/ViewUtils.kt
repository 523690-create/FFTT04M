package com.example.FFTT04M

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.widget.TextViewCompat
import com.google.android.material.slider.Slider

/**
 * Make text scale to fit its view across device sizes and orientations. Walks the tree and enables
 * uniform autosizing on every TextView/Button, so captions stay legible without manual per-device sp.
 *
 * The slider *value* labels (the small TextViews that share a FrameLayout with a Slider) are skipped:
 * they're positioned and sized at runtime by the activities' label code, which would fight autosizing.
 * EQ/Filter band labels are also skipped; they're handled by updateAllLabelPositions().
 */
fun applyAutoSizeText(root: View, minSp: Int = 6, maxSp: Int = 18) {
    // TabLayout manages its own tab-label sizing; autosizing its internal TextViews fights it.
    if (root is com.google.android.material.tabs.TabLayout) return

    // IDs of labels that are handled separately by updateAllLabelPositions() in ViewerActivity
    val skipIds = intArrayOf(
        R.id.lblEq100, R.id.lblEq300, R.id.lblEq1k, R.id.lblEq3k, R.id.lblEq8k,
        R.id.lblFilterPercent, R.id.lblFilterRise, R.id.lblFilterFall,
        // Display-tab ENHANCE button: keep its fixed size so it matches the adjacent spinners
        // instead of auto-growing to fill its full-width row.
        R.id.btnViewerSweep
    )

    if (root is ViewGroup) {
        val hasSlider = (0 until root.childCount).any { root.getChildAt(it) is Slider }
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            // Inside a slider's FrameLayout, leave the positioned value label alone.
            if (hasSlider && child is TextView && child !is Button) continue
            // Skip EQ/Filter band labels that are manually sized elsewhere
            if (child is TextView && child.id in skipIds) continue
            applyAutoSizeText(child, minSp, maxSp)
        }
    } else if (root is TextView) { // Button is a TextView too
        // Skip the specific label IDs
        if (root.id !in skipIds) {
            // Scale the cap with screen size so a tablet isn't capped at phone-sized text.
            val scaledMax = (maxSp * uiScale(root.resources)).toInt().coerceAtLeast(minSp + 1)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                root, minSp, scaledMax, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
    }
}

/**
 * Binary search for the maximum text size that fits within the parent's constraints.
 * Handles multiline text by splitting by '\n' and measuring each line.
 */
/**
 * Binary search for the maximum text size that fits within the parent's constraints.
 * Handles multiline text by splitting by '\n' and measuring each line.
 * Optimized with caching to prevent UI freezes.
 */
fun TextView.setMaxTextSizeToFit(
    text: String,
    maxWidthRatio: Float = 1.0f,
    maxHeightRatio: Float = 1.0f,
    minSizeSp: Float = 6f,
    maxSizeSp: Float = 100f
) {
    val parent = this.parent as? View ?: return

    val runFit = {
        // Size against the view's OWN allocated box when its dimensions are determinate
        // (match_parent or 0dp+weight). Only fall back to the parent for wrap_content
        // dimensions, whose own measured size just echoes the text and can't bound it.
        // This keeps a label/button from ballooning to fill a much larger parent (the cause
        // of clipped EQ labels in narrow landscape columns and truncated top-bar buttons).
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val lpW = this.layoutParams?.width ?: wrap
        val lpH = this.layoutParams?.height ?: wrap
        val w = if (lpW != wrap && this.width > 0) this.width else parent.width
        val h = if (lpH != wrap && this.height > 0) this.height else parent.height

        if (w > 0 && h > 0) {
            val cacheKey = "fit_$text"
            val cacheVal = "${w}_${h}"
            if (this.getTag(R.id.tag_fit_text) != cacheKey || this.getTag(R.id.tag_fit_dims) != cacheVal) {
                // Disable standard autosizing which might fight us
                TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)

                // Subtract the content padding so text fits the drawable area, not the raw
                // view box. Without this, buttons size text to their full width and the
                // internal padding pushes it into an ellipsis ("GALLERY" -> "GALLE...").
                val padW = (compoundPaddingLeft + compoundPaddingRight).coerceAtLeast(0)
                val padH = (compoundPaddingTop + compoundPaddingBottom).coerceAtLeast(0)
                val targetWidth = (w - padW).coerceAtLeast(1) * maxWidthRatio
                val targetHeight = (h - padH).coerceAtLeast(1) * maxHeightRatio

                val paint = android.graphics.Paint(this.paint)
                var low = minSizeSp
                var high = maxSizeSp
                var best = low

                val lines = text.split("\n")
                // Pin the line count so the text can never spill onto an extra line and wrap
                // per-character (e.g. "Hz" breaking into "H"/"z" in a narrow column).
                this.maxLines = lines.size

                while (low <= high) {
                    val mid = (low + high) / 2f
                    paint.textSize = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, mid, resources.displayMetrics
                    )

                    var maxWidth = 0f
                    val fm = paint.fontMetrics
                    val lineHeight = fm.bottom - fm.top

                    for (line in lines) {
                        // measureText (not getTextBounds) so letterSpacing — applied by Material
                        // buttons — is counted; otherwise width is underestimated and the text
                        // overflows into an ellipsis.
                        maxWidth = kotlin.math.max(maxWidth, paint.measureText(line))
                    }
                    val totalHeight = lines.size * lineHeight

                    if (maxWidth <= targetWidth && totalHeight <= targetHeight) {
                        best = mid
                        low = mid + 0.1f
                    } else {
                        high = mid - 0.1f
                    }
                }

                setTextSize(TypedValue.COMPLEX_UNIT_SP, best)
                setText(text)
                this.setTag(R.id.tag_fit_text, cacheKey)
                this.setTag(R.id.tag_fit_dims, cacheVal)
            }
        }
    }

    if (parent.width <= 0 || parent.height <= 0) {
        // Prevent multiple pending posts for the same view (guard against layout thrashing freezes
        // when tabs change and parent dimensions aren't ready yet). Only post if one isn't pending.
        val pendingKey = "fit_pending_${this.id}"
        if (this.getTag(R.id.tag_fit_pending) != true) {
            this.setTag(R.id.tag_fit_pending, true)
            parent.post {
                this.setTag(R.id.tag_fit_pending, null)
                runFit()
            }
        }
    } else {
        runFit()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unified value-bar slider rendering — shared by Listen / FFT-Viewer / Wavelet so every vertical
// value slider looks identical. The Material Slider's own track is hidden; the value TextView becomes
// a flat coloured bar that fills 8/9 of its column (8:1 bar:gutter), centred, capped by a subtle
// thumb slightly lighter than the bar. Default ticks remain as a faint centred "dotted line".
// ─────────────────────────────────────────────────────────────────────────────

/** Screen-size UI scale so fixed sp/dp grow proportionally on bigger screens. Baseline 411dp (a
 *  large phone) → phones ≈ 1.0; ~1.7 on a 700dp tablet. Use it to multiply font caps and control
 *  heights so a tablet isn't stuck with phone-sized text/buttons. */
fun uiScale(res: android.content.res.Resources): Float =
    (res.configuration.smallestScreenWidthDp / 411f).coerceIn(1.0f, 2.2f)

/**
 * Place the launcher-icon roundel — magenta ring, cyan centre, and the current build's magenta
 * version letter (`@drawable/ic_launcher_foreground`) — just left of this heading's text, on the
 * same line. Sized in dp and drawn to those bounds, so it stays a small badge despite the icon's
 * 108dp source. The letter colour matches the launcher icon because this IS the launcher foreground.
 */
fun TextView.setVersionRoundelStart(sizeDp: Float = 26f, padDp: Float = 8f) {
    val d = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground) ?: return
    val px = (resources.displayMetrics.density * sizeDp).toInt()
    d.setBounds(0, 0, px, px)
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = (resources.displayMetrics.density * padDp).toInt()
}

/** Blend [color] toward white by [fraction] (0 = unchanged, 1 = white). */
fun lightenColor(color: Int, fraction: Float): Int {
    val r = (Color.red(color) + (255 - Color.red(color)) * fraction).toInt().coerceIn(0, 255)
    val g = (Color.green(color) + (255 - Color.green(color)) * fraction).toInt().coerceIn(0, 255)
    val b = (Color.blue(color) + (255 - Color.blue(color)) * fraction).toInt().coerceIn(0, 255)
    return Color.rgb(r, g, b)
}

/**
 * Style [slider] + its value [label] as one unified value bar in [barColor]. [valueMaxSp] caps the
 * auto-fitted value text. Call once per slider; it re-posts itself until the column has a width.
 */
fun styleValueBarSlider(slider: Slider, label: TextView?, barColor: Int, valueMaxSp: Float = 18f) {
    slider.post {
        val parent = slider.parent as? View ?: return@post
        val column = parent.width
        if (column <= 0) return@post
        val density = slider.resources.displayMetrics.density
        val barWidth = column * 8 / 9                       // 8:1 bar:gutter, centred in the column

        // Hide the real track + halo; keep default ticks as a faint centred dotted guide.
        slider.trackActiveTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        slider.trackInactiveTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        slider.haloTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        slider.trackHeight = barWidth

        // Subtle thumb: a thin bar slightly lighter than this slider's own colour, capping the bar.
        val thumb = ContextCompat.getDrawable(slider.context, R.drawable.slider_thumb_bar)?.mutate()
        thumb?.setTint(lightenColor(barColor, 0.30f))
        slider.thumbRadius = (2f * density).toInt()
        if (thumb != null) slider.setCustomThumbDrawable(thumb)

        label?.let { lab ->
            lab.setTextColor(Color.BLACK)
            lab.setBackgroundColor(barColor)
            lab.elevation = 6f * density
            lab.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lab.setPadding(0, (2f * density).toInt(), 0, 0)
            val lp = lab.layoutParams
            lp.width = barWidth
            lab.layoutParams = lp
            lab.minWidth = barWidth
            lab.maxWidth = barWidth
            lab.setTag(R.id.tag_value_max_sp, valueMaxSp * uiScale(slider.resources))
        }

        slider.post { updateValueBarLabel(slider, label, barColor) }
    }
}

/** Size [label]'s value text to fit the bar WIDTH only (height-independent, so it's stable as the
 *  fill height changes and correct when the value text changes). Cached by text+width. */
private fun fitBarValueText(label: TextView, barWidth: Int) {
    if (barWidth <= 0) return
    val text = label.text?.toString() ?: return
    val cacheKey = "barfit_${text}_$barWidth"
    if (label.getTag(R.id.tag_fit_text) == cacheKey) return
    val maxSp = (label.getTag(R.id.tag_value_max_sp) as? Float) ?: 20f
    val lines = text.split("\n")
    label.maxLines = lines.size
    val paint = android.graphics.Paint(label.paint)
    val dm = label.resources.displayMetrics
    var sp = maxSp
    while (sp > 7f) {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
        val widest = lines.maxOf { paint.measureText(it) }
        if (widest <= barWidth * 0.90f) break
        sp -= 0.5f
    }
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    label.setTag(R.id.tag_fit_text, cacheKey)
}

/**
 * Position the value [label] so its coloured fill runs from the thumb down to the container bottom.
 * Defers via a single guarded post (never an OnGlobalLayoutListener — that cascades into a layout
 * storm / ANR on tab switches when many labels are height-0 at once).
 */
fun updateValueBarLabel(slider: Slider, label: TextView?, barColor: Int) {
    if (label == null) return
    if (slider.isGone || label.isGone) return

    if (slider.height == 0 || label.height == 0 || label.layout == null) {
        if (label.getTag(R.id.tag_pos_pending) != true) {
            label.setTag(R.id.tag_pos_pending, true)
            label.post {
                label.setTag(R.id.tag_pos_pending, null)
                updateValueBarLabel(slider, label, barColor)
            }
        }
        return
    }

    val range = slider.valueTo - slider.valueFrom
    if (range <= 0f) return
    val normalized = (slider.value - slider.valueFrom) / range
    val totalHeight = slider.height.toFloat()
    val density = slider.resources.displayMetrics.density

    val thumbRadius = slider.thumbRadius.toFloat()
    val trackTop = slider.paddingTop + thumbRadius
    val trackBottom = totalHeight - slider.paddingBottom - thumbRadius
    val trackLength = trackBottom - trackTop
    val thumbY = trackBottom - (normalized * trackLength)

    // Clamp so a value at the minimum still shows a bar tall enough for the (≤2-line) value text.
    val minH = 46f * density * uiScale(slider.resources)
    val barTopY = thumbY.coerceAtMost(totalHeight - minH).coerceAtLeast(0f)
    label.translationY = barTopY - label.top

    val targetHeight = (totalHeight - barTopY).toInt().coerceAtLeast(minH.toInt())
    if (label.layoutParams.height != targetHeight) {
        label.layoutParams.height = targetHeight
        label.requestLayout()
    }
    label.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    label.setBackgroundColor(barColor)
    label.setTextColor(Color.BLACK)
    label.elevation = 6f * density
    fitBarValueText(label, label.layoutParams.width)
}

// ---------------------------------------------------------------------------------------------
// Shared colour-scheme picker (gradient-swatch radio dialog) used by Listen, FFT, and Wavelet.
// ---------------------------------------------------------------------------------------------

/** A horizontal gradient drawable sampled from a scheme's 256-LUT — used as a row background in
 *  the colour picker (and reusable elsewhere) so each option previews its actual colour map. */
fun colorSchemeSwatch(idx: Int): android.graphics.drawable.GradientDrawable {
    val lut = ColorMaps.lut(idx)
    val n = 16
    val colors = IntArray(n) { lut[(it * 255) / (n - 1)] }
    return android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, colors
    ).apply { cornerRadius = 8f }
}

/** Tap-to-pick colour-scheme dialog shared by all three screens. Each row's background IS that
 *  scheme's gradient (a live preview); white bold text + black shadow stays legible over any map.
 *  Tapping a row immediately calls [onPick] and dismisses the dialog — no Apply/Cancel. Tapping
 *  outside dismisses with no change. The current scheme is marked with a ✓. */
fun android.content.Context.showColorSchemeDialog(currentIdx: Int, onPick: (Int) -> Unit) {
    val density = resources.displayMetrics.density
    val group = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        val p = (16 * density).toInt()
        setPadding(p, p / 2, p, p / 2)
    }
    val scroll = android.widget.ScrollView(this).apply { addView(group) }
    val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Colour scheme")
        .setView(scroll)
        .create()
    for (i in 0 until ColorMaps.count) {
        group.addView(TextView(this).apply {
            text = ColorMaps.names[i] + if (i == currentIdx) "   ✓" else ""
            background = colorSchemeSwatch(i)
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            val vpad = (14 * density).toInt()
            val hpad = (16 * density).toInt()
            setPadding(hpad, vpad, hpad, vpad)
            isClickable = true
            setOnClickListener { onPick(i); dialog.dismiss() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        })
    }
    dialog.show()
}

/**
 * Read a Float pref that may have been corrupted to another numeric type by an older buggy import
 * (e.g. a Float key stored as Int). Coerces any Number back to Float instead of throwing
 * ClassCastException; falls back to [def] for anything else.
 */
fun SharedPreferences.getFloatCoerced(key: String, def: Float): Float =
    try {
        getFloat(key, def)
    } catch (e: ClassCastException) {
        (all[key] as? Number)?.toFloat() ?: def
    }
