package com.example.FFTT04M.cough

import kotlin.math.sqrt

/**
 * Turns each cough's feature vector into a comparable data point with meaningful distances to other
 * coughs (the email's core ask: "distances between these z vectors directly measure similarity of
 * squiggle shape and location"). Standardizes per-dimension (z-score) so heterogeneous scales —
 * curvature in thousands vs duration in fractions of a second — don't dominate, then offers
 * Euclidean / cosine distance, nearest-neighbour lookup, and single-link clustering.
 */
object CoughSimilarity {

    /** Standardized feature set: z-scored [vectors] plus the [mean]/[std] used (to project new ones). */
    data class Standardized(val vectors: List<DoubleArray>, val mean: DoubleArray, val std: DoubleArray)

    /** Per-dimension z-score standardization. A zero-variance dimension is left at 0. */
    fun standardize(raw: List<DoubleArray>): Standardized {
        if (raw.isEmpty()) return Standardized(emptyList(), DoubleArray(0), DoubleArray(0))
        val dim = raw[0].size
        val mean = DoubleArray(dim)
        val std = DoubleArray(dim)
        for (v in raw) for (d in 0 until dim) mean[d] += v[d]
        for (d in 0 until dim) mean[d] /= raw.size
        for (v in raw) for (d in 0 until dim) { val e = v[d] - mean[d]; std[d] += e * e }
        for (d in 0 until dim) std[d] = sqrt(std[d] / raw.size)
        val z = raw.map { v -> DoubleArray(dim) { d -> if (std[d] > 1e-12) (v[d] - mean[d]) / std[d] else 0.0 } }
        return Standardized(z, mean, std)
    }

    /** Project a raw vector into a previously-computed standardized space. */
    fun project(v: DoubleArray, mean: DoubleArray, std: DoubleArray): DoubleArray =
        DoubleArray(v.size) { d -> if (std[d] > 1e-12) (v[d] - mean[d]) / std[d] else 0.0 }

    fun euclidean(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return sqrt(s)
    }

    /** Cosine distance in [0, 2] (1 − cosine similarity). */
    fun cosineDistance(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na < 1e-12 || nb < 1e-12) return 1.0
        return 1.0 - dot / (sqrt(na) * sqrt(nb))
    }

    /** For point [i], the index + Euclidean distance of its nearest other point (or -1 if none). */
    fun nearestNeighbor(i: Int, z: List<DoubleArray>): Pair<Int, Double> {
        var best = -1; var bestD = Double.MAX_VALUE
        for (j in z.indices) {
            if (j == i) continue
            val d = euclidean(z[i], z[j])
            if (d < bestD) { bestD = d; best = j }
        }
        return best to bestD
    }

    /** Full pairwise Euclidean distance matrix over standardized vectors. */
    fun distanceMatrix(z: List<DoubleArray>): Array<DoubleArray> {
        val n = z.size
        val m = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = euclidean(z[i], z[j]); m[i][j] = d; m[j][i] = d
        }
        return m
    }

    /**
     * Single-link agglomerative clustering: points within [threshold] (standardized Euclidean) are
     * transitively grouped. Returns a cluster id per point. Good for "which coughs look alike".
     */
    fun clusterSingleLink(z: List<DoubleArray>, threshold: Double): IntArray {
        val n = z.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x
            while (parent[c] != c) { val nx = parent[c]; parent[c] = r; c = nx }; return r }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        for (i in 0 until n) for (j in i + 1 until n)
            if (euclidean(z[i], z[j]) <= threshold) union(i, j)
        // Relabel roots to compact 0..k ids.
        val ids = IntArray(n) { find(it) }
        val remap = HashMap<Int, Int>()
        return IntArray(n) { remap.getOrPut(ids[it]) { remap.size } }
    }
}
