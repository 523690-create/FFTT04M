package com.example.FFTT04M.cough

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.FFTT04M.DeviceCaps
import java.io.File
import java.nio.FloatBuffer

/**
 * Production on-device HuBERT feature extractor — the 89%-CV gold-standard path (HubertProbe was the
 * one-shot latency de-risk; this is the real thing). Runs the bundled `hubert_base.onnx` asset and
 * returns per-frame 768-dim hidden states `[T, 768]`, which PhonemeDecoder mean-pools per fixed-grid
 * window and matches against the HuBERT codebook centroids.
 *
 * The **fp32** model is bundled verbatim (no quantization) so the device's embeddings are byte-for-
 * byte the same distribution the desktop built the codebook from — quantization would shift the
 * embeddings and the fp32-trained centroids would no longer fit. The resampler below is identical to
 * the desktop's (HubertKMeansUnits.resample) for the same reason.
 *
 * Heavy: ~377 MB mmap + activations, so it's gated by [DeviceCaps.hubertAllowed] (RAM floor) and the
 * session is created once and cached for the process. Any failure returns null → DSP fallback.
 */
object HubertFeatures {
    private const val TAG = "HubertFeatures"
    private const val TARGET_SR = 16000
    private const val ASSET = "hubert_base.onnx"

    @Volatile private var triedInit = false
    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var inputName: String? = null

    val isAvailable: Boolean get() = session != null

    /** Create the session once (CPU/XNNPACK EP — reliable across SoCs; NNAPI partitions HuBERT
     *  poorly). Returns true if HuBERT is usable on this device. Cached after the first attempt. */
    @Synchronized
    fun ensureLoaded(ctx: Context): Boolean {
        if (triedInit) return session != null
        triedInit = true
        if (!DeviceCaps.hubertAllowed(ctx)) {
            Log.i(TAG, "below RAM floor (${DeviceCaps.totalRamBytes(ctx) / 1_000_000}MB) — HuBERT disabled, DSP fallback")
            return false
        }
        return try {
            val model = stageModel(ctx) ?: return false
            val e = OrtEnvironment.getEnvironment()
            val s = e.createSession(model.absolutePath, OrtSession.SessionOptions())
            env = e; session = s; inputName = s.inputNames.firstOrNull()
            Log.i(TAG, "HuBERT ready (${model.length() / 1_000_000}MB, in=$inputName)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "init failed: ${t.message}", t); session = null; false
        }
    }

    /** The Wi-Fi-DOWNLOADED model file (no longer bundled — see [HubertModelManager]). Present + full-size
     *  ⇒ HuBERT usable; otherwise null ⇒ PhonemeDecoder stays on the DSP codebook until it's downloaded. */
    private fun stageModel(ctx: Context): File? {
        val f = HubertModelManager.modelFile(ctx)
        return if (f.isFile && f.length() == com.example.FFTT04M.BuildConfig.HUBERT_MODEL_BYTES) f
        else { Log.i(TAG, "HuBERT model not downloaded — DSP fallback"); null }
    }

    /** `[T, 768]` per-frame hidden states for a clip (resampled to 16 kHz), or null on any failure.
     *  Frame stride ≈ clipDurationMs / T — the caller derives it to align frames to windows. */
    fun frameEmbeddings(ctx: Context, pcm: FloatArray, sr: Int): Array<FloatArray>? {
        if (!ensureLoaded(ctx)) return null
        val e = env ?: return null; val s = session ?: return null; val inName = inputName ?: return null
        val x = if (sr == TARGET_SR) pcm else resample(pcm, sr, TARGET_SR)
        if (x.isEmpty()) return null
        return try {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { input ->
                s.run(java.util.Collections.singletonMap(inName, input)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    (res[0].value as Array<Array<FloatArray>>)[0]
                }
            }
        } catch (t: Throwable) { Log.e(TAG, "infer failed: ${t.message}"); null }
    }

    /** Linear resample — identical formula to the desktop's HubertKMeansUnits.resample so on-device
     *  embeddings match the codebook's training distribution. */
    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = from.toDouble() / to
        val outLen = (x.size / ratio).toInt().coerceAtLeast(1)
        return FloatArray(outLen) { i ->
            val pos = i * ratio; val idx = pos.toInt()
            if (idx >= x.size - 1) x[x.size - 1]
            else { val frac = (pos - idx).toFloat(); x[idx] * (1 - frac) + x[idx + 1] * frac }
        }
    }
}
