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
        // One-time content upgrade (old single-line auto-match → top-3+confidence) requires reading
        // every .txt — slow. After one full pass we set this flag, so steady-state opens only do cheap
        // exists() checks for genuinely new clips (missing .txt/.png), not a full re-read each time.
        val prefs = ctx.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val upgraded = prefs.getBoolean("clip_match_upgraded_v2", false)
        try {
            val dir = GalleryTransfer.recordingsDir(ctx) ?: ctx.filesDir
            // ONE directory listing; membership via in-memory sets (per-file exists() on emulated
            // storage was ~490 stats / 5s per open). Now steady-state opens are a single listdir.
            val all = dir.listFiles() ?: return
            val wavs = all.filter { it.isFile && it.extension.equals("wav", true) }
            val haveIcon = HashSet<String>(); val haveTxt = HashSet<String>()
            for (f in all) when {
                f.extension.equals("png", true) -> haveIcon.add(f.nameWithoutExtension)
                f.extension.equals("txt", true) -> haveTxt.add(f.nameWithoutExtension)
            }
            android.util.Log.i("FFTT04M", "ClipBackfill: scanning ${wavs.size} clips (upgraded=$upgraded)")
            for (wav in wavs) {
                val base = wav.nameWithoutExtension
                val png = File(dir, "$base.png")
                val txt = File(dir, "$base.txt")
                val needIcon = base !in haveIcon
                // After the one-time upgrade, only a MISSING .txt needs work. Before it, read content
                // for clips that have a .txt to upgrade old auto-match comments (user comments left be).
                val needComment = if (upgraded) {
                    base !in haveTxt
                } else {
                    val existing = if (base in haveTxt) runCatching { txt.readText() }.getOrNull() else null
                    existing == null || (existing.startsWith("auto-match") && !existing.contains("conf "))
                }
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
            // Full pass completed: future opens skip the per-clip content re-read.
            if (!upgraded) prefs.edit().putBoolean("clip_match_upgraded_v2", true).apply()
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
