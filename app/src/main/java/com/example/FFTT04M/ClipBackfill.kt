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
        var icons = 0; var comments = 0; var failed = 0
        val t0 = System.currentTimeMillis()
        try {
            val dir = GalleryTransfer.recordingsDir(ctx) ?: ctx.filesDir
            val wavs = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }
                ?: return
            android.util.Log.i("FFTT04M", "ClipBackfill: scanning ${wavs.size} clips in ${dir.absolutePath}")
            for (wav in wavs) {
                val base = wav.nameWithoutExtension
                val png = File(dir, "$base.png")
                val txt = File(dir, "$base.txt")
                val needIcon = !png.exists()
                // Comment needed if missing OR an OLD single-line auto-match to upgrade to top-3.
                // (A user comment, or one already in top-N format, is left alone — no re-analysis.)
                val existing = if (txt.exists()) runCatching { txt.readText() }.getOrNull() else null
                val needComment = existing == null ||
                    (existing.startsWith("auto-match") && !existing.contains("conf "))
                if (!needIcon && !needComment) continue
                val data = try { WavReader.read(wav) } catch (e: Throwable) {
                    failed++; android.util.Log.w("FFTT04M", "ClipBackfill: read failed ${wav.name}: ${e.message}"); continue
                }
                var changed = false
                if (needComment) {
                    runCatching { ClipMatcher.annotate(ctx, wav, data.samples, data.sampleRate) }
                    if (txt.exists()) { comments++; changed = true }
                }
                if (needIcon) {
                    runCatching {
                        SpectrogramThumb.render(ctx, data.samples, data.sampleRate)?.let { bmp ->
                            FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bmp.recycle()
                            icons++; changed = true
                        }
                    }
                }
                if (changed) onProgress?.invoke()
            }
        } catch (e: Throwable) {
            android.util.Log.w("FFTT04M", "ClipBackfill aborted: ${e.message}")
        } finally {
            running = false
            android.util.Log.i("FFTT04M",
                "ClipBackfill done: +$icons icons, +$comments comments, $failed failed, " +
                "${System.currentTimeMillis() - t0}ms (matcher ready=${ClipMatcher.isReady})")
        }
    }
}
