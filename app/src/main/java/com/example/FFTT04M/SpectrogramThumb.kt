package com.example.FFTT04M

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Offline FFT-spectrogram thumbnail for a stored clip — used to backfill the gallery icon of
 * recordings saved without one. Mirrors the live FFTHeatMapView pipeline so the look matches:
 * 2048-pt FFT, 1024 hop, Hann window, magnitude in dB normalized `(20·log10(m)+80)/80`, log
 * frequency axis 80 Hz–10 kHz (high at top), coloured with the user's current global scheme.
 */
object SpectrogramThumb {

    private const val FFT = 2048
    private const val HOP = 1024
    private const val H = 128          // render height (freq rows); scaled to 256² to match live icons
    private const val MIN_F = 80.0
    private const val MAX_F = 10000.0

    /** Render [pcm] to a 256×256 spectrogram PNG-ready bitmap, or null if the clip is too short. */
    fun render(ctx: Context, pcm: FloatArray, sampleRate: Int): Bitmap? {
        if (pcm.size < FFT) return null
        val frames = 1 + (pcm.size - FFT) / HOP
        if (frames < 1) return null
        val cols = frames.coerceAtMost(512)
        val palette = ColorMaps.lut(ColorMaps.loadGlobal(ctx))
        val hann = FloatArray(FFT) { (0.5 * (1 - cos(2 * Math.PI * it / (FFT - 1)))).toFloat() }
        val logMin = log10(MIN_F)
        val logMax = log10(MAX_F)
        val nb = FFT / 2

        val bmp = Bitmap.createBitmap(cols, H, Bitmap.Config.ARGB_8888)
        val real = FloatArray(FFT)
        val imag = FloatArray(FFT)
        val colPix = IntArray(H)

        for (c in 0 until cols) {
            val off = c * HOP
            for (i in 0 until FFT) { real[i] = pcm[off + i] * hann[i]; imag[i] = 0f }
            FFTUtils.compute(real, imag)   // in-place FFT (same routine as the live view)
            for (y in 0 until H) {
                val logF = logMax - (y.toDouble() / H) * (logMax - logMin)
                val freq = 10.0.pow(logF)
                val binExact = freq * FFT / sampleRate
                val i1 = binExact.toInt().coerceIn(0, nb - 1)
                val i2 = (i1 + 1).coerceAtMost(nb - 1)
                val frac = (binExact - i1).toFloat()
                val m1 = sqrt(real[i1] * real[i1] + imag[i1] * imag[i1])
                val m2 = sqrt(real[i2] * real[i2] + imag[i2] * imag[i2])
                val mg = m1 + (m2 - m1) * frac
                val norm = ((20f * log10(mg + 1e-9f) + 80f) / 80f).coerceIn(0f, 1f)
                colPix[y] = palette[(norm * 255).toInt().coerceIn(0, 255)]
            }
            bmp.setPixels(colPix, 0, 1, c, 0, 1, H)
        }
        val scaled = Bitmap.createScaledBitmap(bmp, 256, 256, true)
        if (scaled != bmp) bmp.recycle()
        return scaled
    }
}
