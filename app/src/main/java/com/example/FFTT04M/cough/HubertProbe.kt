package com.example.FFTT04M.cough

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.FFTT04M.GalleryTransfer
import java.io.File
import java.nio.FloatBuffer

/**
 * One-shot latency probe for on-device HuBERT — de-risks whether a capable phone (P10) can run the
 * model fast enough before we build the full tier-gated decode path. Loads the ONNX model from
 * `<recordingsDir>/hubert_base.onnx` (push it with adb — NOT bundled for the probe), runs it on one
 * clip, and logs session-create + inference ms for each available execution provider (NNAPI, CPU).
 * Read it with:  adb logcat -s HubertProbe
 */
object HubertProbe {
    private const val TAG = "HubertProbe"
    @Volatile private var ran = false

    private fun modelFile(ctx: Context): File =
        File(GalleryTransfer.recordingsDir(ctx) ?: ctx.filesDir, "hubert_base.onnx")

    fun available(ctx: Context): Boolean = modelFile(ctx).let { it.isFile && it.length() > 1_000_000 }

    /** Run once per process (background): resample to 16 kHz, run HuBERT, log latency per provider. */
    @Synchronized
    fun probeOnce(ctx: Context, pcm: FloatArray, sr: Int) {
        if (ran) return
        ran = true
        val model = modelFile(ctx)
        if (!model.isFile) { Log.i(TAG, "no model at ${model.absolutePath} — push it with adb to run the probe"); return }
        Thread {
            try {
                val x = if (sr == 16000) pcm else resample(pcm, sr, 16000)
                val env = OrtEnvironment.getEnvironment()
                Log.i(TAG, "model=${model.length() / 1_000_000}MB  clip=${"%.2f".format(x.size / 16000.0)}s")
                for (ep in listOf("nnapi", "cpu")) {
                    try {
                        val opts = OrtSession.SessionOptions()
                        if (ep == "nnapi") {
                            try { opts.addNnapi() } catch (e: Throwable) { Log.w(TAG, "NNAPI unavailable: ${e.message}"); opts.close(); continue }
                        }
                        val tc = System.currentTimeMillis()
                        val session = env.createSession(model.absolutePath, opts)
                        val createMs = System.currentTimeMillis() - tc
                        val inName = session.inputNames.first()
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { input ->
                            val once = java.util.Collections.singletonMap(inName, input)
                            session.run(once).close()                       // warm-up
                            val t0 = System.currentTimeMillis()
                            session.run(once).use { res ->
                                @Suppress("UNCHECKED_CAST")
                                val out = res[0].value as Array<Array<FloatArray>>
                                val ms = System.currentTimeMillis() - t0
                                Log.i(TAG, ">>> EP=$ep  sessionCreate=${createMs}ms  inferMs=$ms  frames=${out[0].size}x${out[0][0].size}")
                            }
                        }
                        session.close()
                    } catch (e: Throwable) { Log.w(TAG, "EP=$ep failed: ${e.message}") }
                }
                env.close()
            } catch (e: Throwable) { Log.e(TAG, "probe failed: ${e.message}", e) }
        }.start()
    }

    /** Linear resample (probe only; production would match the desktop's resampler). */
    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val n = (x.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * step; val i0 = p.toInt(); val frac = (p - i0).toFloat()
            out[i] = if (i0 + 1 < x.size) x[i0] * (1 - frac) + x[i0 + 1] * frac else x[x.size - 1]
        }
        return out
    }
}
