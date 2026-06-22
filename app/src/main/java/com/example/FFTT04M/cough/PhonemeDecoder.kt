package com.example.FFTT04M.cough

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device phoneme decode — the same supervised fragment-level codebook the desktop builds
 * (PHONEME_CODEBOOK.md / ON_DEVICE_CODEBOOK_PLAN.md), run here on the phone.
 *
 * A clip is Spectral-Flux **fractionated** into sub-segments; each fragment is featurised with the
 * SAME 13-dim vector the codebook was trained on (`WholeClipFeatures` minus `syllabic`, keep
 * `spectral_crest`), z-normalised with the codebook's mean/std, and assigned to its nearest phoneme
 * centroid (`?` when beyond that phoneme's radius). Output: a **word** (ordered codes) + the inferred
 * class. `WholeClipFeatures` is byte-identical to the desktop's, so the codebook centroids apply
 * unchanged.
 *
 * Codebook asset `codebook.json` (the desktop's <tag>_phonemes.json):
 *   { "norm": { "mean":[13], "std":[13] },
 *     "phonemes": [ { "code":"S3", "letter":"S", "label":"snoring", "centroid":[13], "radius":d }, … ] }
 * Missing/unparsable asset ⇒ decoding silently disabled (no crash).
 */
object PhonemeDecoder {

    private const val SHORT_MIN = 256          // skip fragments shorter than this many samples

    private data class Phoneme(
        val code: String, val letter: String, val label: String,
        val centroid: DoubleArray, val radius: Double,
    )

    @Volatile private var loaded = false
    private var mean: DoubleArray? = null
    private var std: DoubleArray? = null
    private var phonemes: List<Phoneme> = emptyList()
    private var labelByLetter: Map<String, String> = emptyMap()

    val isReady: Boolean get() = phonemes.isNotEmpty()

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val txt = ctx.assets.open("codebook.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            val norm = o.getJSONObject("norm")
            mean = norm.getJSONArray("mean").toDoubleArray()
            std = norm.getJSONArray("std").toDoubleArray()
            val pa = o.getJSONArray("phonemes")
            val list = ArrayList<Phoneme>(pa.length())
            for (i in 0 until pa.length()) {
                val p = pa.getJSONObject(i)
                list.add(Phoneme(
                    p.getString("code"), p.getString("letter"), p.getString("label"),
                    p.getJSONArray("centroid").toDoubleArray(), p.getDouble("radius")))
            }
            phonemes = list
            labelByLetter = list.associate { it.letter to it.label }
        } catch (_: Throwable) {
            phonemes = emptyList()   // no bundled codebook → feature disabled, no crash
        }
    }

    private fun JSONArray.toDoubleArray() = DoubleArray(length()) { getDouble(it) }

    /** word = ordered phoneme codes; letter/label = the dominant class by non-`?` count. */
    data class Decoded(val letter: String, val label: String, val word: List<String>)

    fun decode(ctx: Context, pcm: FloatArray, sr: Int): Decoded? {
        ensureLoaded(ctx)
        val mu = mean ?: return null
        val sd = std ?: return null
        if (phonemes.isEmpty() || mu.size != 13) return null
        val x = pcm.copyOf().also { rmsNormalize(it) }   // match the codebook's RMS-normalised training
        val word = ArrayList<String>()
        for ((sMs, eMs) in fractionate(x, sr)) {
            val v = fragVec(x, sr, sMs, eMs)
            if (v == null) { word.add("?"); continue }
            for (i in 0 until 13) v[i] = (v[i] - mu[i]) / sd[i]
            var best: Phoneme? = null; var bestD = Double.MAX_VALUE
            for (p in phonemes) { val d = dist(v, p.centroid); if (d < bestD) { bestD = d; best = p } }
            word.add(if (best != null && bestD <= best.radius) best.code else "?")
        }
        val letter = word.asSequence().filter { it != "?" }.map { it.takeWhile { c -> c.isLetter() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "?"
        return Decoded(letter, labelByLetter[letter] ?: "?", word)
    }

    /** Decode [pcm] and write the result to `<wav>.phon` (JSON). Never touches the user's `.txt`. */
    fun annotate(ctx: Context, wav: File, pcm: FloatArray, sr: Int) {
        val d = decode(ctx, pcm, sr) ?: return
        val out = File(wav.parentFile, wav.nameWithoutExtension + ".phon")
        val o = JSONObject()
            .put("letter", d.letter).put("label", d.label).put("word", JSONArray(d.word))
        runCatching { out.writeText(o.toString()) }
    }

    /** Read a previously-written `<base>.phon` for display, or null. */
    fun read(dir: File, base: String): Decoded? {
        val f = File(dir, "$base.phon")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val wa = o.optJSONArray("word")
            val word = if (wa != null) List(wa.length()) { wa.getString(it) } else emptyList()
            Decoded(o.optString("letter", "?"), o.optString("label", "?"), word)
        } catch (_: Throwable) { null }
    }

    // ---- 13-dim fragment feature (WholeClipFeatures[14] minus syllabic; keep spectral_crest) ------
    private fun fragVec(pcm: FloatArray, sr: Int, sMs: Int, eMs: Int): DoubleArray? {
        val s = (sMs / 1000.0 * sr).toInt().coerceIn(0, pcm.size)
        val e = (eMs / 1000.0 * sr).toInt().coerceIn(s, pcm.size)
        if (e - s < SHORT_MIN) return null
        val full = WholeClipFeatures.extract(pcm.copyOfRange(s, e), sr)   // 14-dim
        val v = DoubleArray(13)
        for (i in 0..11) v[i] = full[i]
        v[12] = full[13]                                                 // spectral_crest; drop full[12]=syllabic
        for (i in 0 until 13) if (!v[i].isFinite()) v[i] = 0.0
        return v
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0; for (i in a.indices) { val d = a[i] - b[i]; s += d * d }; return sqrt(s)
    }

    /** Scale the whole clip to a target RMS (matches the codebook's training) so device mic-gain
     *  differences (Pixel 10 ≈ 8× the Pixel 3a) don't shift level-sensitive features. */
    private fun rmsNormalize(pcm: FloatArray, target: Float = 0.1f) {
        var s = 0.0; for (x in pcm) s += x.toDouble() * x
        val rms = sqrt(s / pcm.size.coerceAtLeast(1))
        if (rms > 1e-5) { val g = (target / rms).toFloat(); for (i in pcm.indices) pcm[i] *= g }
    }

    // ---- Fixed-grid overlapping windows (matches the desktop codebook: deterministic fragmentation,
    //      no onset-count variance; 50% overlap absorbs frame-shifts). ----
    private const val WIN_MS = 180
    private const val HOP_MS = 90
    private fun fractionate(x: FloatArray, sr: Int): List<Pair<Int, Int>> {
        val durMs = (x.size.toLong() * 1000 / sr).toInt()
        if (durMs <= WIN_MS) return if (durMs > 0) listOf(0 to durMs) else emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var s = 0
        while (s < durMs) {
            val e = (s + WIN_MS).coerceAtMost(durMs)
            if (e - s >= WIN_MS / 2) out.add(s to e)
            if (e >= durMs) break
            s += HOP_MS
        }
        return out
    }
}
