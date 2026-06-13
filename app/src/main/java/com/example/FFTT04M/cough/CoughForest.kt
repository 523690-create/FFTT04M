package com.example.FFTT04M.cough

import kotlin.math.sqrt

/**
 * Serializable random forest for the cough/not-cough verdict on a [WholeClipFeatures] vector.
 * Pure (DoubleArray in → probability out), so the SAME model file runs identically on desktop and
 * mobile. Trained on ALLDATA: cough vs speech/crying/non-human negatives (sneezing/breathing/snoring
 * are "don't care" and excluded). See memory `cough-detection-redesign`.
 *
 * Tree nodes are parallel flat arrays per tree: internal node has `feat>=0` and splits on
 * `x[feat] <= thr`; a leaf has `feat<0` and carries `prob`.
 */
class CoughForest internal constructor(
    private val feat: Array<IntArray>,
    private val thr: Array<FloatArray>,
    private val left: Array<IntArray>,
    private val right: Array<IntArray>,
    private val prob: Array<FloatArray>,
    val nFeatures: Int,
    val threshold: Double,
) {
    /** Mean cough probability across the trees. */
    fun coughProb(x: DoubleArray): Double {
        var s = 0.0
        for (t in feat.indices) {
            var n = 0
            while (feat[t][n] >= 0) n = if (x[feat[t][n]] <= thr[t][n]) left[t][n] else right[t][n]
            s += prob[t][n]
        }
        return s / feat.size
    }

    fun isCough(x: DoubleArray): Boolean = coughProb(x) >= threshold

    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("COUGHFOREST v1\n")
        sb.append("nFeatures $nFeatures\n")
        sb.append("threshold $threshold\n")
        sb.append("nTrees ${feat.size}\n")
        for (t in feat.indices) {
            sb.append("T ${feat[t].size}\n")
            for (n in feat[t].indices)
                sb.append("${feat[t][n]} ${thr[t][n]} ${left[t][n]} ${right[t][n]} ${prob[t][n]}\n")
        }
        return sb.toString()
    }

    companion object {
        fun parse(text: String): CoughForest {
            val lines = text.split("\n").iterator()
            require(lines.next().startsWith("COUGHFOREST")) { "bad header" }
            val nF = lines.next().substringAfter(' ').trim().toInt()
            val thrv = lines.next().substringAfter(' ').trim().toDouble()
            val nT = lines.next().substringAfter(' ').trim().toInt()
            val feat = ArrayList<IntArray>(); val thr = ArrayList<FloatArray>()
            val left = ArrayList<IntArray>(); val right = ArrayList<IntArray>(); val prob = ArrayList<FloatArray>()
            repeat(nT) {
                val cnt = lines.next().substringAfter(' ').trim().toInt()
                val f = IntArray(cnt); val th = FloatArray(cnt); val l = IntArray(cnt); val r = IntArray(cnt); val p = FloatArray(cnt)
                for (n in 0 until cnt) {
                    val parts = lines.next().split(' ')
                    f[n] = parts[0].toInt(); th[n] = parts[1].toFloat()
                    l[n] = parts[2].toInt(); r[n] = parts[3].toInt(); p[n] = parts[4].toFloat()
                }
                feat.add(f); thr.add(th); left.add(l); right.add(r); prob.add(p)
            }
            return CoughForest(feat.toTypedArray(), thr.toTypedArray(), left.toTypedArray(),
                right.toTypedArray(), prob.toTypedArray(), nF, thrv)
        }

        /** Load the bundled gzipped model from the classpath (`/cough_forest.txt.gz`), or null. */
        fun loadBundled(): CoughForest? =
            CoughForest::class.java.getResourceAsStream("/cough_forest.txt.gz")?.use {
                parse(java.util.zip.GZIPInputStream(it).bufferedReader().readText())
            }

        // ---- training -------------------------------------------------------------------------
        private class TA {
            val f = ArrayList<Int>(); val t = ArrayList<Float>()
            val l = ArrayList<Int>(); val r = ArrayList<Int>(); val p = ArrayList<Float>()
            fun leaf(prob: Double): Int { f.add(-1); t.add(0f); l.add(-1); r.add(-1); p.add(prob.toFloat()); return f.size - 1 }
            fun internal(): Int { f.add(0); t.add(0f); l.add(-1); r.add(-1); p.add(0f); return f.size - 1 }
        }

        fun train(X: Array<DoubleArray>, y: IntArray, nFeatures: Int, threshold: Double,
                  nTrees: Int = 150, maxDepth: Int = 14, minLeaf: Int = 4, seed: Long = 42): CoughForest {
            val rnd = java.util.Random(seed)
            val mtry = maxOf(1, sqrt(nFeatures.toDouble()).toInt())
            val feat = ArrayList<IntArray>(); val thr = ArrayList<FloatArray>()
            val left = ArrayList<IntArray>(); val right = ArrayList<IntArray>(); val prob = ArrayList<FloatArray>()
            repeat(nTrees) {
                val ta = TA()
                val idx = (0 until X.size).map { rnd.nextInt(X.size) }
                build(ta, X, y, idx, 0, mtry, maxDepth, minLeaf, rnd)
                feat.add(ta.f.toIntArray()); thr.add(ta.t.toFloatArray())
                left.add(ta.l.toIntArray()); right.add(ta.r.toIntArray()); prob.add(ta.p.toFloatArray())
            }
            return CoughForest(feat.toTypedArray(), thr.toTypedArray(), left.toTypedArray(),
                right.toTypedArray(), prob.toTypedArray(), nFeatures, threshold)
        }

        private fun build(ta: TA, X: Array<DoubleArray>, y: IntArray, idx: List<Int>,
                          depth: Int, mtry: Int, maxDepth: Int, minLeaf: Int, rnd: java.util.Random): Int {
            val posCnt = idx.count { y[it] == 1 }; val p = posCnt.toDouble() / idx.size
            if (depth >= maxDepth || idx.size <= minLeaf || posCnt == 0 || posCnt == idx.size) return ta.leaf(p)
            val d = X[0].size
            val feats = (0 until d).toMutableList().also { java.util.Collections.shuffle(it, rnd) }.take(mtry)
            var bestGini = Double.MAX_VALUE; var bf = -1; var bt = 0.0
            for (f in feats) {
                val vals = idx.map { X[it][f] }.sorted()
                val cands = (1..16).map { vals[(it.toLong() * vals.size / 17).toInt()] }.distinct()
                for (cthr in cands) {
                    var lp = 0; var ln = 0; var rp = 0; var rn = 0
                    for (i in idx) if (X[i][f] <= cthr) { if (y[i] == 1) lp++ else ln++ } else { if (y[i] == 1) rp++ else rn++ }
                    val nL = lp + ln; val nR = rp + rn; if (nL == 0 || nR == 0) continue
                    val gL = 1 - (lp.toDouble() / nL).let { it * it } - (ln.toDouble() / nL).let { it * it }
                    val gR = 1 - (rp.toDouble() / nR).let { it * it } - (rn.toDouble() / nR).let { it * it }
                    val g = (nL * gL + nR * gR) / idx.size
                    if (g < bestGini) { bestGini = g; bf = f; bt = cthr }
                }
            }
            if (bf < 0) return ta.leaf(p)
            val li = idx.filter { X[it][bf] <= bt }; val ri = idx.filter { X[it][bf] > bt }
            if (li.isEmpty() || ri.isEmpty()) return ta.leaf(p)
            val id = ta.internal()
            val lc = build(ta, X, y, li, depth + 1, mtry, maxDepth, minLeaf, rnd)
            val rc = build(ta, X, y, ri, depth + 1, mtry, maxDepth, minLeaf, rnd)
            ta.f[id] = bf; ta.t[id] = bt.toFloat(); ta.l[id] = lc; ta.r[id] = rc
            return id
        }
    }
}
