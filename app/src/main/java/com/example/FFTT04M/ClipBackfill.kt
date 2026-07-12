package com.example.FFTT04M

import android.content.Context
import android.graphics.Bitmap
import com.example.FFTT04M.cough.PhonemeDecoder
import java.io.File
import java.io.FileOutputStream

/**
 * "Dig deeper into clip storage": backfill over stored recordings. For each `.wav` missing its `.png`
 * spectrogram icon and/or its `.phon` phoneme decode, read the clip once and generate whichever is
 * missing — the offline FFT thumbnail ([SpectrogramThumb]) and/or the Spectral-Flux fractionate +
 * phoneme decode ([PhonemeDecoder]). Also a one-time cleanup of the OLD whole-clip "auto-match
 * (top-3)" `.txt` sidecars (now superseded by `.phon`), leaving real user comments untouched.
 * Idempotent and safe to re-run; runs on a background thread; [onProgress] fires after each changed
 * clip so an open gallery can refresh.
 */
object ClipBackfill {

    @Volatile private var running = false
    @Volatile private var cancelled = false

    /** Stop an in-flight backfill (call when leaving the gallery) so its per-clip wav-read + analyze +
     *  bitmap work doesn't pile on top of Listen's allocations and get the process LMK-killed. */
    fun cancel() { cancelled = true }

    fun run(ctx: Context, onProgress: (() -> Unit)? = null) {
        if (running) return
        running = true
        cancelled = false
        var icons = 0; var decodes = 0; var cleaned = 0; var failed = 0
        val t0 = System.currentTimeMillis()
        // One-time migration: delete the OLD whole-clip "auto-match (top-3)" .txt sidecars (now replaced
        // by the .phon phoneme decode). Reading every .txt is slow, so do it once and flag it; afterwards
        // steady-state opens only do cheap set checks for clips missing .phon/.png. New clips never get an
        // auto-match .txt (we don't write them anymore), so cleanup is genuinely one-time.
        val prefs = ctx.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val migrated = prefs.getBoolean("phon_decode_v1", false)
        // One-time: backfill the CoughVote scores into clips decoded before the vote existed (cheap —
        // reuses the cached HuBERT embedding, recomputes only forest + DSP cues). New clips get them at
        // annotate() time, so this is genuinely one-time.
        val votesMigrated = prefs.getBoolean("phon_votes_v1", false)
        var votes = 0
        try {
            val dir = GalleryTransfer.recordingsDir(ctx) ?: ctx.filesDir
            // ONE directory listing; membership via in-memory sets (per-file exists() on emulated
            // storage was ~490 stats / 5s per open). Now steady-state opens are a single listdir.
            val all = dir.listFiles() ?: return
            val wavs = all.filter { it.isFile && it.extension.equals("wav", true) }
            val haveIcon = HashSet<String>(); val haveTxt = HashSet<String>(); val havePhon = HashSet<String>()
            for (f in all) when {
                f.extension.equals("png", true) -> haveIcon.add(f.nameWithoutExtension)
                f.extension.equals("txt", true) -> haveTxt.add(f.nameWithoutExtension)
                f.extension.equals("phon", true) -> havePhon.add(f.nameWithoutExtension)
            }
            android.util.Log.i("FFTT04M", "ClipBackfill: scanning ${wavs.size} clips (migrated=$migrated)")
            for (wav in wavs) {
                if (cancelled) { android.util.Log.i("FFTT04M", "ClipBackfill cancelled (left gallery)"); break }
                val base = wav.nameWithoutExtension
                val png = File(dir, "$base.png")
                val txt = File(dir, "$base.txt")
                var changed = false
                // One-time: drop the stale auto-generated "best 3 matches" .txt; keep real user comments.
                if (!migrated && base in haveTxt) {
                    val existing = runCatching { txt.readText() }.getOrNull()
                    if (existing != null && existing.startsWith("auto-match") && txt.delete()) {
                        cleaned++; changed = true; haveTxt.remove(base)
                    }
                }
                val needIcon = base !in haveIcon
                val needDecode = base !in havePhon
                // Backfill votes only for already-decoded clips that predate the vote (migration pass).
                val existing = if (!votesMigrated && !needDecode) PhonemeDecoder.read(dir, base) else null
                val needVotes = existing != null && existing.voteP == null
                if (needIcon || needDecode || needVotes) {
                    val data = try { WavReader.read(wav) } catch (e: Throwable) {
                        failed++; android.util.Log.w("FFTT04M", "ClipBackfill: read failed ${wav.name}: ${e.message}")
                        if (changed) onProgress?.invoke(); continue
                    }
                    if (needDecode) {
                        runCatching { PhonemeDecoder.annotate(ctx, wav, data.samples, data.sampleRate) }
                        if (File(dir, "$base.phon").exists()) { decodes++; changed = true }
                    } else if (needVotes) {
                        runCatching { PhonemeDecoder.ensureVotes(ctx, wav, data.samples, data.sampleRate, existing!!) }
                        votes++; changed = true
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
                }
                if (changed) onProgress?.invoke()
            }
            // Only mark the migrations done after a COMPLETE (non-cancelled) pass.
            if (!migrated && !cancelled) prefs.edit().putBoolean("phon_decode_v1", true).apply()
            if (!votesMigrated && !cancelled) prefs.edit().putBoolean("phon_votes_v1", true).apply()
        } catch (e: Throwable) {
            android.util.Log.w("FFTT04M", "ClipBackfill aborted: ${e.message}")
        } finally {
            running = false
            android.util.Log.i("FFTT04M",
                "ClipBackfill done: +$icons icons, +$decodes decodes, +$votes votes, $cleaned cleaned, $failed failed, " +
                "${System.currentTimeMillis() - t0}ms (decoder ready=${PhonemeDecoder.isReady})")
        }
    }
}
