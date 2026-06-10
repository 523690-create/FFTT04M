package com.example.FFTT04M.cough

import com.example.FFTT04M.FFTUtils

/**
 * Classical FFT spectral features per cough segment (research brief §1, BMC Pulmonary Medicine):
 * duration, Q-ratio = E(60–600 Hz)/E(600–6000 Hz), and Fmax (frequency of peak energy).
 *
 * The whole segment is DC-removed and transformed once (zero-padded to a power of two); band
 * energies are summed directly over magnitude bins, which is equivalent to the published
 * filter-then-FFT-then-band approach for these ratio/peak features.
 */
class FftFeatureExtractor(private val cfg: CoughAnalysisConfig = CoughAnalysisConfig()) {

    fun extract(x: FloatArray, startSample: Int, endSample: Int, sampleRate: Int): FftFeatures {
        val n = (endSample - startSample).coerceAtLeast(0)
        val durationSec = n.toDouble() / sampleRate
        if (n < 8) return FftFeatures(durationSec, 0.0, 0.0, 0.0, 0.0)

        // DC-removed copy.
        var mean = 0.0
        for (i in 0 until n) mean += x[startSample + i]
        mean /= n
        val fftSize = FFTUtils.nextPowerOfTwo(n)
        val seg = FloatArray(n) { (x[startSample + it] - mean).toFloat() }
        val mag = CoughDsp.magnitudeSpectrum(seg, fftSize, window = false)

        val loA = CoughDsp.hzToBin(cfg.lowBandLoHz, sampleRate, fftSize)
        val loB = CoughDsp.hzToBin(cfg.lowBandHiHz, sampleRate, fftSize)
        val hiB = CoughDsp.hzToBin(cfg.highBandHiHz, sampleRate, fftSize)
        var lowE = 0.0
        var highE = 0.0
        var peakBin = loA
        var peakMag = -1.0
        for (k in mag.indices) {
            val m = mag[k].toDouble()
            if (k in loA until loB) lowE += m
            if (k in loB until hiB) highE += m
            // Fmax is sought across the analysis band (low..high) to ignore DC and ultrasonic noise.
            if (k in loA until hiB && m > peakMag) { peakMag = m; peakBin = k }
        }
        val qRatio = if (highE > 1e-9) lowE / highE else 0.0
        val offset = CoughDsp.parabolicPeakOffset(mag, peakBin)
        val fmaxHz = (peakBin + offset) * sampleRate / fftSize
        return FftFeatures(durationSec, qRatio, fmaxHz, lowE, highE)
    }
}
