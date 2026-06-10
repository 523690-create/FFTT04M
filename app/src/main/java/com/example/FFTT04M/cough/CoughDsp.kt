package com.example.FFTT04M.cough

import com.example.FFTT04M.FFTUtils
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Low-level DSP primitives shared by the cough-analysis engine. Pure Kotlin (FloatArray +
 * kotlin.math), no Android dependencies, so it is fully unit-testable on the JVM. FFTs reuse the
 * project's [FFTUtils] (in-place radix-2).
 */
object CoughDsp {

    /** A Hann window of length [n] (cached per size). */
    private val hannCache = HashMap<Int, FloatArray>()
    fun hann(n: Int): FloatArray = hannCache.getOrPut(n) {
        FloatArray(n) { i ->
            if (n <= 1) 1f else (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (n - 1))).toFloat()
        }
    }

    /** Short-time RMS energy envelope: one value per hop. [win]/[hop] in samples. */
    fun rmsEnvelope(x: FloatArray, win: Int, hop: Int): FloatArray {
        if (x.isEmpty() || win <= 0 || hop <= 0) return FloatArray(0)
        val frames = max(0, (x.size - win) / hop + 1)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            val start = f * hop
            var sum = 0.0
            for (i in 0 until win) { val v = x[start + i]; sum += v.toDouble() * v }
            out[f] = sqrt(sum / win).toFloat()
        }
        return out
    }

    /** Median of a copy of [v] (does not mutate the input). */
    fun median(v: FloatArray): Float {
        if (v.isEmpty()) return 0f
        val s = v.copyOf(); s.sort()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    /**
     * One-sided magnitude spectrum of [frame] zero-padded to [fftSize] (power of two). Returns
     * fftSize/2 bins; bin k corresponds to frequency k·sr/fftSize. Optionally Hann-windowed.
     */
    fun magnitudeSpectrum(frame: FloatArray, fftSize: Int, window: Boolean): FloatArray {
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val n = min(frame.size, fftSize)
        if (window) {
            val w = hann(n)
            for (i in 0 until n) re[i] = frame[i] * w[i]
        } else {
            for (i in 0 until n) re[i] = frame[i]
        }
        FFTUtils.compute(re, im)
        val half = fftSize / 2
        val mag = FloatArray(half)
        for (k in 0 until half) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        return mag
    }

    /** Frequency (Hz) of FFT bin [k] at [sampleRate] and [fftSize]. */
    fun binToHz(k: Int, sampleRate: Int, fftSize: Int): Double = k.toDouble() * sampleRate / fftSize

    /** FFT bin index nearest to [hz]. */
    fun hzToBin(hz: Double, sampleRate: Int, fftSize: Int): Int =
        (hz * fftSize / sampleRate).toInt()

    /**
     * Sub-bin peak location around bin [k] using parabolic interpolation on the (log-ish) magnitudes
     * at k-1, k, k+1. Returns the fractional bin offset in [-0.5, 0.5]; 0 if the peak is at an edge
     * or the curvature is degenerate.
     */
    fun parabolicPeakOffset(mag: FloatArray, k: Int): Double {
        if (k <= 0 || k >= mag.size - 1) return 0.0
        val a = mag[k - 1].toDouble()
        val b = mag[k].toDouble()
        val c = mag[k + 1].toDouble()
        val denom = a - 2 * b + c
        if (abs(denom) < 1e-12) return 0.0
        return (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
    }

    /**
     * Spectral flatness (Wiener entropy) of [mag] over bins [lo, hi): geometric mean / arithmetic
     * mean, in [0, 1]. High (→1) = flat / noise-like (cough); low (→0) = peaky / tonal (voiced
     * speech). Ignores non-positive bins.
     */
    fun spectralFlatness(mag: FloatArray, lo: Int, hi: Int): Float {
        val a = lo.coerceIn(0, mag.size)
        val b = hi.coerceIn(0, mag.size)
        if (b - a <= 0) return 0f
        var logSum = 0.0
        var arith = 0.0
        var count = 0
        for (k in a until b) {
            val m = mag[k].toDouble()
            if (m > 1e-12) { logSum += ln(m); arith += m; count++ }
        }
        if (count == 0 || arith <= 0) return 0f
        val geo = kotlin.math.exp(logSum / count)
        return (geo / (arith / count)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Pitch strength via normalized autocorrelation: the maximum normalized autocorrelation over
     * lags corresponding to [minHz, maxHz]. ~1 = strongly periodic (voiced); ~0 = noise-like.
     */
    fun pitchStrength(x: FloatArray, sampleRate: Int, minHz: Double = 70.0, maxHz: Double = 500.0): Float {
        if (x.size < 64) return 0f
        val minLag = max(1, (sampleRate / maxHz).toInt())
        val maxLag = min(x.size - 1, (sampleRate / minHz).toInt())
        if (maxLag <= minLag) return 0f
        var energy = 0.0
        for (v in x) energy += v.toDouble() * v
        if (energy < 1e-12) return 0f
        var best = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            val n = x.size - lag
            for (i in 0 until n) sum += x[i].toDouble() * x[i + lag]
            val norm = sum / energy
            if (norm > best) best = norm
        }
        return best.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Solve the 3×3 linear system A·x = y (A given row-major, length 9; y length 3) via Gaussian
     * elimination with partial pivoting. Returns null if singular.
     */
    fun solve3x3(a: DoubleArray, y: DoubleArray): DoubleArray? {
        val m = arrayOf(
            doubleArrayOf(a[0], a[1], a[2], y[0]),
            doubleArrayOf(a[3], a[4], a[5], y[1]),
            doubleArrayOf(a[6], a[7], a[8], y[2]),
        )
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-15) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until 3) {
                if (r == col) continue
                val factor = m[r][col] / m[col][col]
                for (cc in col until 4) m[r][cc] -= factor * m[col][cc]
            }
        }
        return doubleArrayOf(m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2])
    }

    /**
     * Least-squares quadratic fit f ≈ a·t² + b·t + c over the points ([t], [f]). Returns
     * [a, b, c] plus R² as the 4th element, or null if fewer than 3 points / singular.
     */
    fun fitParabola(t: DoubleArray, f: DoubleArray): DoubleArray? {
        val n = t.size
        if (n < 3 || f.size != n) return null
        var s0 = n.toDouble(); var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var sf = 0.0; var stf = 0.0; var st2f = 0.0
        for (i in 0 until n) {
            val ti = t[i]; val ti2 = ti * ti
            s1 += ti; s2 += ti2; s3 += ti2 * ti; s4 += ti2 * ti2
            sf += f[i]; stf += ti * f[i]; st2f += ti2 * f[i]
        }
        // Normal equations for [a (t²), b (t), c (1)]:
        val mat = doubleArrayOf(s4, s3, s2, s3, s2, s1, s2, s1, s0)
        val rhs = doubleArrayOf(st2f, stf, sf)
        val sol = solve3x3(mat, rhs) ?: return null
        val (a, b, c) = sol
        var ssRes = 0.0; var ssTot = 0.0; val mean = sf / n
        for (i in 0 until n) {
            val pred = a * t[i] * t[i] + b * t[i] + c
            ssRes += (f[i] - pred) * (f[i] - pred)
            ssTot += (f[i] - mean) * (f[i] - mean)
        }
        val r2 = if (ssTot > 1e-12) 1.0 - ssRes / ssTot else 1.0
        return doubleArrayOf(a, b, c, r2)
    }
}
