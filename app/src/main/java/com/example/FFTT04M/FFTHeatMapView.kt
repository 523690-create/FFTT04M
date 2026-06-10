package com.example.FFTT04M

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.pow

class FFTHeatMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private var maxHistory = 129 
    private var currentColumn = 0
    private var spectrogramBitmap: Bitmap? = null
    // Per-pixel normalized magnitude (0..255; -1 = no data), row-major over the maxHistory-wide
    // bitmap. Lets setColorScheme recolour with a single LUT lookup per pixel (no reverse search).
    private var valueIndex: IntArray? = null
    private var yToBinMapping: IntArray? = null
    
    private val minFreq = 80f
    private val maxFreq = 10000f
    private var sampleRate = 44100f
    private var fftSize = 2048
    private var stepSize = 1024
    private var blurRadius = 0
    private var playCursor = -1f
    private val playCursorPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // Time-grid overlay (decoupled from blur). Bit 0 = thick 1 s lines, bit 1 = thin 100 ms lines.
    private val gridDensity = resources.displayMetrics.density
    private val gridThickPaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255); strokeWidth = 2f * gridDensity; style = Paint.Style.STROKE
    }
    private val gridThinPaint = Paint().apply {
        color = Color.argb(130, 255, 255, 255); strokeWidth = 1f * gridDensity; style = Paint.Style.STROKE
    }
    private var timeGridMask = 0

    /** Set the time-grid overlay: bit0 = 1 s thick lines, bit1 = 100 ms thin lines, 0 = off. */
    fun setTimeGrid(mask: Int) {
        timeGridMask = mask
        invalidate()
    }

    var isFrozen = false
    private var isEngineActive = false

    fun setEngineActive(active: Boolean) {
        isEngineActive = active
        postInvalidate()
    }

    private var coughAnalysisAlpha = 0f

    fun setCoughAnalysisAlpha(alpha: Float) {
        coughAnalysisAlpha = alpha.coerceIn(0f, 1f)
    }

    private var zoomFactorX = 1f
    private var zoomFactorY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** Invoked when the user finishes a pan/zoom gesture, so the host can persist the view state. */
    var onViewStateChanged: (() -> Unit)? = null

    /** Current pan/zoom as [zoomX, zoomY, offsetX, offsetY] — used to persist per-recording view. */
    fun getViewState(): FloatArray = floatArrayOf(zoomFactorX, zoomFactorY, offsetX, offsetY)

    /** Restore a previously saved pan/zoom. Offsets are clamped to the current view bounds. */
    fun setViewState(zx: Float, zy: Float, ox: Float, oy: Float) {
        zoomFactorX = zx.coerceIn(1f, 10f)
        zoomFactorY = zy.coerceIn(1f, 10f)
        offsetX = ox
        offsetY = oy
        if (width > 0 && height > 0) checkOffsets()
        invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldZoomX = zoomFactorX
            val oldZoomY = zoomFactorY
            
            val spanX = detector.currentSpanX
            val spanY = detector.currentSpanY
            val prevSpanX = detector.previousSpanX
            val prevSpanY = detector.previousSpanY
            val totalSpan = detector.currentSpan
            
            if (prevSpanX > 0 && prevSpanY > 0) {
                val scaleX = spanX / prevSpanX
                val scaleY = spanY / prevSpanY
                
                if (spanX > totalSpan * 0.4f) zoomFactorX *= scaleX
                if (spanY > totalSpan * 0.4f) zoomFactorY *= scaleY
            }
            
            zoomFactorX = zoomFactorX.coerceIn(1f, 10f)
            zoomFactorY = zoomFactorY.coerceIn(1f, 10f)
            
            val focusX = detector.focusX
            val focusY = detector.focusY
            
            offsetX = focusX - (focusX - offsetX) * (zoomFactorX / oldZoomX)
            offsetY = focusY - (focusY - offsetY) * (zoomFactorY / oldZoomY)
            
            checkOffsets()
            invalidate()
            return true
        }
    })

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Color schemes
    // Colour schemes are now defined once in ColorMaps as 256-entry LUTs (shared with WaveletView).
    private val colorSchemes = ColorMaps.luts
    private var activeColors = colorSchemes[0]

    /** Lowest-value (bottom-of-scale) color of a scheme - used as the COLOR control background. */
    fun lowColorFor(index: Int): Int = colorSchemes[index.coerceIn(0, colorSchemes.size - 1)].first()

    /** Highest-value (top-of-scale) color of a scheme - used as the COLOR control text color. */
    fun highColorFor(index: Int): Int = colorSchemes[index.coerceIn(0, colorSchemes.size - 1)].last()

    /** Recolour instantly from the cached per-pixel value buffer — one LUT lookup per pixel, no
     *  reverse search, no recompute from source. */
    fun setColorScheme(index: Int) {
        activeColors = ColorMaps.lut(index)
        val bitmap = spectrogramBitmap
        val vi = valueIndex
        if (bitmap != null && vi != null) {
            synchronized(bitmap) {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                for (i in pixels.indices) {
                    val v = vi[i]
                    pixels[i] = if (v >= 0) activeColors[v] else Color.TRANSPARENT
                }
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }
        invalidate()
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
    })

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    fun setParams(newFftSize: Int, newSampleRate: Float, newStepSize: Int) {
        this.fftSize = newFftSize
        this.sampleRate = newSampleRate
        this.stepSize = newStepSize
        precalculateMapping()
    }

    fun setBlur(radius: Int) {
        blurRadius = radius.coerceIn(0, 10)
        invalidate()
    }

    /** Playback playhead position in [0,1] across the time axis, or <0 to hide. */
    fun setPlayCursor(progress: Float) {
        playCursor = progress
        postInvalidate()
    }

    fun clearPlayCursor() {
        playCursor = -1f
        postInvalidate()
    }

    fun setMaxHistory(count: Int) {
        maxHistory = count
        resetBitmap()
    }

    fun clearHistory() {
        currentColumn = 0
        spectrogramBitmap?.eraseColor(Color.TRANSPARENT)
        valueIndex?.fill(-1)
        invalidate()
    }

    private fun resetBitmap() {
        if (width > 0 && height > 0) {
            val h = height
            spectrogramBitmap = Bitmap.createBitmap(maxHistory, h, Bitmap.Config.ARGB_8888)
            valueIndex = IntArray(maxHistory * h) { -1 }
            currentColumn = 0
            precalculateMapping()
        }
    }

    private fun precalculateMapping() {
        val h = height
        if (h <= 0) return
        val mapping = IntArray(h)
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val numBins = fftSize / 2
        
        for (y in 0 until h) {
            val logF = logMax - (y.toFloat() / h) * (logMax - logMin)
            val freq = 10.0.pow(logF.toDouble()).toFloat()
            mapping[y] = (freq * fftSize / sampleRate).toInt().coerceIn(0, numBins - 1)
        }
        yToBinMapping = mapping
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetBitmap()
    }

    fun updateFFT(data: FloatArray, force: Boolean = false) {
        if (isFrozen && !force) return
        val bitmap = spectrogramBitmap ?: return
        val h = bitmap.height
        
        currentColumn = (currentColumn + 1) % maxHistory

        val columnPixels = IntArray(h)
        val vi = valueIndex

        if (data.size == h) {
            // Data is already mapped to the heat map height (e.g. from ViewerActivity background thread)
            for (y in 0 until h) {
                val idx = valueToIndex(data[y])
                columnPixels[y] = activeColors[idx]
                vi?.set(y * maxHistory + currentColumn, idx)
            }
        } else {
            // Data is raw frequency bins (e.g. from MainActivity real-time recording)
            val logMin = log10(minFreq)
            val logMax = log10(maxFreq)
            val numBins = data.size

            // Vertical interpolation for smoother color mapping
            for (y in 0 until h) {
                val logF = logMax - (y.toFloat() / h) * (logMax - logMin)
                val freq = 10.0.pow(logF.toDouble()).toFloat()
                val binIdxExact = (freq * fftSize / sampleRate)

                val idx1 = binIdxExact.toInt().coerceIn(0, numBins - 1)
                val idx2 = (idx1 + 1).coerceAtMost(numBins - 1)
                val frac = binIdxExact - idx1

                val val1 = data[idx1]
                val val2 = data[idx2]
                val interpolatedVal = val1 + (val2 - val1) * frac.toFloat()

                val idx = valueToIndex(interpolatedVal)
                columnPixels[y] = activeColors[idx]
                vi?.set(y * maxHistory + currentColumn, idx)
            }
        }

        synchronized(bitmap) {
            bitmap.setPixels(columnPixels, 0, 1, currentColumn, 0, 1, h)
        }
        postInvalidate()
    }

    private fun checkOffsets() {
        val maxOffsetX = 0f
        val minOffsetX = width * (1f - zoomFactorX)
        val maxOffsetY = 0f
        val minOffsetY = height * (1f - zoomFactorY)
        offsetX = offsetX.coerceIn(minOffsetX, maxOffsetX)
        offsetY = offsetY.coerceIn(minOffsetY, maxOffsetY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        
        if (!scaleDetector.isInProgress) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    offsetX += dx
                    offsetY += dy
                    checkOffsets()
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    performClick()
                    onViewStateChanged?.invoke()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = spectrogramBitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(zoomFactorX, zoomFactorY)

        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }

        synchronized(bitmap) {
            if (blurRadius > 0) {
                // To achieve blur without RenderEffect, we draw at lower resolution into a temporary bitmap
                // then upscale with bilinear filtering (isFilterBitmap = true).
                val scale = 1.0f / (blurRadius * 0.5f + 1.0f)
                val bw = (maxHistory * scale).toInt().coerceAtLeast(10)
                val bh = (bitmap.height * scale).toInt().coerceAtLeast(10)
                
                val blurredBitmap = Bitmap.createScaledBitmap(bitmap, bw, bh, true)
                
                if (isFrozen) {
                    canvas.drawBitmap(blurredBitmap, null, RectF(0f, 0f, w, h), paint)
                } else {
                    val bW = blurredBitmap.width
                    val drawCol = (currentColumn.toFloat() / maxHistory * bW).toInt()
                    
                    val splitX = (drawCol + 1).toFloat() / bW * w
                    val src1 = Rect(drawCol + 1, 0, bW, blurredBitmap.height)
                    val dst1 = RectF(0f, 0f, w - splitX, h)
                    canvas.drawBitmap(blurredBitmap, src1, dst1, paint)
                    
                    val src2 = Rect(0, 0, drawCol + 1, blurredBitmap.height)
                    val dst2 = RectF(w - splitX, 0f, w, h)
                    canvas.drawBitmap(blurredBitmap, src2, dst2, paint)
                }
            } else {
                if (isFrozen) {
                    canvas.drawBitmap(bitmap, null, RectF(0f, 0f, w, h), paint)
                } else {
                    val splitX = (currentColumn + 1).toFloat() / maxHistory * w
                    val src1 = Rect(currentColumn + 1, 0, maxHistory, bitmap.height)
                    val dst1 = RectF(0f, 0f, w - splitX, h)
                    canvas.drawBitmap(bitmap, src1, dst1, paint)
                    
                    val src2 = Rect(0, 0, currentColumn + 1, bitmap.height)
                    val dst2 = RectF(w - splitX, 0f, w, h)
                    canvas.drawBitmap(bitmap, src2, dst2, paint)
                }
            }
        }
        canvas.restore()

        // Time-grid overlay — independent of blur now (bit0 = 1 s thick, bit1 = 100 ms thin).
        // Lines are transformed by the same pan/zoom as the spectrogram so they track the time axis.
        if (timeGridMask != 0) {
            val msPerFrame = stepSize.toFloat() / sampleRate * 1000f
            if (msPerFrame > 0f) {
                val yTop = offsetY
                val yBottom = offsetY + h * zoomFactorY
                fun drawEvery(intervalMs: Float, paint: Paint) {
                    val framesPerLine = intervalMs / msPerFrame
                    if (framesPerLine < 1f) return          // would be denser than 1 px/column
                    var frame = framesPerLine
                    while (frame < maxHistory) {
                        val x = offsetX + (frame / maxHistory) * w * zoomFactorX
                        if (x in 0f..w) canvas.drawLine(x, yTop, x, yBottom, paint)
                        frame += framesPerLine
                    }
                }
                if (timeGridMask and 2 != 0) drawEvery(100f, gridThinPaint)    // 100 ms (under)
                if (timeGridMask and 1 != 0) drawEvery(1000f, gridThickPaint)  // 1 s (on top)
            }
        }

        // Play cursor: vertical playhead synced to audio playback position (tracks pan/zoom).
        if (playCursor in 0f..1f) {
            val px = offsetX + playCursor * w * zoomFactorX
            canvas.drawLine(px, offsetY, px, offsetY + h * zoomFactorY, playCursorPaint)
        }

        // Labels stay fixed but move vertically with pan/zoom
        val targetFreqs = floatArrayOf(100f, 300f, 1000f, 3000f, 8000f)
        for (f in targetFreqs) {
            val y = freqToY(f, h) * zoomFactorY + offsetY
            canvas.drawLine(0f, y, w, y, linePaint)
            canvas.drawText("${f.toInt()}Hz", 10f, y - 5f, textPaint)
        }

        // Draw Sz and St in the top right corner ONLY if a standard engine is selected.
        if (!isEngineActive) {
            val szText = "Sz $fftSize"
            val stText = "St $stepSize"
            val infoX = w - 10f
            canvas.drawText(szText, infoX - textPaint.measureText(szText), 40f, textPaint)
            canvas.drawText(stText, infoX - textPaint.measureText(stText), 80f, textPaint)
        }
    }

    private fun freqToY(freq: Float, h: Float): Float {
        val f = freq.coerceIn(minFreq, maxFreq)
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val logF = log10(f)
        return h * (1f - (logF - logMin) / (logMax - logMin))
    }

    /** Normalized magnitude (0..1) → LUT index (0..255). The colour is then activeColors[idx]. */
    private fun valueToIndex(value: Float): Int =
        (value.coerceIn(0f, 1f) * (activeColors.size - 1)).toInt().coerceIn(0, activeColors.size - 1)

    /**
     * Capture the whole heat-map exactly as displayed (current pan/zoom/colors) to a 256x256 PNG.
     * Used to refresh a recording's gallery thumbnail when its comment is saved.
     */
    fun saveSnapshot(fileName: String) {
        if (width <= 0 || height <= 0) return
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(full))
        val icon = Bitmap.createScaledBitmap(full, 256, 256, true)
        val file = File(GalleryTransfer.recordingsDir(context), fileName)
        FileOutputStream(file).use { out ->
            icon.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    fun saveIcon(cropRect: RectF, fileName: String) {
        if (width <= 0 || height <= 0) return
        val fullRender = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fullRender)
        draw(canvas)
        val left = cropRect.left.toInt().coerceIn(0, width - 1)
        val top = cropRect.top.toInt().coerceIn(0, height - 1)
        val right = cropRect.right.toInt().coerceIn(left + 1, width)
        val bottom = cropRect.bottom.toInt().coerceIn(top + 1, height)
        val cropped = Bitmap.createBitmap(fullRender, left, top, right - left, bottom - top)
        val icon = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        val file = File(GalleryTransfer.recordingsDir(context), fileName)
        FileOutputStream(file).use { out ->
            icon.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
