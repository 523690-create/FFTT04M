package com.example.FFTT04M.cough

import android.content.Context
import android.util.Log
import com.example.FFTT04M.DeviceCaps
import java.io.File

/**
 * Autonomous rejection of high-confidence NON-cough captures (Tier 1+).
 *
 * The cough detector occasionally fires on background noise or speech. When the on-device decoder
 * labels a FRESH capture as noise/voice with high confidence, the clip is moved out of the gallery
 * into a recoverable `rejected/` subfolder — NOT hard-deleted: a misfire must never lose data, and the
 * subfolder is excluded from the gallery, the clip-cloud, and the USB offer (all list the recordings
 * dir non-recursively). Only new, un-commented clips are eligible — anything the user has labelled with
 * a `.txt` is hands-off.
 *
 * Conservative by design: the threshold is high (≥0.8 of assigned windows agree on noise/voice), so a
 * real cough — which rarely decodes as mostly noise/voice — is very unlikely to be rejected. Recover
 * by moving files back out of `<recordings>/rejected/`.
 */
object AutoReject {
    private const val TAG = "AutoReject"
    const val SUBDIR = "rejected"
    private val NON_COUGH = setOf("noise", "voice")
    private const val MIN_CONFIDENCE = 0.8
    private const val COUGH_PROB_FLOOR = 0.25f   // reject when the trained head is this confident it's NOT a cough

    fun eligible(ctx: Context, d: PhonemeDecoder.Decoded): Boolean {
        if (DeviceCaps.tier(ctx) < 1) return false
        // The multi-class decoder is the RELIABLE signal: it's trained on the user's own labelled clips,
        // so it correctly IDs their voice/noise. The bundled binary head was trained on ALLDATA
        // (coswara/urban8k) voice, which is OUT-OF-DISTRIBUTION from the user's voice — it confidently
        // MISlabels it as cough. So the head may only ADD a rejection, never VETO the multi-class one.
        val letterSaysBackground = d.label in NON_COUGH && d.confidence >= MIN_CONFIDENCE
        val headSaysNotCough = d.emb?.let { CoughVerifier.coughProbability(ctx, it) }?.let { it < COUGH_PROB_FLOOR } ?: false
        return letterSaysBackground || headSaysNotCough
    }

    /** Move [wav] + its sidecars to `<recordingsDir>/rejected/`. Returns true if the clip was rejected. */
    fun reject(ctx: Context, wav: File, decoded: PhonemeDecoder.Decoded): Boolean {
        if (!eligible(ctx, decoded)) return false
        val dir = wav.parentFile ?: return false
        val base = wav.nameWithoutExtension
        if (File(dir, "$base.txt").exists()) return false        // user-labelled → never auto-reject
        val trash = File(dir, SUBDIR).apply { if (!isDirectory) mkdirs() }
        var moved = false
        for (ext in listOf("wav", "png", "txt", "phon", "json")) {
            val f = File(dir, "$base.$ext")
            if (f.isFile) {
                val ok = runCatching { f.renameTo(File(trash, f.name)) }.getOrDefault(false)
                if (ext == "wav") moved = ok
            }
        }
        if (moved) Log.i(TAG, "auto-rejected $base → $SUBDIR/ (label=${decoded.label} conf=${"%.2f".format(decoded.confidence)})")
        return moved
    }

    data class Sweep(val scanned: Int, val rejected: Int)

    /** One-time batch pass over an existing gallery: re-decode each clip with the current codebook and
     *  auto-reject the high-confidence non-coughs. Skips user-commented clips. Cancellable; reports
     *  progress. Runs on a caller-supplied background thread. */
    fun sweep(ctx: Context, dir: File, onProgress: (Int, Int) -> Unit, cancelled: () -> Boolean): Sweep {
        val wavs = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) } ?: return Sweep(0, 0)
        var rejected = 0
        for ((i, wav) in wavs.withIndex()) {
            if (cancelled()) break
            onProgress(i + 1, wavs.size)
            if (File(dir, "${wav.nameWithoutExtension}.txt").isFile) continue   // commented → skip (fast path)
            val data = runCatching { com.example.FFTT04M.WavReader.read(wav) }.getOrNull() ?: continue
            val decoded = PhonemeDecoder.decode(ctx, data.samples, data.sampleRate) ?: continue
            if (reject(ctx, wav, decoded)) rejected++
        }
        Log.i(TAG, "sweep: rejected $rejected of ${wavs.size}")
        return Sweep(wavs.size, rejected)
    }

    // ---- recover/manage the rejected/ folder ------------------------------------------------------
    fun rejectedDir(dir: File) = File(dir, SUBDIR)
    fun rejectedWavs(dir: File): List<File> =
        rejectedDir(dir).listFiles { f -> f.isFile && f.extension.equals("wav", true) }?.toList() ?: emptyList()

    /** Move every rejected file back to the gallery. Returns the number of recordings restored. */
    fun restoreAll(dir: File): Int {
        val trash = rejectedDir(dir); var n = 0
        trash.listFiles()?.forEach { f ->
            if (f.isFile && runCatching { f.renameTo(File(dir, f.name)) }.getOrDefault(false) &&
                f.extension.equals("wav", true)) n++
        }
        return n
    }

    /** Permanently delete everything in rejected/. Returns the number of recordings removed. */
    fun deleteAll(dir: File): Int {
        val trash = rejectedDir(dir); var n = 0
        trash.listFiles()?.forEach { f -> val wav = f.extension.equals("wav", true); if (f.delete() && wav) n++ }
        return n
    }
}
