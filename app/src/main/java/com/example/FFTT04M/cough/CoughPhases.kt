package com.example.FFTT04M.cough

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cough phase segmentation (research brief / BMC protocol): split a cough into
 *   T1 (inspiratory) · T2 (compressive) · T3 (expulsive)
 * and locate the **expulsive window** — the high-energy 150–200 ms that the literature finds most
 * diagnostically informative (EAT patch-attention studies).
 *
 * Energy-envelope heuristic: find the peak, grow the expulsive core outward while energy stays above
 * a fraction of the peak; everything before the expulsive onset is the (lower-energy) inspiratory +
 * compressive pre-phase, split in half as a best-effort T1/T2. Captured coughs often begin at the
 * burst, so T1/T2 may be near-zero — that's expected and honest.
 */
data class PhaseFeatures(
    val t1Sec: Double,
    val t2Sec: Double,
    val t3Sec: Double,
    val expulsiveStartSample: Int,
    val expulsiveEndSample: Int,
)

class CoughPhases(private val cfg: CoughAnalysisConfig = CoughAnalysisConfig()) {

    fun detect(x: FloatArray, startSample: Int, endSample: Int, sampleRate: Int): PhaseFeatures {
        val n = (endSample - startSample).coerceAtLeast(0)
        if (n < 16) return PhaseFeatures(0.0, 0.0, n.toDouble() / sampleRate, startSample, endSample)

        val win = max(4, (5.0 / 1000 * sampleRate).roundToInt())     // 5 ms
        val hop = max(2, (2.5 / 1000 * sampleRate).roundToInt())     // 2.5 ms
        val seg = FloatArray(n) { x[startSample + it] }
        val env = CoughDsp.rmsEnvelope(seg, win, hop)
        if (env.isEmpty()) return PhaseFeatures(0.0, 0.0, n.toDouble() / sampleRate, startSample, endSample)

        var peak = 0; var peakVal = env[0]
        for (i in env.indices) if (env[i] > peakVal) { peakVal = env[i]; peak = i }
        if (peakVal <= 1e-9) return PhaseFeatures(0.0, 0.0, n.toDouble() / sampleRate, startSample, endSample)

        val onsetThresh = 0.5f * peakVal     // expulsive core
        val decayThresh = 0.3f * peakVal     // expulsive tail
        var s = peak
        while (s > 0 && env[s - 1] >= onsetThresh) s--
        var e = peak
        while (e < env.size - 1 && env[e + 1] >= decayThresh) e++

        val hopSec = hop.toDouble() / sampleRate
        val t3Sec = (e - s + 1) * hopSec
        val preFrames = s
        val t1Sec = (preFrames / 2) * hopSec
        val t2Sec = (preFrames - preFrames / 2) * hopSec

        val expStart = startSample + s * hop
        val expEnd = (startSample + (e + 1) * hop).coerceAtMost(endSample)
        return PhaseFeatures(t1Sec, t2Sec, t3Sec, expStart, expEnd)
    }
}
