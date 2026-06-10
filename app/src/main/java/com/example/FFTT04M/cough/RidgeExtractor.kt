package com.example.FFTT04M.cough

import com.example.FFTT04M.FFTUtils
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Extracts the "bronchitis squiggle": a short time–frequency ridge in the 300–1000 Hz band whose
 * instantaneous frequency follows a near-parabolic trajectory (research brief / email §2).
 *
 * Pipeline: STFT (Hann, ~25 ms / ~10 ms) → for each frame keep only the 300–1000 Hz rows → take the
 * peak-energy bin (sub-bin interpolated) as f_t, if it's prominent enough → fit f ≈ a·t² + b·t + c
 * to the {(t, f_t)} sequence by least squares. Yields a compact, comparable feature vector.
 */
class RidgeExtractor(private val cfg: CoughAnalysisConfig = CoughAnalysisConfig()) {

    /** A single tracked ridge point, exposed for visualization overlays. */
    data class RidgePoint(val timeSec: Double, val freqHz: Double, val energy: Double)

    /** Full ridge result including the per-frame points (for drawing the overlay). */
    data class RidgeResult(val features: RidgeFeatures, val points: List<RidgePoint>)

    fun extract(x: FloatArray, startSample: Int, endSample: Int, sampleRate: Int): RidgeResult {
        val n = (endSample - startSample).coerceAtLeast(0)
        if (n < 16) return RidgeResult(RidgeFeatures.NONE, emptyList())

        val win = max(8, (cfg.stftWindowMs / 1000.0 * sampleRate).roundToInt())
        val hop = max(1, (cfg.stftHopMs / 1000.0 * sampleRate).roundToInt())
        val fftSize = FFTUtils.nextPowerOfTwo(win)
        val loBin = CoughDsp.hzToBin(cfg.ridgeLoHz, sampleRate, fftSize).coerceAtLeast(1)
        val hiBin = CoughDsp.hzToBin(cfg.ridgeHiHz, sampleRate, fftSize).coerceAtMost(fftSize / 2 - 1)
        if (hiBin <= loBin) return RidgeResult(RidgeFeatures.NONE, emptyList())

        val points = ArrayList<RidgePoint>()
        var frameStart = startSample
        while (frameStart + win <= endSample) {
            val frame = FloatArray(win) { x[frameStart + it] }
            val mag = CoughDsp.magnitudeSpectrum(frame, fftSize, window = true)
            // Peak within the ridge band + total magnitude for prominence gating.
            var peakBin = loBin
            var peakMag = -1.0
            var bandSum = 0.0
            var totalSum = 0.0
            for (k in mag.indices) {
                val m = mag[k].toDouble()
                totalSum += m
                if (k in loBin..hiBin) {
                    bandSum += m
                    if (m > peakMag) { peakMag = m; peakBin = k }
                }
            }
            val prominence = if (totalSum > 1e-12) bandSum / totalSum else 0.0
            if (prominence >= cfg.ridgePeakProminence && peakMag > 0) {
                val offset = CoughDsp.parabolicPeakOffset(mag, peakBin)
                val freq = (peakBin + offset) * sampleRate / fftSize
                val tSec = (frameStart + win / 2.0 - startSample) / sampleRate
                points.add(RidgePoint(tSec, freq, peakMag))
            }
            frameStart += hop
        }

        if (points.size < 3) return RidgeResult(RidgeFeatures.NONE, points)

        val t = DoubleArray(points.size) { points[it].timeSec }
        val f = DoubleArray(points.size) { points[it].freqHz }
        val fit = CoughDsp.fitParabola(t, f) ?: return RidgeResult(RidgeFeatures.NONE, points)
        val (a, b, c, r2) = fit

        val midT = (t.first() + t.last()) / 2.0
        val centerFreq = a * midT * midT + b * midT + c
        var meanF = 0.0; var meanE = 0.0
        for (p in points) { meanF += p.freqHz; meanE += p.energy }
        meanF /= points.size; meanE /= points.size
        var varF = 0.0
        for (p in points) { val d = p.freqHz - meanF; varF += d * d }
        val bandwidth = sqrt(varF / points.size)

        return RidgeResult(
            RidgeFeatures(
                valid = true,
                curvature = a, slope = b, intercept = c,
                centerFreqHz = centerFreq,
                frameCount = points.size,
                energy = meanE,
                bandwidthHz = bandwidth,
                rSquared = r2,
            ),
            points,
        )
    }
}
