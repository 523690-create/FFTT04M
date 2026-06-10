package com.example.FFTT04M.cough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Offline tests for the streaming auto-capture detector. */
class CoughDetectorTest {

    private val sr = 44100

    private fun noise(durSec: Double, amp: Double, rng: Random): FloatArray =
        FloatArray((durSec * sr).toInt()) { (amp * (rng.nextDouble() * 2 - 1)).toFloat() }

    private fun tone(freq: Double, durSec: Double): FloatArray =
        FloatArray((durSec * sr).toInt()) { (0.5 * sin(2 * PI * freq * it / sr)).toFloat() }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    /** Feed a stream in small, irregular chunks to exercise block-boundary handling. */
    private fun run(stream: FloatArray): List<CoughDetector.CapturedCough> {
        val caught = ArrayList<CoughDetector.CapturedCough>()
        val det = CoughDetector(sr) { caught.add(it) }
        var i = 0
        val sizes = intArrayOf(513, 1024, 200, 4096, 777)
        var s = 0
        while (i < stream.size) {
            val len = minOf(sizes[s % sizes.size], stream.size - i)
            det.process(stream.copyOfRange(i, i + len))
            i += len; s++
        }
        det.finish()
        return caught
    }

    @Test fun capturesOneCoughBurst() {
        val rng = Random(11)
        val stream = concat(noise(0.6, 0.001, rng), noise(0.4, 0.5, rng), noise(0.6, 0.001, rng))
        val caught = run(stream)
        assertEquals("one cough captured", 1, caught.size)
        val durMs = caught[0].pcm.size.toDouble() * 1000 / sr
        assertTrue("captured audio is non-trivial ($durMs ms)", durMs > 300)
    }

    @Test fun rejectsTonalSpeechLikeBurst() {
        val rng = Random(12)
        val stream = concat(noise(0.6, 0.001, rng), tone(220.0, 0.5), noise(0.6, 0.001, rng))
        val caught = run(stream)
        assertEquals("tonal/periodic burst is rejected as speech", 0, caught.size)
    }

    @Test fun rejectsTooShortBurst() {
        val rng = Random(13)
        val stream = concat(noise(0.6, 0.001, rng), noise(0.08, 0.5, rng), noise(0.6, 0.001, rng))
        val caught = run(stream)
        assertEquals("80ms burst below the 200ms floor is rejected", 0, caught.size)
    }

    @Test fun capturesTwoSeparatedCoughs() {
        val rng = Random(14)
        val stream = concat(
            noise(0.5, 0.001, rng), noise(0.35, 0.5, rng),
            noise(0.5, 0.001, rng), noise(0.35, 0.5, rng), noise(0.5, 0.001, rng),
        )
        val caught = run(stream)
        assertEquals("two distinct coughs", 2, caught.size)
    }
}
