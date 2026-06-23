package com.example.FFTT04M.cough

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
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
        val proj = pca2d(pts.map { it.emb }, fit)
        for (i in pts.indices) { pts[i].x = proj[i][0]; pts[i].y = proj[i][1] }

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            status.visibility = View.GONE
            val v = ClipCloudView(this, pts) { p ->
                startActivity(Intent(this, ViewerActivity::class.java).putExtra("FILE_PATH", p.file.absolutePath))
            }
            view = v
            root.addView(v, 0, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            Toast.makeText(this, "${pts.size} clips • pinch to zoom, drag to pan, tap a point to open", Toast.LENGTH_LONG).show()
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

    // ---- PCA-2D (z-norm + power iteration with deflation; mirrors BronchitisCloud) ----
    //  The basis (mean/std/covariance/eigenvectors) is fitted on the [fit]-marked clips only; ALL clips
    //  are then projected onto it. Falls back to all clips if too few are marked.
    private fun pca2d(vecs: List<FloatArray>, fit: BooleanArray): Array<DoubleArray> {
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
        val cov = Array(d) { DoubleArray(d) }
        for (i in basis) { val v = z[i]; for (a in 0 until d) { val va = v[a]; for (b in a until d) cov[a][b] += va * v[b] } }
        for (a in 0 until d) for (b in a until d) { cov[a][b] /= nb; cov[b][a] = cov[a][b] }
        val pc1 = powerIter(cov, d)
        var lam = 0.0; for (i in 0 until d) { var t = 0.0; for (j in 0 until d) t += cov[i][j] * pc1[j]; lam += pc1[i] * t }
        val cov2 = Array(d) { i -> DoubleArray(d) { j -> cov[i][j] - lam * pc1[i] * pc1[j] } }
        val pc2 = powerIter(cov2, d)
        return Array(n) { k ->
            var a = 0.0; var b = 0.0
            for (i in 0 until d) { a += z[k][i] * pc1[i]; b += z[k][i] * pc2[i] }
            doubleArrayOf(a, b)
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
        var x = 0.0; var y = 0.0
    }

    /** Pan/zoom scatter; tap a point (within a small radius) to open the clip. */
    class ClipCloudView(
        ctx: Activity, private val pts: List<Pt>, private val onTap: (Pt) -> Unit,
    ) : View(ctx) {

        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hullFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val hullStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
        private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f }
        private val labels = pts.map { it.label }.distinct().sorted()
        private val colorOf = labels.associateWith { classColor(it) }

        // Class-boundary hulls: a translucent convex hull per non-background class (≥3 points).
        private val hulls: Map<String, List<DoubleArray>> = pts.groupBy { it.label }
            .filterKeys { it !in BACKGROUND_LABELS }
            .mapValues { (_, ps) -> convexHull(ps.map { doubleArrayOf(it.x, it.y) }) }
            .filterValues { it.size >= 3 }

        // Frame on the important clips so the cough structure fills the view; background points that
        // land outside just sit near the edges (pan/zoom to reach them).
        private val framePts = pts.filter { it.label !in BACKGROUND_LABELS }.ifEmpty { pts }
        private val minX = framePts.minOf { it.x }; private val maxX = framePts.maxOf { it.x }
        private val minY = framePts.minOf { it.y }; private val maxY = framePts.maxOf { it.y }

        private var scale = 1f; private var panX = 0f; private var panY = 0f
        private val scaleGd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(g: ScaleGestureDetector): Boolean {
                scale = (scale * g.scaleFactor).coerceIn(0.4f, 12f); invalidate(); return true
            }
        })
        private val tapGd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                panX -= dx; panY -= dy; invalidate(); return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean { hit(e.x, e.y)?.let(onTap); return true }
        })

        // data → screen
        private fun sx(x: Double) = panX + (40 + (x - minX) / (maxX - minX + 1e-9) * (width - 80)).toFloat() * scale
        private fun sy(y: Double) = panY + (height - 60 - (y - minY) / (maxY - minY + 1e-9) * (height - 200)).toFloat() * scale

        private fun hit(px: Float, py: Float): Pt? {
            var best: Pt? = null; var bd = 48f * 48f
            for (p in pts) { val dx = sx(p.x) - px; val dy = sy(p.y) - py; val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = p } }
            return best
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaleGd.onTouchEvent(e); tapGd.onTouchEvent(e); return true
        }

        override fun onDraw(canvas: Canvas) {
            // class-boundary hulls (translucent, behind the points)
            for ((label, hull) in hulls) {
                val c = colorOf[label] ?: Color.GRAY
                val path = Path()
                hull.forEachIndexed { i, p ->
                    val x = sx(p[0]); val y = sy(p[1]); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                hullFill.color = (c and 0x00FFFFFF) or (0x1F shl 24)     // ~12% alpha fill (overlaps stay legible)
                canvas.drawPath(path, hullFill)
                hullStroke.color = (c and 0x00FFFFFF) or (0xC0 shl 24)   // strong coloured outline = the boundary
                canvas.drawPath(path, hullStroke)
            }
            for (p in pts) {
                dot.color = colorOf[p.label] ?: Color.GRAY
                canvas.drawCircle(sx(p.x), sy(p.y), 7f, dot)
            }
            // legend (top-left)
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
