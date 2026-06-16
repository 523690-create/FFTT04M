package com.example.FFTT04M.cough

/**
 * Orchestrates the on-device classical cough-analysis pipeline:
 *   segment → (per segment) FFT features + ridge fit + speech gate → [CoughAnalysis].
 *
 * Pure DSP; feed it decoded PCM (e.g. from WavReader). The richer per-frame ridge points are
 * available via [analyzeWithRidges] for the visualization overlay.
 */
class CoughAnalyzer(private val cfg: CoughAnalysisConfig = CoughAnalysisConfig()) {

    private val segmenter = CoughSegmenter(cfg)
    private val fftExtractor = FftFeatureExtractor(cfg)
    private val ridgeExtractor = RidgeExtractor(cfg)
    private val speechRejector = SpeechRejector(cfg)
    private val phaseDetector = CoughPhases(cfg)
    private val mfccExtractor = MfccExtractor()

    /** Analyse [x] at [sampleRate]; returns events with features (ridge points discarded). */
    fun analyze(x: FloatArray, sampleRate: Int): CoughAnalysis =
        analyzeWithRidges(x, sampleRate).first

    /** Like [analyze], but also returns the per-event ridge point lists (parallel to events). */
    fun analyzeWithRidges(
        x: FloatArray,
        sampleRate: Int,
    ): Pair<CoughAnalysis, List<List<RidgeExtractor.RidgePoint>>> {
        val segments = segmenter.segment(x, sampleRate)
        val events = ArrayList<CoughEvent>(segments.size)
        val ridgePoints = ArrayList<List<RidgeExtractor.RidgePoint>>(segments.size)
        segments.forEachIndexed { i, seg ->
            val fft = fftExtractor.extract(x, seg.startSample, seg.endSample, sampleRate)
            val ridge = ridgeExtractor.extract(x, seg.startSample, seg.endSample, sampleRate)
            val speech = speechRejector.classify(x, seg.startSample, seg.endSample, sampleRate)
            val phases = phaseDetector.detect(x, seg.startSample, seg.endSample, sampleRate)
            val mfcc = mfccExtractor.extract(x, seg.startSample, seg.endSample, sampleRate)
            events.add(CoughEvent(i, seg, fft, ridge.features, speech, phases, mfcc))
            ridgePoints.add(ridge.points)
        }
        return CoughAnalysis(sampleRate, x.size, events) to ridgePoints
    }

    /**
     * [8 DSP + 13 MFCC] feature vector computed over the WHOLE clip as one window — a fallback for
     * matching when the segmenter finds no cough segment (common for pre-trimmed captures the capture
     * detector saved but this analyzer won't split). Same layout/order as
     * [CoughEvent.featureVector] + the 13 MFCC means. Null for an empty clip.
     */
    fun wholeClipFeatureVector(x: FloatArray, sampleRate: Int): DoubleArray? {
        if (x.isEmpty()) return null
        val fft = fftExtractor.extract(x, 0, x.size, sampleRate)
        val ridge = ridgeExtractor.extract(x, 0, x.size, sampleRate).features
        val mfcc = mfccExtractor.extract(x, 0, x.size, sampleRate)
        val base = doubleArrayOf(
            ridge.curvature, ridge.slope, ridge.centerFreqHz,
            fft.durationSec, ridge.energy, ridge.bandwidthHz,
            fft.qRatio, fft.fmaxHz,
        )
        val m = mfcc?.mean ?: DoubleArray(0)
        return base + DoubleArray(13) { if (it < m.size) m[it] else 0.0 }
    }
}
