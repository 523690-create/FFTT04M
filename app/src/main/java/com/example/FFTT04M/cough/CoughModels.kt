package com.example.FFTT04M.cough

/**
 * Data model for the cough-analysis engine. All times are in seconds unless a field name says
 * otherwise; frequencies in Hz.
 */

/** A detected acoustic event (candidate cough), as sample offsets into the source PCM. */
data class CoughSegment(
    val startSample: Int,
    val endSample: Int,
    val sampleRate: Int,
) {
    val startSec: Double get() = startSample.toDouble() / sampleRate
    val endSec: Double get() = endSample.toDouble() / sampleRate
    val durationSec: Double get() = (endSample - startSample).toDouble() / sampleRate
}

/** Classical FFT spectral features (tier-1; reproducible per the BMC Pulmonary Medicine protocol). */
data class FftFeatures(
    val durationSec: Double,
    /** Q-ratio = E(60–600 Hz) / E(600–6000 Hz). */
    val qRatio: Double,
    /** Frequency of maximum spectral energy. */
    val fmaxHz: Double,
    val lowBandEnergy: Double,
    val highBandEnergy: Double,
)

/**
 * The "squiggle": a short time–frequency ridge fitted to a parabola f ≈ a·t² + b·t + c over the
 * 300–1000 Hz band. [valid] is false when too few ridge points were found to fit.
 */
data class RidgeFeatures(
    val valid: Boolean,
    /** Curvature (a), in Hz/s². */
    val curvature: Double,
    /** Linear slope (b), in Hz/s. */
    val slope: Double,
    /** Constant term (c), in Hz (frequency at t=0 of the local fit). */
    val intercept: Double,
    /** Fitted frequency at the ridge's mid-time, in Hz. */
    val centerFreqHz: Double,
    /** Number of time frames that contributed a ridge point. */
    val frameCount: Int,
    /** Mean in-band energy along the ridge. */
    val energy: Double,
    /** Spread (std-dev) of the tracked frequencies, in Hz. */
    val bandwidthHz: Double,
    /** Goodness of the parabola fit (coefficient of determination, ≤ 1). */
    val rSquared: Double,
) {
    companion object {
        val NONE = RidgeFeatures(false, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0)
    }
}

/** Heuristic cough-vs-speech verdict (not a trained classifier — see [SpeechRejector]). */
data class SpeechVerdict(
    val isLikelyCough: Boolean,
    /** 0 = clearly cough-like, 1 = clearly voiced-speech-like. */
    val speechLikelihood: Double,
    val spectralFlatness: Double,
    val pitchStrength: Double,
)

/** A fully analysed cough event: segment + all extracted features. */
data class CoughEvent(
    val index: Int,
    val segment: CoughSegment,
    val fft: FftFeatures,
    val ridge: RidgeFeatures,
    val speech: SpeechVerdict,
    val phases: PhaseFeatures? = null,
    val mfcc: MfccFeatures? = null,
) {
    /**
     * Compact, comparable feature vector for clustering / distance:
     * [curvature, slope, centerFreq, duration, energy, bandwidth, qRatio, fmax].
     * Ridge terms are 0 when the ridge is invalid.
     */
    fun featureVector(): DoubleArray = doubleArrayOf(
        ridge.curvature, ridge.slope, ridge.centerFreqHz,
        fft.durationSec, ridge.energy, ridge.bandwidthHz,
        fft.qRatio, fft.fmaxHz,
    )
}

/** Result of analysing one recording. */
data class CoughAnalysis(
    val sampleRate: Int,
    val totalSamples: Int,
    val events: List<CoughEvent>,
) {
    val coughCount: Int get() = events.count { it.speech.isLikelyCough }
}

/** Tunable parameters for the whole pipeline; defaults follow the research brief. */
data class CoughAnalysisConfig(
    // Segmentation
    val rmsWindowMs: Double = 25.0,
    val rmsHopMs: Double = 10.0,
    val thresholdFactor: Double = 4.0,
    val mergeGapMs: Double = 200.0,
    val minDurationMs: Double = 200.0,
    val maxDurationMs: Double = 2000.0,
    // FFT feature bands (Hz)
    val lowBandLoHz: Double = 60.0,
    val lowBandHiHz: Double = 600.0,
    val highBandHiHz: Double = 6000.0,
    // Ridge band (Hz) — the "squiggle" lives here
    val ridgeLoHz: Double = 300.0,
    val ridgeHiHz: Double = 1000.0,
    val stftWindowMs: Double = 25.0,
    val stftHopMs: Double = 10.0,
    /** A frame contributes a ridge point only if its in-band peak exceeds this fraction of the
     *  frame's total magnitude (rejects noise-only frames). */
    val ridgePeakProminence: Double = 0.15,
    // Speech rejection
    val speechFlatnessThreshold: Double = 0.18,
    val speechPitchThreshold: Double = 0.55,
    // Real-time auto-capture (CoughDetector)
    /** Audio kept before the onset crossing, so the cough's attack isn't clipped. */
    val preRollMs: Double = 80.0,
    /** Sub-threshold silence needed to declare the event over (also bridges intra-cough gaps). */
    val hangoverMs: Double = 150.0,
    /** EMA factor for the adaptive noise floor (updated only while idle). */
    val noiseFloorAlpha: Double = 0.97,
    /** Absolute RMS gate so pure-silence rooms don't trigger on the relative threshold alone. */
    val detectAbsFloor: Double = 0.004,
)
