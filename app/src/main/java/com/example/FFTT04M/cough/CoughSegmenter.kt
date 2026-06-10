package com.example.FFTT04M.cough

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Energy-envelope cough event detection (research brief §2):
 *   short-time RMS → dynamic threshold (median × factor) → group active frames →
 *   merge gaps < mergeGapMs → discard segments shorter than minDurationMs or longer than maxDurationMs.
 */
class CoughSegmenter(private val cfg: CoughAnalysisConfig = CoughAnalysisConfig()) {

    fun segment(x: FloatArray, sampleRate: Int): List<CoughSegment> {
        if (x.isEmpty() || sampleRate <= 0) return emptyList()
        val win = max(1, (cfg.rmsWindowMs / 1000.0 * sampleRate).roundToInt())
        val hop = max(1, (cfg.rmsHopMs / 1000.0 * sampleRate).roundToInt())
        val env = CoughDsp.rmsEnvelope(x, win, hop)
        if (env.isEmpty()) return emptyList()

        val threshold = CoughDsp.median(env) * cfg.thresholdFactor.toFloat()
        // Collect runs of above-threshold frames as [startFrame, endFrame] (inclusive).
        val runs = ArrayList<IntArray>()
        var runStart = -1
        for (f in env.indices) {
            val active = env[f] > threshold
            if (active && runStart < 0) runStart = f
            if (!active && runStart >= 0) { runs.add(intArrayOf(runStart, f - 1)); runStart = -1 }
        }
        if (runStart >= 0) runs.add(intArrayOf(runStart, env.size - 1))
        if (runs.isEmpty()) return emptyList()

        // Merge runs separated by a gap smaller than mergeGapMs (in frames).
        val mergeGapFrames = (cfg.mergeGapMs / cfg.rmsHopMs).roundToInt()
        val merged = ArrayList<IntArray>()
        var cur = runs[0]
        for (i in 1 until runs.size) {
            val next = runs[i]
            if (next[0] - cur[1] <= mergeGapFrames) cur = intArrayOf(cur[0], next[1])
            else { merged.add(cur); cur = next }
        }
        merged.add(cur)

        // Convert frames → sample offsets, filter by duration.
        val out = ArrayList<CoughSegment>()
        for (r in merged) {
            val startSample = r[0] * hop
            val endSample = (r[1] * hop + win).coerceAtMost(x.size)
            val durMs = (endSample - startSample).toDouble() / sampleRate * 1000.0
            if (durMs in cfg.minDurationMs..cfg.maxDurationMs) {
                out.add(CoughSegment(startSample, endSample, sampleRate))
            }
        }
        return out
    }
}
