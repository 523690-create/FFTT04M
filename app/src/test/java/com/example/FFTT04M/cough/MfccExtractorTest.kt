package com.example.FFTT04M.cough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MfccExtractorTest {

    private val sr = 44100
    private fun tone(freq: Double, durSec: Double) =
        FloatArray((durSec * sr).toInt()) { (0.5 * sin(2 * PI * freq * it / sr)).toFloat() }

    @Test fun producesFixedLengthCoefficients() {
        val m = MfccExtractor().extract(tone(1000.0, 0.3), 0, (0.3 * sr).toInt(), sr)
        assertEquals(13, m.numCoeffs)
        assertEquals(13, m.mean.size)
        assertEquals(13, m.std.size)
        assertTrue("frames were produced", m.frameCount > 5)
        for (v in m.mean) assertTrue("finite coeff", v.isFinite())
    }

    @Test fun isDeterministic() {
        val x = tone(1000.0, 0.3)
        val a = MfccExtractor().extract(x, 0, x.size, sr)
        val b = MfccExtractor().extract(x, 0, x.size, sr)
        for (k in 0 until a.numCoeffs) assertEquals(a.mean[k], b.mean[k], 1e-12)
    }

    @Test fun discriminatesDifferentTones() {
        val low = MfccExtractor().extract(tone(300.0, 0.3), 0, (0.3 * sr).toInt(), sr)
        val high = MfccExtractor().extract(tone(3000.0, 0.3), 0, (0.3 * sr).toInt(), sr)
        var dist = 0.0
        for (k in 0 until low.numCoeffs) { val d = low.mean[k] - high.mean[k]; dist += d * d }
        assertTrue("different tones → different MFCCs (dist=${kotlin.math.sqrt(dist)})", dist > 1.0)
    }
}
