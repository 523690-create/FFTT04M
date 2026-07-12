package com.example.FFTT04M.cough

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * On-device cough / not-cough VOTING head — fuses several independent, cheap signals into one calibrated
 * P(cough), trained IN-DOMAIN on the user's own labelled device clips (desktop `:desktop:deviceCoughGate`).
 *
 * Why a vote: the single ALLDATA-trained models over-fire on the USER's own voice (the OOD problem — see
 * [AutoReject]). Measured on 2711 labelled device clips (5-fold CV, @90% cough recall): the current
 * forest gate alone is 71% false-positive; the vote is 32% — the in-domain HuBERT head is the driver,
 * with the forest + DSP cues adding a bit and giving a DSP-only fallback (56%, still far below 71%).
 *
 * Two bundled assets:
 *   - `device_cough_head.json` : a 768→32→2 ReLU MLP over the whole-clip HuBERT embedding, trained on the
 *      user's clips (the strong in-domain method). Only usable when a HuBERT embedding is available.
 *   - `cough_vote.json` : the 7-feature logistic fuser
 *      [forestP, headP, pitch_strength, flatness, syllabic_mod, hf_ratio, crest] → P(cough). When there is
 *      no HuBERT embedding, `headP` is filled with its training mean (`headMean`) so the fuser degrades
 *      gracefully to a DSP-only vote.
 *
 * Missing/unparsable asset ⇒ [probability] returns null and callers fall back to their prior logic.
 */
object CoughVote {
    private const val HEAD_ASSET = "device_cough_head.json"
    private const val VOTE_ASSET = "cough_vote.json"
    // WholeClipFeatures indices used as cues (must match DeviceCoughGateCli.CUE_IDX):
    // 11 pitch_strength, 6 flatness, 12 syllabic_mod, 9 hf_ratio, 0 crest
    private val CUE_IDX = intArrayOf(11, 6, 12, 9, 0)

    @Volatile private var loaded = false

    // in-domain head (MLP)
    private var hMean: FloatArray? = null
    private var hStd: FloatArray? = null
    private var w1: Array<FloatArray>? = null   // [hidden][768]
    private var b1: FloatArray? = null
    private var w2: Array<FloatArray>? = null   // [class][hidden]
    private var b2: FloatArray? = null
    private var headCoughIdx = 1

    // fuser (LR)
    private var fMean: FloatArray? = null
    private var fStd: FloatArray? = null
    private var fw: Array<FloatArray>? = null    // [class][7]
    private var fb: FloatArray? = null
    private var fCoughIdx = 1
    private var headMean = 0.5f

    val isReady: Boolean get() = fw != null

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        // fuser (required)
        try {
            val o = JSONObject(ctx.assets.open(VOTE_ASSET).bufferedReader().use { it.readText() })
            fMean = o.getJSONArray("mean").toFloatArray()
            fStd = o.getJSONArray("std").toFloatArray()
            val wa = o.getJSONArray("w"); fw = Array(wa.length()) { wa.getJSONArray(it).toFloatArray() }
            fb = o.getJSONArray("b").toFloatArray()
            headMean = o.optDouble("headMean", 0.5).toFloat()
            fCoughIdx = coughIndexOf(o.optJSONArray("classes"))
        } catch (_: Throwable) { fw = null }
        // in-domain head (optional — only used when a HuBERT embedding is present)
        try {
            val o = JSONObject(ctx.assets.open(HEAD_ASSET).bufferedReader().use { it.readText() })
            hMean = o.getJSONArray("mean").toFloatArray()
            hStd = o.getJSONArray("std").toFloatArray()
            val a1 = o.getJSONArray("w1"); w1 = Array(a1.length()) { a1.getJSONArray(it).toFloatArray() }
            b1 = o.getJSONArray("b1").toFloatArray()
            val a2 = o.getJSONArray("w2"); w2 = Array(a2.length()) { a2.getJSONArray(it).toFloatArray() }
            b2 = o.getJSONArray("b2").toFloatArray()
            headCoughIdx = coughIndexOf(o.optJSONArray("classes"))
        } catch (_: Throwable) { w1 = null }
    }

    private fun coughIndexOf(classes: JSONArray?): Int {
        if (classes != null) for (i in 0 until classes.length())
            if (classes.getString(i).equals("cough", true)) return i
        return 1
    }

    private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }

    /** In-domain head P(cough) for a whole-clip HuBERT embedding, or null if the head/embedding is absent. */
    fun deviceHeadP(emb: FloatArray): Float? {
        val mu = hMean ?: return null; val sd = hStd ?: return null
        val a1 = w1 ?: return null; val c1 = b1 ?: return null; val a2 = w2 ?: return null; val c2 = b2 ?: return null
        if (emb.size != mu.size) return null
        val z = FloatArray(emb.size) { (emb[it] - mu[it]) / sd[it] }
        val h = FloatArray(c1.size) { j -> var s = c1[j]; val wj = a1[j]; for (k in z.indices) s += wj[k] * z[k]; if (s > 0f) s else 0f }
        val logits = FloatArray(c2.size) { k -> var s = c2[k]; val wk = a2[k]; for (j in h.indices) s += wk[j] * h[j]; s }
        return softmaxAt(logits, headCoughIdx)
    }

    /**
     * Fused P(cough) in [0,1] for a captured clip, or null if the fuser asset is unavailable.
     * [emb] is the whole-clip HuBERT embedding when the clip was decoded on the HuBERT path (null on the
     * DSP path → the head vote is filled with its training mean).
     */
    fun probability(ctx: Context, pcm: FloatArray, sampleRate: Int, emb: FloatArray?): Float? {
        ensureLoaded(ctx)
        val mu = fMean ?: return null; val sd = fStd ?: return null; val ww = fw ?: return null; val bb = fb ?: return null
        val forestP = CoughClassifier.coughProb(pcm, sampleRate).let { if (it < 0.0) 0.5f else it.toFloat() }
        val headP = emb?.let { deviceHeadP(it) } ?: headMean
        val wcf = WholeClipFeatures.extract(pcm, sampleRate)
        val x = floatArrayOf(
            forestP, headP,
            wcf[CUE_IDX[0]].toFloat(), wcf[CUE_IDX[1]].toFloat(), wcf[CUE_IDX[2]].toFloat(),
            wcf[CUE_IDX[3]].toFloat(), wcf[CUE_IDX[4]].toFloat())
        if (x.size != mu.size) return null
        val z = FloatArray(x.size) { (x[it] - mu[it]) / sd[it] }
        val logits = FloatArray(ww.size) { k -> var s = bb[k]; val wk = ww[k]; for (j in z.indices) s += wk[j] * z[j]; s }
        return softmaxAt(logits, fCoughIdx)
    }

    private fun softmaxAt(logits: FloatArray, idx: Int): Float {
        val mx = logits.max()
        var sum = 0f; val p = FloatArray(logits.size) { val e = exp((logits[it] - mx).toDouble()).toFloat(); sum += e; e }
        return p[idx] / sum
    }
}
