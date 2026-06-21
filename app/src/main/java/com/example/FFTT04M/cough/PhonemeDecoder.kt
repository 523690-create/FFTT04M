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
        val word = ArrayList<String>()
        val letterWeight = HashMap<String, Double>()   // class vote weighted by fragment DURATION, not count
        for ((sMs, eMs) in fractionate(pcm, sr)) {
            val v = fragVec(pcm, sr, sMs, eMs)
            val code = if (v == null) "?" else {
                for (i in 0 until 13) v[i] = (v[i] - mu[i]) / sd[i]
                var best: Phoneme? = null; var bestD = Double.MAX_VALUE
                for (p in phonemes) { val d = dist(v, p.centroid); if (d < bestD) { bestD = d; best = p } }
                if (best != null && bestD <= best.radius) best.code else "?"
            }
            word.add(code)
            if (code != "?") { val l = code.takeWhile { it.isLetter() }; letterWeight[l] = (letterWeight[l] ?: 0.0) + (eMs - sMs) }
        }
        val letter = letterWeight.maxByOrNull { it.value }?.key ?: "?"
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

    // ---- Spectral Flux Onset fractionation (ported from desktop SpectralFluxOnset) ----------------
    private fun fractionate(x: FloatArray, sr: Int): List<Pair<Int, Int>> {
        if (x.isEmpty()) return emptyList()
        val frameMs = 25.0; val hopMs = 10.0; val sensitivity = 1.2f
        val minOnsetMs = 150; val smoothWin = 5; val historyFrames = 150
        val win = max(8, (frameMs / 1000 * sr).roundToInt())
        val hop = max(1, (hopMs / 1000 * sr).roundToInt())
        val fftSize = nextPow2(win)
        val half = fftSize / 2
        val minHold = max(1, (minOnsetMs / hopMs).roundToInt())
        val numFrames = max(0, (x.size - win) / hop + 1)
        val wholeMs = (x.size.toDouble() / sr * 1000).roundToInt()
        if (numFrames < 2) return listOf(0 to wholeMs)

        val mags = Array(numFrames) { f ->
            CoughDsp.magnitudeSpectrum(FloatArray(win) { x[f * hop + it] }, fftSize, true)
        }
        val flux = FloatArray(numFrames)
        for (i in 1 until numFrames) {
            val prev = mags[i - 1]; val cur = mags[i]; var sf = 0f
            for (k in 0 until half) { val d = cur[k] - prev[k]; if (d > 0) sf += d }
            flux[i] = sf
        }
        val smooth = FloatArray(numFrames)
        for (i in flux.indices) {
            val a = max(0, i - smoothWin / 2); val b = min(numFrames, i + smoothWin / 2 + 1)
            var s = 0f; for (j in a until b) s += flux[j]; smooth[i] = s / (b - a)
        }
        val onsets = ArrayList<Int>(); var hold = 0
        for (i in 1 until smooth.size) {
            if (hold > 0) { hold--; continue }
            val thr = CoughDsp.median(smooth.copyOfRange(max(0, i - historyFrames), i)) * sensitivity
            if (smooth[i] > thr && smooth[i] > smooth[i - 1]) { onsets.add(i); hold = minHold }
        }
        if (onsets.isEmpty()) return listOf(0 to wholeMs)

        val boundaries = listOf(0) + onsets + listOf(numFrames)
        val segs = ArrayList<Pair<Int, Int>>(boundaries.size)
        for (b in 0 until boundaries.size - 1) {
            val startMs = (boundaries[b] * hop.toDouble() / sr * 1000).roundToInt()
            val endSample = (boundaries[b + 1] * hop + win).coerceAtMost(x.size)
            val endMs = (endSample.toDouble() / sr * 1000).roundToInt()
            if (endMs > startMs) segs.add(startMs to endMs)
        }
        return segs
    }

    private fun nextPow2(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }
}
