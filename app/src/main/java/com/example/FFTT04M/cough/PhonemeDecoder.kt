package com.example.FFTT04M.cough

import android.content.Context
import com.example.FFTT04M.DeviceCaps
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
 * Two codebooks ship; the device picks one in [ensureLoaded]:
 *   - `codebook_hubert.json` (768-dim, the 89%-CV gold standard) on RAM-capable phones, paired with
 *     the bundled HuBERT model — features come from [HubertFeatures] instead of the DSP path.
 *   - `codebook.json` (13-dim DSP) everywhere else, and as the fallback if the model won't load.
 * Both share the schema:
 *   { "featureType": "hubert768"|"dsp13", "norm": { "mean":[D], "std":[D] },
 *     "phonemes": [ { "code":"S3", "letter":"S", "label":"snoring", "centroid":[D], "radius":d }, … ] }
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
    private var useHubert = false               // true → 768-dim HuBERT codebook; false → 13-dim DSP

    val isReady: Boolean get() = phonemes.isNotEmpty()
    /** "hubert768" (gold-standard path) or "dsp13" (fallback) — which codebook this device loaded. */
    val featureType: String get() = if (useHubert) "hubert768" else "dsp13"

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        // Prefer the HuBERT gold-standard codebook on capable phones, but only if the model actually
        // loads (RAM floor + bundled asset present); otherwise fall back to the always-bundled DSP one.
        if (DeviceCaps.hubertAllowed(ctx) && loadCodebook(ctx, "codebook_hubert.json")) {
            if (HubertFeatures.ensureLoaded(ctx)) {
                useHubert = true
            } else {
                clearCodebook(); loadCodebook(ctx, "codebook.json")   // model wouldn't load → DSP
            }
        } else {
            loadCodebook(ctx, "codebook.json")
        }
    }

    /** Parse a codebook asset into the active fields. Returns true on a usable (non-empty) codebook. */
    private fun loadCodebook(ctx: Context, asset: String): Boolean = try {
        val o = JSONObject(ctx.assets.open(asset).bufferedReader().use { it.readText() })
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
        list.isNotEmpty()
    } catch (_: Throwable) {
        clearCodebook(); false   // missing/unparsable asset → feature disabled, no crash
    }

    private fun clearCodebook() { phonemes = emptyList(); mean = null; std = null; labelByLetter = emptyMap() }

    private fun JSONArray.toDoubleArray() = DoubleArray(length()) { getDouble(it) }

    /** word = ordered phoneme codes; letter/label = the dominant class by non-`?` count. [emb] is the
     *  whole-clip mean HuBERT embedding (768-dim) when decoded via HuBERT — cached for the clip-cloud
     *  view so it need not re-run the model; null on the DSP path. */
    data class Decoded(val letter: String, val label: String, val word: List<String>, val emb: FloatArray? = null,
                       val confidence: Double = 0.0,
                       // Cached CoughVote scores (see CoughVote.Breakdown) so the gallery/ground-truth lists
                       // can show every vote without recomputing. Null until computed for a clip.
                       val forestP: Float? = null, val headP: Float? = null, val voteP: Float? = null,
                       // True when the clip contains BOTH a cough and a voice span (see MixedClipBreaker).
                       val mixed: Boolean = false)

    fun decode(ctx: Context, pcm: FloatArray, sr: Int): Decoded? {
        ensureLoaded(ctx)
        val mu = mean ?: return null
        val sd = std ?: return null
        if (phonemes.isEmpty()) return null
        val dim = mu.size
        val x = pcm.copyOf().also { rmsNormalize(it) }   // match the codebook's RMS-normalised training
        val frags = fractionate(x, sr)
        // Per-window feature vectors. HuBERT path: one model pass → mean-pool frames per window
        // (768-dim), returns null whole-clip on inference failure → null decode. DSP path: 13-dim
        // fragVec per window (null for too-short windows → "?").
        var clipEmb: FloatArray? = null
        val vecs: List<DoubleArray?> = if (useHubert) {
            val emb = HubertFeatures.frameEmbeddings(ctx, x, sr) ?: return null
            if (emb.isEmpty()) return null
            clipEmb = meanFrames(emb)                       // whole-clip embedding for the cloud
            hubertWindowVecs(emb, x.size, sr, frags)
        } else {
            frags.map { (sMs, eMs) -> fragVec(x, sr, sMs, eMs) }
        }
        val word = ArrayList<String>(vecs.size)
        for (v in vecs) {
            if (v == null || v.size != dim) { word.add("?"); continue }
            val z = DoubleArray(dim) { (v[it] - mu[it]) / sd[it] }
            var best: Phoneme? = null; var bestD = Double.MAX_VALUE
            for (p in phonemes) { val d = dist(z, p.centroid); if (d < bestD) { bestD = d; best = p } }
            word.add(if (best != null && bestD <= best.radius) best.code else "?")
        }
        val letterCounts = word.asSequence().filter { it != "?" }.map { it.takeWhile { c -> c.isLetter() } }
            .groupingBy { it }.eachCount()
        val nonQ = letterCounts.values.sum()
        val dom = letterCounts.maxByOrNull { it.value }
        val letter = dom?.key ?: "?"
        // Confidence = the dominant letter's share of the assigned (non-?) windows.
        val confidence = if (nonQ > 0 && dom != null) dom.value.toDouble() / nonQ else 0.0
        return Decoded(letter, labelByLetter[letter] ?: "?", word, clipEmb, confidence)
    }

    /** HuBERT per-window features: mean-pool the frames falling in each fixed-grid window (768-dim),
     *  byte-for-byte the same pooling the desktop codebook build uses. */
    private fun hubertWindowVecs(emb: Array<FloatArray>, nSamples: Int, sr: Int, frags: List<Pair<Int, Int>>): List<DoubleArray> {
        val t = emb.size; val h = emb[0].size
        val durMs = (nSamples.toLong() * 1000 / sr).toInt().coerceAtLeast(1)
        val msPerFrame = durMs.toDouble() / t
        return frags.map { (sMs, eMs) ->
            val f0 = (sMs / msPerFrame).toInt().coerceIn(0, t - 1)
            val f1 = (eMs / msPerFrame).toInt().coerceIn(f0 + 1, t)
            val v = DoubleArray(h); var n = 0
            for (f in f0 until f1) { val ef = emb[f]; for (j in 0 until h) v[j] += ef[j]; n++ }
            if (n > 0) for (j in 0 until h) v[j] /= n
            v
        }
    }

    /** Whole-clip embedding = mean over all HuBERT frames (matches BronchitisCloud's per-clip vector). */
    private fun meanFrames(emb: Array<FloatArray>): FloatArray {
        val h = emb[0].size; val v = FloatArray(h)
        for (f in emb) for (j in 0 until h) v[j] += f[j]
        for (j in 0 until h) v[j] /= emb.size
        return v
    }

    /** Whole-clip HuBERT embedding for the cloud (or null on DSP / failure). Reuses the cached `.phon`
     *  embedding when present so the model isn't re-run; otherwise decodes once (which caches it). */
    fun clipEmbedding(ctx: Context, wav: File, pcm: FloatArray, sr: Int): FloatArray? {
        read(wav.parentFile ?: return null, wav.nameWithoutExtension)?.emb?.let { return it }
        val d = decode(ctx, pcm, sr) ?: return null
        annotate(ctx, wav, pcm, sr)   // persist (incl. emb) so next open is free
        return d.emb
    }

    /** Decode [pcm] and write the result to `<wav>.phon` (JSON). Never touches the user's `.txt`.
     *  On the HuBERT path also stores the whole-clip embedding ("emb") for the clip-cloud view. */
    fun annotate(ctx: Context, wav: File, pcm: FloatArray, sr: Int): Decoded? {
        val d = decode(ctx, pcm, sr) ?: return null
        // Compute every CoughVote score once here (pcm + emb both in hand) and cache it in the .phon,
        // so the gallery / ground-truth lists can show all votes without re-running anything.
        val bd = runCatching { CoughVote.breakdown(ctx, pcm, sr, d.emb) }.getOrNull()
        val mixed = runCatching { MixedClipBreaker.isMixed(MixedClipBreaker.components(d.word, windowSpans(pcm, sr))) }.getOrDefault(false)
        val full = d.copy(forestP = bd?.forestP, headP = bd?.headP, voteP = bd?.voteP, mixed = mixed)
        writePhon(wav, full)
        return full
    }

    /** Write a [Decoded] (incl. emb + cached vote scores + mixed flag) to `<wav>.phon`. Never touches `.txt`. */
    private fun writePhon(wav: File, d: Decoded) {
        val out = File(wav.parentFile, wav.nameWithoutExtension + ".phon")
        val o = JSONObject()
            .put("letter", d.letter).put("label", d.label).put("word", JSONArray(d.word))
        d.emb?.let { e -> o.put("emb", JSONArray().apply { for (v in e) put(v.toDouble()) }) }
        d.forestP?.let { o.put("forestP", it.toDouble()) }
        d.headP?.let { o.put("headP", it.toDouble()) }
        d.voteP?.let { o.put("voteP", it.toDouble()) }
        if (d.mixed) o.put("mixed", true)
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
            val ea = o.optJSONArray("emb")
            val emb = if (ea != null) FloatArray(ea.length()) { ea.getDouble(it).toFloat() } else null
            Decoded(o.optString("letter", "?"), o.optString("label", "?"), word, emb,
                forestP = if (o.has("forestP")) o.getDouble("forestP").toFloat() else null,
                headP = if (o.has("headP")) o.getDouble("headP").toFloat() else null,
                voteP = if (o.has("voteP")) o.getDouble("voteP").toFloat() else null,
                mixed = o.optBoolean("mixed", false))
        } catch (_: Throwable) { null }
    }

    /** Cheap "is this clip mixed?" check that reads the `.phon` TEXT and looks for the flag, WITHOUT
     *  JSON-parsing the (768-float) embedding — safe to call over a whole gallery. Still do it off the main
     *  thread for large galleries (file I/O). */
    fun isMixedQuick(dir: File, base: String): Boolean {
        val f = File(dir, "$base.phon")
        if (!f.isFile) return false
        return runCatching { f.readText().contains("\"mixed\":true") }.getOrDefault(false)
    }

    /** The (startMs,endMs) window grid [decode] uses — aligned 1:1 with a [Decoded.word], so a word code
     *  and its time span share an index. Used by [MixedClipBreaker] to map decoded classes back to time. */
    fun windowSpans(pcm: FloatArray, sr: Int): List<Pair<Int, Int>> = fractionate(pcm, sr)

    /** Compact one-line summary of every cached vote score (forest / in-domain head / fused), or null
     *  if none are cached yet. Shown per-clip in the gallery + ground-truth lists. */
    fun votesLine(d: Decoded?): String? {
        val v = d?.voteP ?: return null
        val sb = StringBuilder("votes  forest ").append(fmtP(d.forestP))
        d.headP?.let { sb.append(" · head ").append(fmtP(it)) }
        return sb.append(" · fused ").append(fmtP(v)).toString()
    }
    private fun fmtP(x: Float?) = if (x == null) "–" else String.format("%.2f", x)

    /** Backfill the CoughVote scores into an already-decoded clip's `.phon` WITHOUT re-running HuBERT:
     *  reuses the cached whole-clip embedding, recomputes only the cheap forest + DSP cues from [pcm].
     *  No-op (returns the input) if the scores are already present or the fuser is unavailable. */
    fun ensureVotes(ctx: Context, wav: File, pcm: FloatArray, sr: Int, d: Decoded): Decoded {
        if (d.voteP != null) return d
        val bd = runCatching { CoughVote.breakdown(ctx, pcm, sr, d.emb) }.getOrNull() ?: return d
        val mixed = runCatching { MixedClipBreaker.isMixed(MixedClipBreaker.components(d.word, windowSpans(pcm, sr))) }.getOrDefault(false)
        val full = d.copy(forestP = bd.forestP, headP = bd.headP, voteP = bd.voteP, mixed = mixed)
        writePhon(wav, full)
        return full
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
