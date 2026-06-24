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

    fun eligible(ctx: Context, d: PhonemeDecoder.Decoded): Boolean =
        DeviceCaps.tier(ctx) >= 1 && d.label in NON_COUGH && d.confidence >= MIN_CONFIDENCE

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
}
