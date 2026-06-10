package com.example.FFTT04M.cough

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoughPhasesTest {

    private val sr = 44100

    private fun noise(durSec: Double, amp: Double, rng: Random): FloatArray =
        FloatArray((durSec * sr).toInt()) { (amp * (rng.nextDouble() * 2 - 1)).toFloat() }

    private fun concat(vararg p: FloatArray): FloatArray {
        val out = FloatArray(p.sumOf { it.size }); var o = 0
        for (a in p) { a.copyInto(out, o); o += a.size }
        return out
    }

    @Test fun detectsExpulsiveWindow() {
        val rng = Random(21)
        // 60 ms low pre-phase, 180 ms loud expulsive, 120 ms decay tail.
        val pre = noise(0.06, 0.05, rng)
        val expul = noise(0.18, 0.5, rng)
        val tail = noise(0.12, 0.08, rng)
        val cough = concat(pre, expul, tail)

        val ph = CoughPhases().detect(cough, 0, cough.size, sr)
        // Expulsive core should land roughly where the loud part begins (~60 ms).
        val expStartMs = ph.expulsiveStartSample.toDouble() / sr * 1000
        assertTrue("expulsive starts near the burst onset (was $expStartMs ms)", expStartMs in 30.0..100.0)
        assertTrue("expulsive (T3) duration is substantial (was ${ph.t3Sec * 1000} ms)", ph.t3Sec >= 0.10)
        assertTrue("pre-phase T1+T2 is small", ph.t1Sec + ph.t2Sec < 0.12)
        assertTrue("expulsive window is within the segment",
            ph.expulsiveEndSample <= cough.size && ph.expulsiveStartSample < ph.expulsiveEndSample)
    }
}
