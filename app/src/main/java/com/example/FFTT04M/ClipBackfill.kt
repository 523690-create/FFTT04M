package com.example.FFTT04M

import android.content.Context
import android.graphics.Bitmap
import com.example.FFTT04M.cough.ClipMatcher
import java.io.File
import java.io.FileOutputStream

/**
 * "Dig deeper into clip storage": one-time backfill over already-stored recordings. For each `.wav`
 * missing its `.png` spectrogram icon and/or its `.txt` match comment, read the clip once and
 * generate whichever is missing — the offline FFT thumbnail ([SpectrogramThumb]) and/or the nearest
 * cough/non-cough DB match ([ClipMatcher]). Idempotent and safe to re-run (skips clips that already
 * have both). Runs on a background thread; [onProgress] fires after each clip that changed so an open
 * gallery can refresh.
 */
object ClipBackfill {

    @Volatile private var running = false

    fun run(ctx: Context, onProgress: (() -> Unit)? = null) {
        if (running) return
        running = true
        try {
            val dir = GalleryTransfer.recordingsDir(ctx) ?: ctx.filesDir
            val wavs = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }
                ?: return
            for (wav in wavs) {
                val base = wav.nameWithoutExtension
                val png = File(dir, "$base.png")
                val txt = File(dir, "$base.txt")
                val needIcon = !png.exists()
                val needComment = !txt.exists()
                if (!needIcon && !needComment) continue
                val data = try { WavReader.read(wav) } catch (_: Throwable) { continue }
                var changed = false
                if (needComment) {
                    runCatching { ClipMatcher.annotate(ctx, wav, data.samples, data.sampleRate) }
                    if (txt.exists()) changed = true
                }
                if (needIcon) {
                    runCatching {
                        SpectrogramThumb.render(ctx, data.samples, data.sampleRate)?.let { bmp ->
                            FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bmp.recycle()
                            changed = true
                        }
                    }
                }
                if (changed) onProgress?.invoke()
            }
        } catch (_: Throwable) {
            // best-effort backfill; never crash the gallery
        } finally {
            running = false
        }
    }
}
