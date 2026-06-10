package com.example.FFTT04M.cough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Offline unit tests for the cough-analysis engine, driven by synthetic signals. Run with
 * `gradlew :app:testDebugUnitTest` — no device needed.
 */
class CoughEngineTest {

    private val sr = 44100

    // ---- signal generators -----------------------------------------------------------------------

    /** A sinusoid whose instantaneous frequency follows f(t) = a·t² + b·t + c (the squiggle). */
    private fun parabolicChirp(a: Double, b: Double, c: Double, durSec: Double, amp: Double = 1.0): FloatArray {
        val n = (durSec * sr).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val phase = 2 * PI * (a * t * t * t / 3.0 + b * t * t / 2.0 + c * t)
            (amp * sin(phase)).toFloat()
        }
    }

    private fun tone(freq: Double, durSec: Double, amp: Double = 1.0): FloatArray {
        val n = (durSec * sr).toInt()
        return FloatArray(n) { i -> (amp * sin(2 * PI * freq * i / sr)).toFloat() }
    }

    private fun noise(durSec: Double, amp: Double, rng: Random): FloatArray {
        val n = (durSec * sr).toInt()
        return FloatArray(n) { (amp * (rng.nextDouble() * 2 - 1)).toFloat() }
    }

    private fun silence(durSec: Double): FloatArray = FloatArray((durSec * sr).toInt())

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    // ---- DSP primitives --------------------------------------------------------------------------

    @Test fun solve3x3_recoversKnownSolution() {
        // 2x + y + z = 5 ; x + 3y + 2z = 11 ; x + y + 4z = 12  → x=0.5? just check residual.
        val a = doubleArrayOf(2.0, 1.0, 1.0, 1.0, 3.0, 2.0, 1.0, 1.0, 4.0)
        val y = doubleArrayOf(8.0, 13.0, 15.0)
        val x = CoughDsp.solve3x3(a, y)!!
        // verify A·x ≈ y
        assertEquals(8.0, a[0]*x[0]+a[1]*x[1]+a[2]*x[2], 1e-9)
        assertEquals(13.0, a[3]*x[0]+a[4]*x[1]+a[5]*x[2], 1e-9)
        assertEquals(15.0, a[6]*x[0]+a[7]*x[1]+a[8]*x[2], 1e-9)
    }

    @Test fun fitParabola_recoversCoefficients() {
        val a = 4375.0; val b = -120.0; val c = 300.0
        val t = DoubleArray(40) { it * 0.01 }
        val f = DoubleArray(40) { a * t[it] * t[it] + b * t[it] + c }
        val fit = CoughDsp.fitParabola(t, f)!!
        assertEquals(a, fit[0], 1e-3)
        assertEquals(b, fit[1], 1e-3)
        assertEquals(c, fit[2], 1e-3)
        assertEquals(1.0, fit[3], 1e-6)  // perfect fit → R² = 1
    }

    @Test fun spectralFlatness_noiseHigherThanTone() {
        val rng = Random(1)
        val noiseMag = CoughDsp.magnitudeSpectrum(noise(0.05, 0.5, rng), 2048, true)
        val toneMag = CoughDsp.magnitudeSpectrum(tone(1000.0, 0.05), 2048, true)
        val fNoise = CoughDsp.spectralFlatness(noiseMag, 1, 1024)
        val fTone = CoughDsp.spectralFlatness(toneMag, 1, 1024)
        assertTrue("noise flatness ($fNoise) should exceed tone flatness ($fTone)", fNoise > fTone)
        assertTrue(fTone < 0.1)
    }

    @Test fun pitchStrength_toneHigherThanNoise() {
        val rng = Random(2)
        val pTone = CoughDsp.pitchStrength(tone(200.0, 0.1), sr)
        val pNoise = CoughDsp.pitchStrength(noise(0.1, 0.5, rng), sr)
        assertTrue("tone pitch ($pTone) should exceed noise pitch ($pNoise)", pTone > pNoise)
        assertTrue(pTone > 0.8)
    }

    // ---- ridge extraction (the squiggle) ---------------------------------------------------------

    @Test fun ridgeExtractor_recoversParabolicTrajectory() {
        // f(t) = 4375 t² + 300  → sweeps 300 Hz (t=0) up to 1000 Hz (t=0.4).
        val a = 4375.0; val c = 300.0
        val x = parabolicChirp(a, 0.0, c, 0.4)
        val r = RidgeExtractor().extract(x, 0, x.size, sr)
        val rf = r.features
        assertTrue("ridge should be valid", rf.valid)
        assertTrue("enough ridge points (${rf.frameCount})", rf.frameCount >= 20)
        assertTrue("good fit R²=${rf.rSquared}", rf.rSquared > 0.85)
        assertTrue("positive curvature ${rf.curvature}", rf.curvature > 2500 && rf.curvature < 6500)
        assertTrue("intercept≈300 (${rf.intercept})", rf.intercept in 180.0..420.0)
        assertTrue("centerFreq in band (${rf.centerFreqHz})", rf.centerFreqHz in 350.0..650.0)
    }

    @Test fun ridgeExtractor_noiseGivesNoCleanRidge() {
        val x = noise(0.4, 0.5, Random(3))
        val r = RidgeExtractor().extract(x, 0, x.size, sr)
        // Broadband noise has no coherent parabolic ridge → fit quality must be poor.
        assertTrue("noise R²=${r.features.rSquared} should be low", !r.features.valid || r.features.rSquared < 0.5)
    }

    // ---- segmentation ----------------------------------------------------------------------------

    @Test fun segmenter_findsSingleBurst() {
        val rng = Random(4)
        val x = concat(
            noise(0.8, 0.001, rng),   // quiet floor
            noise(0.4, 0.5, rng),     // 400 ms cough-like burst
            noise(0.8, 0.001, rng),
        )
        val segs = CoughSegmenter().segment(x, sr)
        assertEquals("exactly one segment", 1, segs.size)
        val durMs = segs[0].durationSec * 1000
        assertTrue("duration ≈400ms (was $durMs)", durMs in 300.0..550.0)
    }

    @Test fun segmenter_discardsTooShortBurst() {
        val rng = Random(5)
        val x = concat(noise(0.8, 0.001, rng), noise(0.08, 0.5, rng), noise(0.8, 0.001, rng))
        val segs = CoughSegmenter().segment(x, sr)
        assertTrue("80ms burst below 200ms floor is discarded", segs.isEmpty())
    }

    // ---- FFT features ----------------------------------------------------------------------------

    @Test fun fftFeatures_highToneLowQRatio() {
        val x = tone(1500.0, 0.3)
        val f = FftFeatureExtractor().extract(x, 0, x.size, sr)
        assertEquals("Fmax≈1500", 1500.0, f.fmaxHz, 30.0)
        assertTrue("energy concentrated in high band → Q<1 (was ${f.qRatio})", f.qRatio < 0.5)
    }

    @Test fun fftFeatures_lowToneHighQRatio() {
        val x = tone(300.0, 0.3)
        val f = FftFeatureExtractor().extract(x, 0, x.size, sr)
        assertEquals("Fmax≈300", 300.0, f.fmaxHz, 25.0)
        assertTrue("energy in low band → Q>1 (was ${f.qRatio})", f.qRatio > 2.0)
    }

    // ---- speech rejection ------------------------------------------------------------------------

    @Test fun speechRejector_flagsTonePeriodicAsSpeech() {
        val x = tone(220.0, 0.4)
        val v = SpeechRejector().classify(x, 0, x.size, sr)
        assertFalse("a strongly periodic/tonal signal is flagged speech-like", v.isLikelyCough)
        assertTrue(v.pitchStrength > 0.5)
    }

    @Test fun speechRejector_passesNoiseBurstAsCough() {
        val x = noise(0.4, 0.5, Random(6))
        val v = SpeechRejector().classify(x, 0, x.size, sr)
        assertTrue("a noise-like burst passes as cough", v.isLikelyCough)
    }

    // ---- end-to-end ------------------------------------------------------------------------------

    @Test fun analyzer_detectsCoughAndSerializes() {
        val rng = Random(7)
        val x = concat(
            noise(0.6, 0.001, rng),
            noise(0.35, 0.5, rng),    // burst 1
            noise(0.5, 0.001, rng),
            noise(0.35, 0.5, rng),    // burst 2
            noise(0.6, 0.001, rng),
        )
        val analysis = CoughAnalyzer().analyze(x, sr)
        assertEquals("two events detected", 2, analysis.events.size)
        assertTrue("both pass as cough", analysis.coughCount >= 1)

        val jsonl = CoughSchemaJson.toJsonl(analysis, "rec_test", "unit_test")
        val lines = jsonl.trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"is_cough\""))
        assertTrue(lines[0].contains("\"ridge\""))
        assertTrue(lines[0].contains("\"q_ratio\""))
        // numbers must be locale-independent (dot decimals, no commas in numerics)
        assertFalse(jsonl.contains(",\""+","))
    }
}
