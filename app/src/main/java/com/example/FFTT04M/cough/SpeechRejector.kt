package com.example.FFTT04M.cough

import com.example.FFTT04M.FFTUtils

/**
 * Heuristic cough-vs-speech gate (research brief: "speech rejection"). NOT a trained classifier —
 * a transparent, explainable filter so speech isn't mistaken for a cough during auto-capture.
 *
 * Discriminators:
 *  - Spectral flatness (Wiener entropy): a cough's expulsive burst is noise-like → high flatness;
 *    voiced speech is harmonic/peaky → low flatness.
 *  - Pitch strength (normalized autocorrelation): voiced speech is strongly periodic → high;
 *    cough is aperiodic → low.
 *  - Duration: very long events are more likely sustained speech than a single cough.
 *
 * A segment is flagged speech-like when it is strongly pitched AND tonal (low flatness).
 */
class SpeechRejector(private val cfg: CoughAnalysisConfig = CoughAnalysisConfig()) {

    fun classify(x: FloatArray, startSample: Int, endSample: Int, sampleRate: Int): SpeechVerdict {
        val n = (endSample - startSample).coerceAtLeast(0)
        if (n < 16) return SpeechVerdict(true, 0.0, 1.0, 0.0)

        val seg = FloatArray(n) { x[startSample + it] }
        val fftSize = FFTUtils.nextPowerOfTwo(n)
        val mag = CoughDsp.magnitudeSpectrum(seg, fftSize, window = true)
        // Flatness measured over the speech-relevant band (≈ up to 4 kHz) where harmonics live.
        val hi = CoughDsp.hzToBin(4000.0, sampleRate, fftSize)
        val flatness = CoughDsp.spectralFlatness(mag, 1, hi).toDouble()
        val pitch = CoughDsp.pitchStrength(seg, sampleRate).toDouble()

        // Speech-likelihood: high when pitched and tonal. Each cue contributes; combine as the
        // geometric-ish mean so BOTH must be speech-like to flag it.
        val pitchCue = (pitch / cfg.speechPitchThreshold).coerceIn(0.0, 1.0)
        val toneCue = ((cfg.speechFlatnessThreshold - flatness) / cfg.speechFlatnessThreshold)
            .coerceIn(0.0, 1.0)
        val speechLikelihood = kotlin.math.sqrt(pitchCue * toneCue)

        val isLikelyCough = !(pitch >= cfg.speechPitchThreshold && flatness < cfg.speechFlatnessThreshold)
        return SpeechVerdict(isLikelyCough, speechLikelihood, flatness, pitch)
    }
}
