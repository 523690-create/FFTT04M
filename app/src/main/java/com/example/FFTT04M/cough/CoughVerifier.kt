package com.example.FFTT04M.cough

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * Purpose-built cough / not-cough head — a 768→2 class-balanced logistic classifier trained on the
 * desktop over HuBERT whole-clip embeddings (`cough_head.json`: mean/std + weights). Runs on the exact
 * whole-clip embedding PhonemeDecoder already computes on the HuBERT path, so verification is a single
 * z-norm + dot product — effectively free once the clip is decoded.
 *
 * This sharpens AutoReject: instead of inferring "background" from the multi-class dominant-letter rule,
 * it asks the trained head directly for P(cough). Measured ~F1 0.81 (cough vs voice/noise/breathing).
 * Missing/unparsable asset ⇒ returns null and callers fall back to the letter rule.
 */
object CoughVerifier {
    private const val ASSET = "cough_head.json"

    @Volatile private var loaded = false
    private var mean: FloatArray? = null
    private var std: FloatArray? = null
    private var w: Array<FloatArray>? = null   // [class][768]
    private var b: FloatArray? = null
    private var coughIdx = 1

    val isReady: Boolean get() = mean != null

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
            mean = o.getJSONArray("mean").toFloatArray()
            std = o.getJSONArray("std").toFloatArray()
            val wa = o.getJSONArray("w")
            w = Array(wa.length()) { wa.getJSONArray(it).toFloatArray() }
            b = o.getJSONArray("b").toFloatArray()
            val classes = o.optJSONArray("classes")
            if (classes != null) for (i in 0 until classes.length())
                if (classes.getString(i).equals("cough", true)) coughIdx = i
        } catch (_: Throwable) {
            mean = null   // no bundled head → feature disabled, callers fall back
        }
    }

    private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }

    /** P(cough) in [0,1] for a whole-clip HuBERT embedding, or null if the head/embedding is unavailable. */
    fun coughProbability(ctx: Context, emb: FloatArray): Float? {
        ensureLoaded(ctx)
        val mu = mean ?: return null; val sd = std ?: return null
        val ww = w ?: return null; val bb = b ?: return null
        if (emb.size != mu.size) return null
        val z = FloatArray(emb.size) { (emb[it] - mu[it]) / sd[it] }
        val logits = FloatArray(ww.size) { k -> var s = bb[k]; val wk = ww[k]; for (j in z.indices) s += wk[j] * z[j]; s }
        val mx = logits.max()
        var sum = 0f; val p = FloatArray(logits.size) { val e = exp((logits[it] - mx).toDouble()).toFloat(); sum += e; e }
        return p[coughIdx] / sum
    }
}
