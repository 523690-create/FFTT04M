package com.example.FFTT04M.cough

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mel-Frequency Cepstral Coefficients — the Tier-2 feature consumed by the MFCC+TDNN / logistic-
 * regression pipeline (research brief §1.2, the "practical sweet spot"). Frames the cough, builds a
 * mel filterbank, log-compresses, and applies a DCT-II; summarizes per-segment as mean ± std over
 * frames (a fixed-length vector for classical ML) and can also return the full sequence.
 *
 * Pure Kotlin (reuses FFTUtils via CoughDsp); unit-tested on the JVM.
 */
data class MfccFeatures(
    val numCoeffs: Int,
    val mean: DoubleArray,   // per-coefficient mean over frames
    val std: DoubleArray,    // per-coefficient std over frames
    val frameCount: Int,
)

class MfccExtractor(
    private val numCoeffs: Int = 13,
    private val numFilters: Int = 40,
    private val winMs: Double = 25.0,
    private val hopMs: Double = 10.0,
    private val lowHz: Double = 20.0,
    private val highHz: Double = 8000.0,
) {
    private fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun melToHz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)

    /** Triangular mel filterbank as [numFilters] arrays of per-bin weights over the half-spectrum. */
    private fun melFilterbank(sampleRate: Int, fftSize: Int): Array<DoubleArray> {
        val half = fftSize / 2
        val hi = min(highHz, sampleRate / 2.0)
        val loMel = hzToMel(lowHz); val hiMel = hzToMel(hi)
        val points = DoubleArray(numFilters + 2) { melToHz(loMel + (hiMel - loMel) * it / (numFilters + 1)) }
        val bins = IntArray(numFilters + 2) { (points[it] * fftSize / sampleRate).roundToInt().coerceIn(0, half - 1) }
        return Array(numFilters) { m ->
            val w = DoubleArray(half)
            val a = bins[m]; val b = bins[m + 1]; val c = bins[m + 2]
            for (k in a until b) if (b > a) w[k] = (k - a).toDouble() / (b - a)
            for (k in b until c) if (c > b) w[k] = (c - k).toDouble() / (c - b)
            w
        }
    }

    /** DCT-II of [logMel] → first [numCoeffs] cepstral coefficients. */
    private fun dct(logMel: DoubleArray): DoubleArray {
        val m = logMel.size
        return DoubleArray(numCoeffs) { k ->
            var s = 0.0
            for (n in 0 until m) s += logMel[n] * cos(Math.PI / m * (n + 0.5) * k)
            s
        }
    }

    fun extract(x: FloatArray, startSample: Int, endSample: Int, sampleRate: Int): MfccFeatures {
        val n = (endSample - startSample).coerceAtLeast(0)
        val win = max(8, (winMs / 1000 * sampleRate).roundToInt())
        val hop = max(1, (hopMs / 1000 * sampleRate).roundToInt())
        if (n < win) return MfccFeatures(numCoeffs, DoubleArray(numCoeffs), DoubleArray(numCoeffs), 0)
        val fftSize = com.example.FFTT04M.FFTUtils.nextPowerOfTwo(win)
        val fb = melFilterbank(sampleRate, fftSize)

        val frames = ArrayList<DoubleArray>()
        var pos = startSample
        while (pos + win <= endSample) {
            val frame = FloatArray(win) { x[pos + it] }
            val mag = CoughDsp.magnitudeSpectrum(frame, fftSize, window = true)
            val logMel = DoubleArray(numFilters) { f ->
                var e = 0.0
                val w = fb[f]
                for (k in mag.indices) { val p = mag[k].toDouble(); e += p * p * w[k] }
                ln(max(e, 1e-10))
            }
            frames.add(dct(logMel))
            pos += hop
        }
        if (frames.isEmpty()) return MfccFeatures(numCoeffs, DoubleArray(numCoeffs), DoubleArray(numCoeffs), 0)

        val mean = DoubleArray(numCoeffs); val std = DoubleArray(numCoeffs)
        for (fr in frames) for (k in 0 until numCoeffs) mean[k] += fr[k]
        for (k in 0 until numCoeffs) mean[k] /= frames.size
        for (fr in frames) for (k in 0 until numCoeffs) { val d = fr[k] - mean[k]; std[k] += d * d }
        for (k in 0 until numCoeffs) std[k] = kotlin.math.sqrt(std[k] / frames.size)
        return MfccFeatures(numCoeffs, mean, std, frames.size)
    }
}
