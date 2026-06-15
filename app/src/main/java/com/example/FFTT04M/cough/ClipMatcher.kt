package com.example.FFTT04M.cough

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * On-device "cloud match": compares a captured clip's cough events against a compact reference set
 * (bundled asset `cough_ref.json`, built on the desktop from the large cough / non-cough database)
 * and returns the nearest match's label, used to auto-write a recording's comment sidecar.
 *
 * `cough_ref.json` schema:
 *   { "dim":21, "featureNames":[...], "mean":[21], "std":[21],
 *     "refs":[ { "src":..., "sound":..., "health":..., "cough":bool, "v":[21 z-scored] }, ... ] }
 *
 * The reference vectors are z-scored with the bundled mean/std (the SAME space the desktop tensor
 * used). We compute a device event's raw [8 DSP + 13 MFCC] vector (identical layout to the desktop
 * MetaAnalyzer), project it into that space via [CoughSimilarity.project], and take the nearest
 * reference by Euclidean distance. If the asset is missing or unparsable, matching is silently
 * disabled (the app behaves exactly as before).
 */
object ClipMatcher {

    private data class Ref(
        val src: String, val sound: String, val health: String,
        val cough: Boolean, val v: DoubleArray,
    )

    @Volatile private var loaded = false
    private var mean: DoubleArray? = null
    private var std: DoubleArray? = null
    private var refs: List<Ref> = emptyList()
    private val analyzer by lazy { CoughAnalyzer() }

    val isReady: Boolean get() = refs.isNotEmpty()

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val txt = ctx.assets.open("cough_ref.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            mean = o.getJSONArray("mean").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            std = o.getJSONArray("std").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val ra = o.getJSONArray("refs")
            val list = ArrayList<Ref>(ra.length())
            for (i in 0 until ra.length()) {
                val r = ra.getJSONObject(i)
                val va = r.getJSONArray("v")
                list.add(Ref(
                    r.optString("src"), r.optString("sound"), r.optString("health"),
                    r.optBoolean("cough"),
                    DoubleArray(va.length()) { va.getDouble(it) }))
            }
            refs = list
        } catch (_: Throwable) {
            refs = emptyList()   // no bundled reference → feature disabled, no crash
        }
    }

    /** [8 DSP + 13 MFCC means] — identical to the desktop MetaAnalyzer.eventVector layout. */
    private fun eventVector(e: CoughEvent): DoubleArray {
        val base = e.featureVector()
        val m = e.mfcc?.mean ?: DoubleArray(0)
        val mfcc13 = DoubleArray(13) { if (it < m.size) m[it] else 0.0 }
        return base + mfcc13
    }

    data class Match(val label: String, val src: String, val distance: Double, val cough: Boolean)

    /** Nearest reference for a clip's PCM, or null if unavailable / no analysable events. */
    fun match(ctx: Context, pcm: FloatArray, sampleRate: Int): Match? {
        ensureLoaded(ctx)
        val mu = mean ?: return null
        val sd = std ?: return null
        if (refs.isEmpty() || mu.size != 21) return null
        val a = try { analyzer.analyze(pcm, sampleRate) } catch (_: Throwable) { return null }
        // Prefer cough-like events; fall back to all detected events.
        val events = a.events.filter { it.speech.isLikelyCough }.ifEmpty { a.events }
        if (events.isEmpty()) return null
        var best: Ref? = null
        var bestD = Double.MAX_VALUE
        for (e in events) {
            val z = CoughSimilarity.project(eventVector(e), mu, sd)
            for (r in refs) {
                if (r.v.size != z.size) continue
                val d = CoughSimilarity.euclidean(z, r.v)
                if (d < bestD) { bestD = d; best = r }
            }
        }
        val r = best ?: return null
        val label = listOf(r.sound, r.health)
            .filter { it.isNotBlank() && !it.equals("na", true) }
            .joinToString(" / ")
            .ifBlank { if (r.cough) "cough" else "sound" }
        return Match(label, r.src, bestD, r.cough)
    }

    /**
     * Match [pcm] and, unless a comment already exists, write the result as the clip's `.txt`
     * comment sidecar. Marked clearly as an automated guess (not ground truth), with the source and
     * the distance so confidence is visible.
     */
    fun annotate(ctx: Context, wav: File, pcm: FloatArray, sampleRate: Int) {
        val parent = wav.parentFile ?: return
        val txt = File(parent, wav.nameWithoutExtension + ".txt")
        if (txt.exists()) return   // never clobber a user/existing comment
        val m = match(ctx, pcm, sampleRate) ?: return
        val line = String.format(Locale.US, "auto-match: ≈ %s  [%s, d=%.2f]", m.label, m.src, m.distance)
        runCatching { txt.writeText(line) }
    }
}
