package com.example.FFTT04M.cough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoughSimilarityTest {

    @Test fun standardize_givesZeroMeanUnitStd() {
        val raw = listOf(
            doubleArrayOf(1.0, 100.0), doubleArrayOf(2.0, 200.0),
            doubleArrayOf(3.0, 300.0), doubleArrayOf(4.0, 400.0),
        )
        val s = CoughSimilarity.standardize(raw)
        val dim = 2
        for (d in 0 until dim) {
            var mean = 0.0; for (v in s.vectors) mean += v[d]; mean /= s.vectors.size
            var varr = 0.0; for (v in s.vectors) varr += (v[d] - mean) * (v[d] - mean); varr /= s.vectors.size
            assertEquals("dim $d mean≈0", 0.0, mean, 1e-9)
            assertEquals("dim $d std≈1", 1.0, sqrt(varr), 1e-9)
        }
    }

    @Test fun cosine_identicalIsZeroOrthogonalIsOne() {
        val a = doubleArrayOf(1.0, 2.0, 3.0)
        assertEquals(0.0, CoughSimilarity.cosineDistance(a, a), 1e-9)
        assertEquals(1.0, CoughSimilarity.cosineDistance(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0)), 1e-9)
    }

    @Test fun nearestNeighbor_findsTheCloseTwin() {
        // Two near-identical squiggles + one very different.
        val z = listOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(0.05, -0.05),
            doubleArrayOf(10.0, 10.0),
        )
        assertEquals(1, CoughSimilarity.nearestNeighbor(0, z).first)
        assertEquals(0, CoughSimilarity.nearestNeighbor(1, z).first)
    }

    @Test fun clusterSingleLink_separatesTwoGroups() {
        val z = listOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(0.1, 0.1), doubleArrayOf(0.2, 0.0),  // group A
            doubleArrayOf(8.0, 8.0), doubleArrayOf(8.1, 7.9),                            // group B
        )
        val ids = CoughSimilarity.clusterSingleLink(z, threshold = 1.0)
        assertEquals("A members share a cluster", ids[0], ids[1])
        assertEquals(ids[0], ids[2])
        assertEquals("B members share a cluster", ids[3], ids[4])
        assertTrue("A and B are different clusters", ids[0] != ids[3])
        assertEquals("exactly two clusters", 2, ids.toSet().size)
    }

    @Test fun featureVector_fromEventHasEightDims() {
        val seg = CoughSegment(0, 4410, 44100)
        val e = CoughEvent(
            0, seg,
            FftFeatures(0.1, 1.5, 800.0, 10.0, 6.0),
            RidgeFeatures(true, 4000.0, -50.0, 320.0, 480.0, 25, 12.0, 40.0, 0.9),
            SpeechVerdict(true, 0.1, 0.4, 0.2),
        )
        assertEquals(8, e.featureVector().size)
    }

    private fun sqrt(x: Double) = kotlin.math.sqrt(x)
}
