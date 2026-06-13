package com.example.FFTT04M.cough

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Streaming, hands-free cough detector for auto-capture (FUTURE VISION: "auto-capture of cough-like
 * events" + "speech rejection"). Feed it live PCM in arbitrary chunk sizes via [process]; it fires
 * [onCough] for each detected, speech-rejected cough with the captured audio + features.
 *
 * Mechanism: block-wise RMS with an adaptive noise floor (EMA, updated only while idle) → onset when
 * RMS exceeds max(floor × factor, absFloor) → collect (with pre-roll so the attack isn't clipped) →
 * end after [hangoverMs] of sub-threshold audio or at the max duration → gate by duration, then run
 * the same FFT / ridge / speech-rejection features as the offline analyzer. Only speech-passed
 * events of valid duration are emitted.
 */
class CoughDetector(
    private val sampleRate: Int,
    private val cfg: CoughAnalysisConfig = CoughAnalysisConfig(),
    private val onCough: (CapturedCough) -> Unit,
) {
    /** A captured cough: the audio plus the same features the offline analyzer produces. */
    data class CapturedCough(
        val pcm: FloatArray,
        val startSampleGlobal: Long,
        val fft: FftFeatures,
        val ridge: RidgeFeatures,
        val speech: SpeechVerdict,
    )

    private val block = max(1, (cfg.rmsHopMs / 1000.0 * sampleRate).roundToInt())
    private val blockMs = block.toDouble() / sampleRate * 1000.0
    private val preRollBlocks = max(0, (cfg.preRollMs / blockMs).roundToInt())
    private val fftExtractor = FftFeatureExtractor(cfg)
    private val ridgeExtractor = RidgeExtractor(cfg)
    private val speechRejector = SpeechRejector(cfg)

    private val carry = ArrayList<Float>(block * 2)
    private val ring = ArrayDeque<FloatArray>()       // recent idle blocks (pre-roll)
    private val eventBlocks = ArrayList<FloatArray>()
    private var active = false
    private var floor = cfg.detectAbsFloor.toFloat()
    private var silenceMs = 0.0
    private var eventMs = 0.0
    private var aboveMs = 0.0          // above-threshold time only (the true event duration)
    private var globalSamples = 0L
    private var eventStartGlobal = 0L

    /** Feed a chunk of mono PCM (any length). */
    fun process(chunk: FloatArray) {
        for (v in chunk) carry.add(v)
        while (carry.size >= block) {
            val b = FloatArray(block) { carry[it] }
            carry.subList(0, block).clear()
            processBlock(b)
        }
    }

    /** Flush any in-progress event (e.g. when stopping capture). */
    fun finish() {
        if (active) endEvent()
    }

    private fun threshold(): Float = max(floor * cfg.thresholdFactor.toFloat(), cfg.detectAbsFloor.toFloat())

    private fun processBlock(b: FloatArray) {
        var sum = 0.0
        for (v in b) sum += v.toDouble() * v
        val rms = sqrt(sum / b.size).toFloat()
        globalSamples += b.size

        if (!active) {
            // Adaptive noise floor (idle only) so steady room noise raises the bar.
            floor = (cfg.noiseFloorAlpha * floor + (1 - cfg.noiseFloorAlpha) * rms).toFloat()
            ring.addLast(b)
            while (ring.size > preRollBlocks) ring.removeFirst()
            if (rms > threshold()) {
                active = true
                eventBlocks.clear()
                eventBlocks.addAll(ring)               // pre-roll
                ring.clear()
                eventBlocks.add(b)
                silenceMs = 0.0
                aboveMs = blockMs                      // the onset block is above threshold
                eventMs = preRollBlocks * blockMs + blockMs
                eventStartGlobal = globalSamples - eventMs.toLong() * sampleRate / 1000
            }
        } else {
            eventBlocks.add(b)
            eventMs += blockMs
            if (rms < threshold()) silenceMs += blockMs else { silenceMs = 0.0; aboveMs += blockMs }
            if (silenceMs >= cfg.hangoverMs || eventMs >= cfg.maxDurationMs) endEvent()
        }
    }

    private fun endEvent() {
        active = false
        // Concatenate captured blocks.
        val total = eventBlocks.sumOf { it.size }
        val pcm = FloatArray(total)
        var o = 0
        for (blk in eventBlocks) { blk.copyInto(pcm, o); o += blk.size }
        eventBlocks.clear()
        val coreMs = aboveMs
        silenceMs = 0.0; eventMs = 0.0; aboveMs = 0.0

        // Gate on the above-threshold span (the real cough), not the padded capture length.
        if (coreMs < cfg.minDurationMs || coreMs > cfg.maxDurationMs) return

        val speech = speechRejector.classify(pcm, 0, pcm.size, sampleRate)
        // Verdict: the trained cough forest (WholeClipFeatures → CoughForest, ~91% sens / 83% spec on
        // ALLDATA) when its model is bundled; otherwise fall back to the old speech-rejection heuristic.
        val passes = if (CoughClassifier.available()) CoughClassifier.isCough(pcm, sampleRate)
                     else speech.isLikelyCough
        if (!passes) return
        val fft = fftExtractor.extract(pcm, 0, pcm.size, sampleRate)
        val ridge = ridgeExtractor.extract(pcm, 0, pcm.size, sampleRate).features
        onCough(CapturedCough(pcm, eventStartGlobal, fft, ridge, speech))
    }
}
