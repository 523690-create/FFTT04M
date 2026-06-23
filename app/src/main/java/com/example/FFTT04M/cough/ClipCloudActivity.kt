package com.example.FFTT04M.cough

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.example.FFTT04M.DeviceCaps
import com.example.FFTT04M.GalleryTransfer
import com.example.FFTT04M.ViewerActivity
import com.example.FFTT04M.WavReader
import android.graphics.Path
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Background classes: they don't define the cloud's axes and get no boundary hull — they just fall
 *  where they may on the plane fitted to the cough/respiratory classes. */
private val BACKGROUND_LABELS = setOf("noise", "voice", "?")

/**
 * Phase 1.5 — the on-device "clip cloud". Each recording becomes a point: its whole-clip HuBERT
 * embedding (768-dim, the gold-standard features) projected to 2-D by PCA — the same construction as
 * the desktop BronchitisCloud, run here. Points are coloured by class (manual comment if present, else
 * the decoder's label); tap a point to open that clip in the viewer.
 *
 * Embeddings are cached in each clip's `.phon` (PhonemeDecoder writes "emb" on the HuBERT path), so the
 * first open computes them once — with a progress count — and later opens are instant. HuBERT-capable
 * devices only (gated by DeviceCaps.hubertAllowed); below the floor there are no embeddings to plot.
 */
class ClipCloudActivity : Activity() {

