package com.example.FFTT04M.cough

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal 16-bit mono PCM WAV writer, byte-compatible with the app's recordings (so auto-captured
 * coughs appear in the Gallery and decode via WavReader). Mirrors MainActivity.encodeWav.
 */
object CoughWav {
    fun write(file: File, data: FloatArray, sampleRate: Int) {
        val dataSize = data.size * 2
        FileOutputStream(file).use { fos ->
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(36 + dataSize)
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16)
            bb.putShort(1)                       // PCM
            bb.putShort(1)                       // mono
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * 2)            // byte rate (mono, 16-bit)
            bb.putShort(2)                       // block align
            bb.putShort(16)                      // bits per sample
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataSize)
            fos.write(header)

            val pcm = ByteArray(dataSize)
            val pb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            for (s in data) pb.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            fos.write(pcm)
        }
    }
}
