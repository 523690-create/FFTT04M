package com.example.FFTT04M.cough

/**
 * Serializes a [CoughAnalysis] to the unified training schema (research brief §2/§3) as
 * `segments.jsonl` — one JSON object per detected event per line — plus a pretty single-object
 * form for inspection. Hand-rolled (no org.json) so it is dependency-free and unit-testable on the
 * JVM, and produces stable, locale-independent numbers.
 */
object CoughSchemaJson {

    /** One JSON line per event, matching the unified `segments.jsonl` schema. */
    fun toJsonl(analysis: CoughAnalysis, recordingId: String, deviceId: String? = null): String {
        val sb = StringBuilder()
        for (e in analysis.events) {
            sb.append(eventObject(analysis, e, recordingId, deviceId))
            sb.append('\n')
        }
        return sb.toString()
    }

    /** A single pretty-ish JSON object wrapping the whole analysis (for export/preview). */
    fun toJson(analysis: CoughAnalysis, recordingId: String, deviceId: String? = null): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"recording\": ").append(recordingObject(analysis, recordingId, deviceId)).append(",\n")
        sb.append("  \"segments\": [\n")
        analysis.events.forEachIndexed { i, e ->
            sb.append("    ").append(eventObject(analysis, e, recordingId, deviceId))
            if (i < analysis.events.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ],\n")
        sb.append("  \"model_metadata\": {\"algorithm\": \"fft_ridge_classical\", \"version\": \"0.1.0\"}\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun recordingObject(a: CoughAnalysis, recordingId: String, deviceId: String?): String =
        "{\"id\": ${str(recordingId)}, \"device_id\": ${nullableStr(deviceId)}, " +
            "\"sample_rate_hz\": ${a.sampleRate}, \"channels\": 1, " +
            "\"total_samples\": ${a.totalSamples}, \"cough_count\": ${a.coughCount}}"

    private fun eventObject(a: CoughAnalysis, e: CoughEvent, recordingId: String, deviceId: String?): String {
        val r = e.ridge
        val s = e.speech
        val f = e.fft
        return buildString {
            append('{')
            append("\"recording_id\": ").append(str(recordingId)).append(", ")
            append("\"segment_id\": ").append(str("${recordingId}_seg_${pad(e.index)}")).append(", ")
            deviceId?.let { append("\"device_id\": ").append(str(it)).append(", ") }
            append("\"start_sample\": ").append(e.segment.startSample).append(", ")
            append("\"end_sample\": ").append(e.segment.endSample).append(", ")
            append("\"sample_rate_hz\": ").append(a.sampleRate).append(", ")
            append("\"is_cough\": ").append(s.isLikelyCough).append(", ")
            e.phases?.let { p ->
                append("\"phases\": {\"t1_s\": ").append(num(p.t1Sec))
                append(", \"t2_s\": ").append(num(p.t2Sec))
                append(", \"t3_expulsive_s\": ").append(num(p.t3Sec))
                append(", \"expulsive_start_sample\": ").append(p.expulsiveStartSample)
                append(", \"expulsive_end_sample\": ").append(p.expulsiveEndSample).append("}, ")
            }
            append("\"qc\": {\"speech_likelihood\": ").append(num(s.speechLikelihood))
            append(", \"spectral_flatness\": ").append(num(s.spectralFlatness))
            append(", \"pitch_strength\": ").append(num(s.pitchStrength)).append("}, ")
            append("\"features\": {")
            append("\"fft\": {")
            append("\"duration_s\": ").append(num(f.durationSec))
            append(", \"q_ratio\": ").append(num(f.qRatio))
            append(", \"fmax_hz\": ").append(num(f.fmaxHz)).append("}, ")
            append("\"ridge\": {")
            append("\"valid\": ").append(r.valid)
            append(", \"curvature_a\": ").append(num(r.curvature))
            append(", \"slope_b\": ").append(num(r.slope))
            append(", \"intercept_c\": ").append(num(r.intercept))
            append(", \"center_freq_hz\": ").append(num(r.centerFreqHz))
            append(", \"frame_count\": ").append(r.frameCount)
            append(", \"energy\": ").append(num(r.energy))
            append(", \"bandwidth_hz\": ").append(num(r.bandwidthHz))
            append(", \"r_squared\": ").append(num(r.rSquared)).append("}")
            e.mfcc?.let { mf ->
                append(", \"mfcc\": {\"num_coeffs\": ").append(mf.numCoeffs)
                append(", \"frame_count\": ").append(mf.frameCount)
                append(", \"mean\": ").append(numArray(mf.mean))
                append(", \"std\": ").append(numArray(mf.std)).append("}")
            }
            append("}, ")
            append("\"labels\": {\"diagnosis_4class\": \"unknown\", \"source\": \"model\"}")
            append('}')
        }
    }

    private fun pad(i: Int): String = i.toString().padStart(4, '0')

    /** Locale-independent number with up to 6 significant decimals; finite-guarded. */
    private fun num(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "null"
        val s = String.format(java.util.Locale.US, "%.6f", v)
        // Trim trailing zeros but keep at least one decimal digit.
        return s.trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
    }

    private fun numArray(v: DoubleArray): String = v.joinToString(prefix = "[", postfix = "]") { num(it) }

    private fun str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun nullableStr(s: String?): String = if (s == null) "null" else str(s)
}
