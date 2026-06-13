package com.example.FFTT04M.cough

import com.example.FFTT04M.FFTUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Detection-INDEPENDENT acoustic features over a clip's loudest window, for the cough/not-cough
 * discrimination study. Every clip yields a vector (no segmentation gate), so we can measure which
 * features actually separate cough from breathing/speech/environment and design a better verdict.
 */
object WholeClipFeatures {

    val names = listOf(
        "crest", "zcr", "onset_sharp", "env_peak_ratio", "active_frac",
        "centroid_hz", "flatness", "rolloff85_hz", "bandwidth_hz", "hf_ratio", "q_ratio", "pitch_strength",
        "syllabic_mod", "spectral_crest")

    private const val FFT = 1024
    private const val HOP = 512
    private const val EPS = 1e-9

    fun extract(x0: FloatArray, sr: Int): DoubleArray {
        if (x0.isEmpty()) return DoubleArray(names.size)
        val x = loudestWindow(x0, sr, 0.7)

        // --- temporal ---
        var peak = 0.0; var sumSq = 0.0; var zc = 0
        for (i in x.indices) {
            val v = x[i].toDouble(); val a = abs(v)
            if (a > peak) peak = a; sumSq += v * v
            if (i > 0 && (x[i] >= 0f) != (x[i - 1] >= 0f)) zc++
        }
        val rms = sqrt(sumSq / x.size)
        val crest = peak / (rms + EPS)
        val zcr = zc.toDouble() / x.size

        val env = rmsEnvelope(x, sr)
        val envMax = env.maxOrNull() ?: 0.0
        val envMed = median(env)
        var onset = 0.0
        for (i in 1 until env.size) onset = maxOf(onset, env[i] - env[i - 1])
        val envMean = env.average().coerceAtLeast(EPS)
        val onsetSharp = onset / envMean
        val envPeakRatio = envMax / (envMed + EPS)
        val activeFrac = env.count { it > 0.5 * envMax }.toDouble() / env.size

        // --- spectral (averaged over frames) ---
        var centroid = 0.0; var flatness = 0.0; var rolloff = 0.0; var bandwidth = 0.0
        var hfRatio = 0.0; var qRatio = 0.0; var crestSpec = 0.0; var nf = 0
        val hann = FloatArray(FFT) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / (FFT - 1)) }
        val re = FloatArray(FFT); val im = FloatArray(FFT)
        val bins = FFT / 2
        val binHz = sr.toDouble() / FFT
        var f = 0
        while (f + FFT <= x.size) {
            for (i in 0 until FFT) { re[i] = x[f + i] * hann[i]; im[i] = 0f }
            FFTUtils.compute(re, im)
            var sumP = 0.0; var sumFP = 0.0; var sumLnP = 0.0
            var eLow = 0.0; var eHigh = 0.0; var eHF = 0.0
            val p = DoubleArray(bins)
            for (k in 1 until bins) {
                val pw = (re[k] * re[k] + im[k] * im[k]).toDouble()
                p[k] = pw; val hz = k * binHz
                sumP += pw; sumFP += hz * pw; sumLnP += ln(pw + EPS)
                if (hz in 60.0..600.0) eLow += pw
                if (hz in 600.0..6000.0) eHigh += pw
                if (hz > 2000.0) eHF += pw
            }
            if (sumP > EPS) {
                val c = sumFP / sumP
                centroid += c
                flatness += Math.exp(sumLnP / (bins - 1)) / (sumP / (bins - 1))
                // rolloff 85%
                var cum = 0.0; var roll = 0.0
                for (k in 1 until bins) { cum += p[k]; if (cum >= 0.85 * sumP) { roll = k * binHz; break } }
                rolloff += roll
                var bw = 0.0; for (k in 1 until bins) bw += (k * binHz - c) * (k * binHz - c) * p[k]
                bandwidth += sqrt(bw / sumP)
                hfRatio += eHF / sumP
                qRatio += eLow / (eHigh + EPS)
                var mx = 0.0; for (k in 1 until bins) if (p[k] > mx) mx = p[k]
                crestSpec += mx / (sumP / (bins - 1))
                nf++
            }
            f += HOP
        }
        if (nf > 0) { centroid /= nf; flatness /= nf; rolloff /= nf; bandwidth /= nf; hfRatio /= nf; qRatio /= nf; crestSpec /= nf }

        val pitch = pitchStrength(x, sr)
        val syllabic = syllabicMod(x0, sr)   // speech/counting rhythm, over the WHOLE clip

        return doubleArrayOf(crest, zcr, onsetSharp, envPeakRatio, activeFrac,
            centroid, flatness, rolloff, bandwidth, hfRatio, qRatio, pitch, syllabic, crestSpec)
    }

    /** Fraction of envelope-modulation energy in 3–8 Hz (syllabic rate) — high for speech/counting,
     *  low for a single impulsive cough. Computed on the whole clip's RMS envelope (~100 Hz). */
    private fun syllabicMod(x0: FloatArray, sr: Int): Double {
        val env = rmsEnvelope(x0, sr)
        if (env.size < 16) return 0.0
        val envSr = sr / maxOf(1, (0.010 * sr).toInt()).toDouble()   // ~100 Hz
        val m = env.average()
        val n = FFTUtils.nextPowerOfTwo(env.size)
        val re = FloatArray(n); val im = FloatArray(n)
        for (i in env.indices) re[i] = (env[i] - m).toFloat()
        FFTUtils.compute(re, im)
        var band = 0.0; var total = 0.0
        for (k in 1 until n / 2) {
            val hz = k * envSr / n
            val pw = (re[k] * re[k] + im[k] * im[k]).toDouble()
            total += pw
            if (hz in 3.0..8.0) band += pw
        }
        return if (total > EPS) band / total else 0.0
    }

    /** Loudest [winSec]-second window (by RMS envelope), or the whole clip if shorter. */
    private fun loudestWindow(x: FloatArray, sr: Int, winSec: Double): FloatArray {
        val w = (winSec * sr).toInt()
        if (x.size <= w) return x
        val env = rmsEnvelope(x, sr)
        val hop = maxOf(1, (0.010 * sr).toInt())
        var best = 0; var bestE = -1.0
        val frames = w / hop
        for (s in 0..env.size - frames) {
            var e = 0.0; for (i in s until s + frames) e += env[i]
            if (e > bestE) { bestE = e; best = s }
        }
        val start = (best * hop).coerceIn(0, x.size - w)
        return x.copyOfRange(start, start + w)
    }

    private fun rmsEnvelope(x: FloatArray, sr: Int): DoubleArray {
        val win = maxOf(1, (0.025 * sr).toInt()); val hop = maxOf(1, (0.010 * sr).toInt())
        val n = (x.size - win) / hop + 1
        if (n <= 0) return doubleArrayOf(sqrt(x.map { it.toDouble() * it }.average()))
        return DoubleArray(n) { fr ->
            var s = 0.0; val o = fr * hop
            for (i in 0 until win) s += x[o + i].toDouble() * x[o + i]
            sqrt(s / win)
        }
    }

    private fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sorted(); return s[s.size / 2]
    }

    /** Normalised autocorrelation peak in the 80–400 Hz pitch range, on the loudest 2048-frame. */
    private fun pitchStrength(x: FloatArray, sr: Int): Double {
        val n = 2048
        if (x.size < n) return 0.0
        // loudest 2048 frame
        var best = 0; var bestE = -1.0
        var i = 0
        while (i + n <= x.size) {
            var e = 0.0; for (j in i until i + n) e += x[j].toDouble() * x[j]
            if (e > bestE) { bestE = e; best = i }; i += n / 2
        }
        val size = FFTUtils.nextPowerOfTwo(2 * n)
        val re = FloatArray(size); val im = FloatArray(size)
        for (j in 0 until n) re[j] = x[best + j]
        FFTUtils.compute(re, im)
        for (k in 0 until size) { re[k] = re[k] * re[k] + im[k] * im[k]; im[k] = 0f }  // power
        FFTUtils.inverse(re, im)                                                       // autocorr
        val r0 = re[0].toDouble()
        if (r0 < EPS) return 0.0
        val loLag = sr / 400; val hiLag = (sr / 80).coerceAtMost(n - 1)
        var pk = 0.0
        for (lag in loLag..hiLag) pk = maxOf(pk, re[lag].toDouble() / r0)
        return pk
    }
}
