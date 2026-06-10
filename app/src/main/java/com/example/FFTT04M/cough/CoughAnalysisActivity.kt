package com.example.FFTT04M.cough

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.FFTT04M.GalleryTransfer
import com.example.FFTT04M.WavReader
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Runs the classical cough-analysis pipeline on one recording and presents the results: a list of
 * detected cough events with their FFT + ridge features, a tap-to-view "squiggle" plot, and a
 * one-tap export to the unified training JSON schema. Launched with a "FILE_PATH" extra.
 */
class CoughAnalysisActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var viz: CoughVisualizerView
    private lateinit var rowsContainer: LinearLayout

    private var filePath: String? = null
    private var analysis: CoughAnalysis? = null
    private var ridgePoints: List<List<RidgeExtractor.RidgePoint>> = emptyList()
    private val cfg = CoughAnalysisConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Cough Analysis"
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        status = TextView(this).apply {
            textSize = 15f; setPadding(dp(16), dp(16), dp(16), dp(8)); text = "Analyzing…"
        }
        viz = CoughVisualizerView(this)
        rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(rowsContainer)
        }
        val exportBtn = Button(this).apply {
            text = "Export training JSON"
            setOnClickListener { exportJson() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(viz, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)))
            addView(scroll)
            addView(exportBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(8), dp(16), dp(16))
            })
        })

        filePath = intent.getStringExtra("FILE_PATH")
        runAnalysis()
    }

    private fun runAnalysis() {
        val path = filePath
        if (path == null) { status.text = "No recording specified."; return }
        thread {
            try {
                val file = File(path)
                if (!file.exists()) { ui { status.text = "Recording not found." }; return@thread }
                // Cap at 60 s so very long captures don't exhaust memory on low-end devices.
                val wav = WavReader.read(file, maxFrames = 60 * 44100)
                val analyzer = CoughAnalyzer(cfg)
                val (result, ridges) = analyzer.analyzeWithRidges(wav.samples, wav.sampleRate)
                ui {
                    analysis = result
                    ridgePoints = ridges
                    populate(result, ridges, file.nameWithoutExtension)
                }
            } catch (e: Throwable) {
                ui { status.text = "Could not analyze: ${e.message}" }
            }
        }
    }

    private fun populate(a: CoughAnalysis, ridges: List<List<RidgeExtractor.RidgePoint>>, base: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        status.text = "$base — ${a.events.size} event(s), ${a.coughCount} cough-like, " +
            "${"%.1f".format(a.totalSamples.toDouble() / a.sampleRate)} s @ ${a.sampleRate} Hz"
        rowsContainer.removeAllViews()
        if (a.events.isEmpty()) {
            rowsContainer.addView(TextView(this).apply {
                text = "No cough-like events detected."; setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        // Standardized feature space → each cough's nearest twin (the email's "comparable data point").
        val z = CoughSimilarity.standardize(a.events.map { it.featureVector() }).vectors
        a.events.forEachIndexed { i, e ->
            val nnText = if (a.events.size > 1) {
                val (nn, dist) = CoughSimilarity.nearestNeighbor(i, z)
                if (nn >= 0) "\n≈ closest: #${nn + 1}  (d=%.2f)".format(Locale.US, dist) else ""
            } else ""
            val row = TextView(this).apply {
                textSize = 13f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setTextColor(Color.WHITE)
                setBackgroundColor(if (e.speech.isLikelyCough) Color.parseColor("#1B3A1B") else Color.parseColor("#3A1B1B"))
                text = rowSummary(e) + nnText
                setOnClickListener { showRidge(i) }
            }
            rowsContainer.addView(row)
            rowsContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(Color.parseColor("#222222"))
            })
        }
        // Default the plot to the first event that has a valid ridge.
        val firstRidge = a.events.indexOfFirst { it.ridge.valid }
        showRidge(if (firstRidge >= 0) firstRidge else 0)
    }

    private fun rowSummary(e: CoughEvent): String {
        val tag = if (e.speech.isLikelyCough) "COUGH" else "speech?"
        val r = e.ridge
        val ridgeStr = if (r.valid)
            "ridge a=%.0f b=%.0f c=%.0f Hz  cf=%.0f  R²=%.2f".format(
                Locale.US, r.curvature, r.slope, r.intercept, r.centerFreqHz, r.rSquared)
        else "ridge: none"
        val phaseStr = e.phases?.let { "  T3(expulsive)=%.0fms".format(Locale.US, it.t3Sec * 1000) } ?: ""
        return "#${e.index + 1} [$tag] @ %.2fs  dur=%.0fms$phaseStr\n".format(Locale.US, e.segment.startSec, e.fft.durationSec * 1000) +
            "Q=%.2f  Fmax=%.0fHz  flat=%.2f pitch=%.2f\n".format(Locale.US, e.fft.qRatio, e.fft.fmaxHz, e.speech.spectralFlatness, e.speech.pitchStrength) +
            ridgeStr
    }

    private fun showRidge(index: Int) {
        val a = analysis ?: return
        if (index !in a.events.indices) return
        val e = a.events[index]
        val pts = ridgePoints.getOrElse(index) { emptyList() }
        viz.show(pts, e.ridge, cfg.ridgeLoHz, cfg.ridgeHiHz,
            "Cough #${e.index + 1} squiggle (${pts.size} pts)")
    }

    private fun exportJson() {
        val a = analysis
        val path = filePath
        if (a == null || path == null) { toast("Nothing to export yet"); return }
        thread {
            try {
                val base = File(path).nameWithoutExtension
                val dir = GalleryTransfer.recordingsDir(this) ?: filesDir
                val out = File(dir, "$base.cough.json")
                out.writeText(CoughSchemaJson.toJson(a, base, android.os.Build.MODEL))
                ui { toast("Exported ${out.name}") }
            } catch (e: Throwable) {
                ui { toast("Export failed: ${e.message}") }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