    private val MAX_CLIPS = 250   // cap the one-time compute (most-recent first)

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var view: ClipCloudView? = null
    @Volatile private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF1E1E22.toInt()) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(28, 28, 28, 28)
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        if (!DeviceCaps.hubertAllowed(this)) {
            status.text = "The clip cloud needs a HuBERT-capable device (≈3 GB+ RAM).\nThis device uses the lightweight DSP decoder, which has no embeddings to map."
            return
        }
        status.text = "Building clip cloud…"
        thread { build() }
    }

    override fun onDestroy() { cancelled = true; super.onDestroy() }

    private fun build() {
        val dir = GalleryTransfer.recordingsDir(this) ?: filesDir
        val wavs = (dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) } ?: emptyArray())
            .sortedByDescending { it.name }.take(MAX_CLIPS)
        if (wavs.isEmpty()) { setStatus("No recordings to map."); return }

        val pts = ArrayList<Pt>(wavs.size)
        var done = 0; var computed = 0
        for (wav in wavs) {
            if (cancelled) return
            done++
            // Cache hit (most opens) is a fast .phon read; only clips never decoded via HuBERT compute.
            val emb = try {
                PhonemeDecoder.read(dir, wav.nameWithoutExtension)?.emb ?: run {
                    val d = WavReader.read(wav)
                    PhonemeDecoder.clipEmbedding(this, wav, d.samples, d.sampleRate)?.also { computed++ }
                }
            } catch (_: Throwable) { null } ?: continue
            if (done % 5 == 0) setStatus(
                (if (computed > 0) "Computing embeddings ($computed new)…" else "Loading clips…") + "  $done / ${wavs.size}")
            pts.add(Pt(wav, labelFor(dir, wav.nameWithoutExtension), emb))
        }
        if (pts.size < 3) { setStatus("Not enough mappable clips yet (${pts.size}). Decode more first."); return }

        // Fit the 2 PCA axes on the "important" (non-background) clips only, so the plane captures the
        // variance AMONG the cough/respiratory classes; noise & voice are then projected onto that same
        // plane and fall where they may instead of dominating the axes.
        val fit = BooleanArray(pts.size) { pts[it].label !in BACKGROUND_LABELS }
        val proj = pca3d(pts.map { it.emb }, fit)
        for (i in pts.indices) { pts[i].x = proj[i][0]; pts[i].y = proj[i][1]; pts[i].z = proj[i][2] }
        // Centre on the important-clip centroid so tumble rotates about the cough structure.
        val imp = pts.filter { it.label !in BACKGROUND_LABELS }.ifEmpty { pts }
        val cx = imp.map { it.x }.average(); val cy = imp.map { it.y }.average(); val cz = imp.map { it.z }.average()
        for (p in pts) { p.x -= cx; p.y -= cy; p.z -= cz }

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            status.visibility = View.GONE
            val v = ClipCloudView(this, pts) { p ->
                startActivity(Intent(this, ViewerActivity::class.java).putExtra("FILE_PATH", p.file.absolutePath))
            }
            view = v
            root.addView(v, 0, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            Toast.makeText(this, "${pts.size} clips • 1-finger rotate (reveals PC3) • 2-finger pan • pinch zoom • tap to open",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun setStatus(s: String) = runOnUiThread { if (!isFinishing) status.text = s }

    /** Class label for colouring: a real manual comment wins (what the user knows), else the decode. */
    private fun labelFor(dir: File, base: String): String {
        val txt = File(dir, "$base.txt")
        if (txt.isFile) {
            val first = runCatching { txt.readText() }.getOrNull()?.lineSequence()?.firstOrNull()?.trim()
            if (!first.isNullOrBlank() && !first.startsWith("auto-match", true)) {
                return first.removePrefix("manual:").trim().lowercase()
            }
        }
        return PhonemeDecoder.read(dir, base)?.label ?: "?"
    }

    // ---- PCA-3D (z-norm + power iteration with deflation; mirrors BronchitisCloud) ----
    //  The basis (mean/std/covariance/eigenvectors) is fitted on the [fit]-marked clips only; ALL clips
    //  are then projected onto the top-3 PCs. PC3 (the depth axis) is what one-finger tumble reveals.
    //  Falls back to all clips if too few are marked.
    private fun pca3d(vecs: List<FloatArray>, fit: BooleanArray): Array<DoubleArray> {
        val n = vecs.size; val d = vecs[0].size
        val basis = vecs.indices.filter { fit[it] }.let { if (it.size >= 3) it else vecs.indices.toList() }
        val nb = basis.size
        val mean = DoubleArray(d); for (i in basis) { val v = vecs[i]; for (j in 0 until d) mean[j] += v[j] }
        for (j in 0 until d) mean[j] /= nb
        val std = DoubleArray(d)
        for (i in basis) { val v = vecs[i]; for (j in 0 until d) { val e = v[j] - mean[j]; std[j] += e * e } }
        for (j in 0 until d) std[j] = sqrt(std[j] / nb).coerceAtLeast(1e-9)
        // z-norm ALL clips with the basis mean/std (so projections are comparable)
        val z = Array(n) { k -> DoubleArray(d) { (vecs[k][it] - mean[it]) / std[it] } }
        // covariance over the BASIS rows only → axes capture variance among the important clips
        var cov = Array(d) { DoubleArray(d) }
        for (i in basis) { val v = z[i]; for (a in 0 until d) { val va = v[a]; for (b in a until d) cov[a][b] += va * v[b] } }
        for (a in 0 until d) for (b in a until d) { cov[a][b] /= nb; cov[b][a] = cov[a][b] }
        // top-3 eigenvectors by power iteration + deflation
        fun lambdaOf(m: Array<DoubleArray>, v: DoubleArray): Double {
            var s = 0.0; for (i in 0 until d) { var t = 0.0; for (j in 0 until d) t += m[i][j] * v[j]; s += v[i] * t }; return s
        }
        fun deflate(m: Array<DoubleArray>, lam: Double, v: DoubleArray) =
            Array(d) { i -> DoubleArray(d) { j -> m[i][j] - lam * v[i] * v[j] } }
        val pc1 = powerIter(cov, d); cov = deflate(cov, lambdaOf(cov, pc1), pc1)
        val pc2 = powerIter(cov, d); cov = deflate(cov, lambdaOf(cov, pc2), pc2)
        val pc3 = powerIter(cov, d)
        return Array(n) { k ->
            var a = 0.0; var b = 0.0; var c = 0.0
            for (i in 0 until d) { a += z[k][i] * pc1[i]; b += z[k][i] * pc2[i]; c += z[k][i] * pc3[i] }
            doubleArrayOf(a, b, c)
        }
    }

    private fun powerIter(cov: Array<DoubleArray>, d: Int): DoubleArray {
        var v = DoubleArray(d) { if (it == 0) 1.0 else 0.0 }
        repeat(120) {
            val nv = DoubleArray(d)
            for (i in 0 until d) { var s = 0.0; val ci = cov[i]; for (j in 0 until d) s += ci[j] * v[j]; nv[i] = s }
            var nrm = 0.0; for (x in nv) nrm += x * x; nrm = sqrt(nrm).coerceAtLeast(1e-12)
            for (i in 0 until d) nv[i] /= nrm; v = nv
        }
        return v
    }

    class Pt(val file: File, val label: String, val emb: FloatArray) {
        var x = 0.0; var y = 0.0; var z = 0.0
    }

    /** 3-D PCA cloud: one-finger tumble (yaw+pitch) reveals PC3, two-finger pan, pinch zoom, tap opens.
     *  Class-boundary hulls are recomputed on the current projection each frame. */
    class ClipCloudView(
        ctx: Activity, private val pts: List<Pt>, private val onTap: (Pt) -> Unit,
    ) : View(ctx) {

        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hullFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val hullStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
        private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f }
        private val labels = pts.map { it.label }.distinct().sorted()
        private val colorOf = labels.associateWith { classColor(it) }
        private val hullLabels = pts.groupBy { it.label }.filterKeys { it !in BACKGROUND_LABELS }
            .filterValues { it.size >= 3 }.keys

        // Radius for auto-fit scaling (max distance of any point from the centre).
        private val maxR = (pts.maxOfOrNull { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) } ?: 1.0).coerceAtLeast(1e-6)

        // view state
        private var yaw = 0f; private var pitch = 0f
        private var zoom = 1f; private var panX = 0f; private var panY = 0f

        private val scaleGd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(g: ScaleGestureDetector): Boolean { zoom = (zoom * g.scaleFactor).coerceIn(0.3f, 12f); invalidate(); return true }
        })

        // 1 finger = tumble; 2 fingers = pan (+ pinch zoom via the detector); a quick still touch = tap.
        private var mode = 0           // 0 idle, 1 rotate, 2 pan/zoom
        private var lastX = 0f; private var lastY = 0f
        private var downX = 0f; private var downY = 0f; private var downT = 0L
        private var lastMidX = 0f; private var lastMidY = 0f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaleGd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { mode = 1; lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; downT = e.eventTime }
                MotionEvent.ACTION_POINTER_DOWN -> { mode = 2; val m = mid(e); lastMidX = m[0]; lastMidY = m[1] }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 2 && e.pointerCount >= 2) {
                        val m = mid(e); panX += m[0] - lastMidX; panY += m[1] - lastMidY; lastMidX = m[0]; lastMidY = m[1]; invalidate()
                    } else if (mode == 1) {
                        yaw += (e.x - lastX) * 0.01f
                        pitch = (pitch + (e.y - lastY) * 0.01f).coerceIn(-1.55f, 1.55f)
                        lastX = e.x; lastY = e.y; invalidate()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remain = if (e.actionIndex == 0) 1 else 0   // re-anchor rotate to the finger still down
                    lastX = e.getX(remain); lastY = e.getY(remain); mode = 1
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == 1) {
                        val dx = e.x - downX; val dy = e.y - downY
                        if (dx * dx + dy * dy < 40f * 40f && e.eventTime - downT < 300) hit(e.x, e.y)?.let(onTap)
                    }
                    mode = 0
                }
            }
            return true
        }

        private fun mid(e: MotionEvent): FloatArray {
            var sx = 0f; var sy = 0f; val n = e.pointerCount
            for (i in 0 until n) { sx += e.getX(i); sy += e.getY(i) }
            return floatArrayOf(sx / n, sy / n)
        }

        // 3-D → screen (orthographic): yaw about the vertical axis, pitch about the horizontal; z2 = depth.
        private fun project(p: Pt): FloatArray {
            val cy = cos(yaw.toDouble()); val sy = sin(yaw.toDouble())
            val cx = cos(pitch.toDouble()); val sx = sin(pitch.toDouble())
            val x1 = p.x * cy + p.z * sy
            val z1 = -p.x * sy + p.z * cy
            val y2 = p.y * cx - z1 * sx
            val z2 = p.y * sx + z1 * cx
            val bs = (min(width, height) * 0.40 / maxR) * zoom
            return floatArrayOf((width / 2f + x1 * bs + panX).toFloat(), (height / 2f - y2 * bs + panY).toFloat(), z2.toFloat())
        }

        private fun hit(px: Float, py: Float): Pt? {
            var best: Pt? = null; var bd = 48f * 48f
            for (p in pts) { val s = project(p); val dx = s[0] - px; val dy = s[1] - py; val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = p } }
            return best
        }

        override fun onDraw(canvas: Canvas) {
            // project all points, back-to-front by depth (painter's order)
            val sp = pts.map { it to project(it) }.sortedBy { it.second[2] }
            // class-boundary hulls on the current projection (behind the points)
            for (label in hullLabels) {
                val scr = sp.filter { it.first.label == label }.map { it.second }
                if (scr.size < 3) continue
                val hull = convexHull(scr.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) })
                if (hull.size < 3) continue
                val c = colorOf[label] ?: Color.GRAY
                val path = Path()
                hull.forEachIndexed { i, h -> if (i == 0) path.moveTo(h[0].toFloat(), h[1].toFloat()) else path.lineTo(h[0].toFloat(), h[1].toFloat()) }
                path.close()
                hullFill.color = (c and 0x00FFFFFF) or (0x1F shl 24); canvas.drawPath(path, hullFill)
                hullStroke.color = (c and 0x00FFFFFF) or (0xC0 shl 24); canvas.drawPath(path, hullStroke)
            }
            // depth-cued dots: nearer = larger & more opaque
            val zMin = sp.firstOrNull()?.second?.get(2) ?: 0f
            val zMax = sp.lastOrNull()?.second?.get(2) ?: 1f
            for ((p, s) in sp) {
                val t = if (zMax > zMin) (s[2] - zMin) / (zMax - zMin) else 0.5f
                dot.color = colorOf[p.label] ?: Color.GRAY
                dot.alpha = (110 + 145 * t).toInt().coerceIn(60, 255)
                canvas.drawCircle(s[0], s[1], 5f + 4f * t, dot)
            }
            // legend (top-left)
            dot.alpha = 255
            var ly = 44f
            for (l in labels) {
                dot.color = colorOf[l] ?: Color.GRAY
                canvas.drawCircle(28f, ly - 9f, 9f, dot)
                canvas.drawText("$l (${pts.count { it.label == l }})", 48f, ly, legendPaint)
                ly += 40f
            }
        }

        /** Andrew's monotone-chain convex hull (CCW, no repeated endpoint). */
        private fun convexHull(input: List<DoubleArray>): List<DoubleArray> {
            val p = input.distinctBy { it[0] to it[1] }.sortedWith(compareBy({ it[0] }, { it[1] }))
            if (p.size < 3) return p
            fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) =
                (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
            val lower = ArrayList<DoubleArray>()
            for (pt in p) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0) lower.removeAt(lower.size - 1)
                lower.add(pt)
            }
            val upper = ArrayList<DoubleArray>()
            for (pt in p.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0) upper.removeAt(upper.size - 1)
                upper.add(pt)
            }
            lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
            return lower + upper
        }

        companion object {
            private val FIXED = mapOf(
                "voice" to 0xFF4FC3F7.toInt(), "noise" to 0xFF9E9E9E.toInt(),
                "snoring" to 0xFFBA68C8.toInt(), "sneeze" to 0xFFFFD54F.toInt(),
                "dry" to 0xFFFF8A65.toInt(), "dry hacking" to 0xFFEF5350.toInt(),
                "typical bronchitis" to 0xFF66BB6A.toInt(), "bronchitis" to 0xFF66BB6A.toInt(),
                "croup" to 0xFFEC407A.toInt(),
            )
            /** Stable colour per class: fixed palette for known labels, else hashed hue. */
            fun classColor(label: String): Int = FIXED[label] ?: run {
                val h = (label.hashCode() and 0x7FFFFFFF) % 360
                Color.HSVToColor(floatArrayOf(h.toFloat(), 0.65f, 0.95f))
            }
        }
    }
}
