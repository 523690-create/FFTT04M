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

    private fun labelOf(r: Ref): String =
        listOf(r.sound, r.health)
            .filter { it.isNotBlank() && !it.equals("na", true) }
            .joinToString(" / ")
            .ifBlank { if (r.cough) "cough" else "sound" }

    /** Project a clip's cough-like (else all) events into the reference z-space; empty if none. */
    private fun eventVectors(ctx: Context, pcm: FloatArray, sampleRate: Int): List<DoubleArray> {
        ensureLoaded(ctx)
        val mu = mean ?: return emptyList()
        val sd = std ?: return emptyList()
        if (refs.isEmpty() || mu.size != 21) return emptyList()
        val a = try { analyzer.analyze(pcm, sampleRate) } catch (_: Throwable) { return emptyList() }
        val events = a.events.filter { it.speech.isLikelyCough }.ifEmpty { a.events }
        return events.map { CoughSimilarity.project(eventVector(it), mu, sd) }
    }

    /**
     * Top [n] **distinct-label** nearest references for a clip (closest distance per label), best
     * first. Distinct labels (vs. n raw neighbours) avoid three near-identical entries and give a
     * more useful shortlist; the spread of distances indicates confidence.
     */
    fun matchTop(ctx: Context, pcm: FloatArray, sampleRate: Int, n: Int = 3): List<Match> {
        val zs = eventVectors(ctx, pcm, sampleRate)
        if (zs.isEmpty()) return emptyList()
        val bestByLabel = HashMap<String, Match>()
        for (r in refs) {
            if (r.v.size != 21) continue
            var dmin = Double.MAX_VALUE
            for (z in zs) { val d = CoughSimilarity.euclidean(z, r.v); if (d < dmin) dmin = d }
            val label = labelOf(r)
            val cur = bestByLabel[label]
            if (cur == null || dmin < cur.distance) bestByLabel[label] = Match(label, r.src, dmin, r.cough)
        }
        return bestByLabel.values.sortedBy { it.distance }.take(n)
    }

    /** Single nearest match (top of [matchTop]); kept for callers that want just one. */
    fun match(ctx: Context, pcm: FloatArray, sampleRate: Int): Match? =
        matchTop(ctx, pcm, sampleRate, 1).firstOrNull()

    /**
     * Match [pcm] and, unless a comment already exists, write the TOP-3 matches as the clip's `.txt`
     * comment sidecar — an automated guess, not ground truth. Read the distances for confidence: a
     * tight #1 well below #2/#3 is a confident match; clustered or all-large distances are unreliable.
     */
    fun annotate(ctx: Context, wav: File, pcm: FloatArray, sampleRate: Int) {
        val parent = wav.parentFile ?: return
        val txt = File(parent, wav.nameWithoutExtension + ".txt")
        if (txt.exists()) {
            val existing = runCatching { txt.readText() }.getOrNull() ?: return
            if (!existing.startsWith("auto-match")) return        // user comment → never touch
            if (existing.startsWith("auto-match (top")) return    // already top-N → one-time upgrade done
            // else: an OLD single-line auto-match → upgrade it to top-3 below
        }
        val top = matchTop(ctx, pcm, sampleRate, 3)
        if (top.isEmpty()) return
        val sb = StringBuilder("auto-match (top ${top.size}):")
        top.forEachIndexed { i, m ->
            sb.append(String.format(Locale.US, "\n  %d) %s  [%s, d=%.2f]", i + 1, m.label, m.src, m.distance))
        }
        runCatching { txt.writeText(sb.toString()) }
    }
}
