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
    private const val PREFS = "app_settings"
    private const val KEY_THRESHOLD = "reject_sensitivity_threshold"
    // Reject when the in-domain VOTE is this confident the clip is NOT a cough. Default is deliberately
    // conservative (well below the vote's 90%-recall operating point ≈0.26) so a real cough is very
    // unlikely to be rejected — AutoReject must never lose data. User-tunable "specificity" knob, exposed
    // in the gallery Tools spinner ("Reject sensitivity"), per the desktop roadmap.
    const val DEFAULT_VOTE_REJECT_THRESHOLD = 0.20f

    /** Current reject threshold — user preference if set, else [DEFAULT_VOTE_REJECT_THRESHOLD]. */
    fun voteRejectThreshold(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_THRESHOLD, DEFAULT_VOTE_REJECT_THRESHOLD)

    fun setVoteRejectThreshold(ctx: Context, value: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_THRESHOLD, value).apply()
    }

    /** [pcm]/[sr] (when available) enable the in-domain [CoughVote]; without them only the codebook
     *  letter rule applies. */
    fun eligible(ctx: Context, d: PhonemeDecoder.Decoded, pcm: FloatArray? = null, sr: Int = 44100): Boolean {
        if (DeviceCaps.tier(ctx) < 1) return false
        // PRIMARY: the in-domain VOTE (forest + in-domain HuBERT head + DSP cues), trained on the user's
        // own clips. This supersedes the old ALLDATA cough-head check, which was OUT-OF-DISTRIBUTION for
        // the user's voice (it MISlabelled their voice as cough); the vote's head is trained in-domain.
        val threshold = voteRejectThreshold(ctx)
        val voteSaysNotCough = if (pcm != null)
            CoughVote.probability(ctx, pcm, sr, d.emb)?.let { it < threshold } ?: false
        else false
        // ORTHOGONAL in-domain signal: the multi-class decoder confidently calling it noise/voice. Also
        // trained on the user's own labelled clips, so it correctly IDs their voice/noise.
        val letterSaysBackground = d.label in NON_COUGH && d.confidence >= MIN_CONFIDENCE
        return voteSaysNotCough || letterSaysBackground
    }

    /** Move [wav] + its sidecars to `<recordingsDir>/rejected/`. Returns true if the clip was rejected.
     *  Pass [pcm]/[sr] so the in-domain [CoughVote] can run (the caller already has the samples). */
    fun reject(ctx: Context, wav: File, decoded: PhonemeDecoder.Decoded, pcm: FloatArray? = null, sr: Int = 44100): Boolean {
        if (!eligible(ctx, decoded, pcm, sr)) return false
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
     *  progress. Runs on a caller-supplied background thread.
     *
     *  Uses [PhonemeDecoder.annotate] (not the bare `decode()`) so the decision is PERSISTED to the
     *  clip's `.phon` sidecar — it moves into `rejected/` together with the wav, so
     *  [diagnose] can explain "why" without having to re-run the model later. (Real-time capture via
     *  `CoughCaptureService` already annotates before checking reject; the batch sweep previously did
     *  not, leaving swept clips with no cached auto-interpretation to inspect afterward.) */
    fun sweep(ctx: Context, dir: File, onProgress: (Int, Int) -> Unit, cancelled: () -> Boolean): Sweep {
        val wavs = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) } ?: return Sweep(0, 0)
        var rejected = 0
        for ((i, wav) in wavs.withIndex()) {
            if (cancelled()) break
            onProgress(i + 1, wavs.size)
            if (File(dir, "${wav.nameWithoutExtension}.txt").isFile) continue   // commented → skip (fast path)
            val data = runCatching { com.example.FFTT04M.WavReader.read(wav) }.getOrNull() ?: continue
            val decoded = runCatching { PhonemeDecoder.annotate(ctx, wav, data.samples, data.sampleRate) }.getOrNull() ?: continue
            if (reject(ctx, wav, decoded, data.samples, data.sampleRate)) rejected++
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

    /** Move a clip (+ sidecars) into rejected/ unconditionally — e.g. after a mixed clip has been broken
     *  into components, the original blob is archived (recoverable) instead of muddying training. */
    fun archiveToRejected(dir: File, wav: File): Boolean {
        val base = wav.nameWithoutExtension
        val trash = File(dir, SUBDIR).apply { if (!isDirectory) mkdirs() }
        var moved = false
        for (ext in listOf("wav", "png", "txt", "phon", "json")) {
            val f = File(dir, "$base.$ext")
            if (f.isFile) { val ok = runCatching { f.renameTo(File(trash, f.name)) }.getOrDefault(false); if (ext == "wav") moved = ok }
        }
        return moved
    }

    /** Restore ONE rejected clip (+ sidecars) back to [dir] (the recordings dir, NOT rejected/ itself). */
    fun restoreOne(dir: File, rejectedWav: File): Boolean {
        val trash = rejectedWav.parentFile ?: return false
        val base = rejectedWav.nameWithoutExtension
        var moved = false
        for (ext in listOf("wav", "png", "txt", "phon", "json")) {
            val f = File(trash, "$base.$ext")
            if (f.isFile) {
                val ok = runCatching { f.renameTo(File(dir, f.name)) }.getOrDefault(false)
                if (ext == "wav") moved = ok
            }
        }
        return moved
    }

    /** Human-readable "why was this flagged" diagnostic for a clip sitting in rejected/ (or anywhere):
     *  the decoder's predicted label/confidence and, when a HuBERT embedding is available, the trained
     *  head's P(cough) — the two signals [eligible] actually checks. Decodes fresh (and persists via
     *  `annotate`, fixing any clip that was rejected before the sweep-caching fix above) when no cached
     *  `.phon` exists yet. Returns "no decode available" if the clip can't be read/decoded at all. */
    fun diagnose(ctx: Context, wav: File): String {
        val dir = wav.parentFile
        val base = wav.nameWithoutExtension
        val cached = dir?.let { PhonemeDecoder.read(it, base) }
        val decoded = cached ?: run {
            val data = runCatching { com.example.FFTT04M.WavReader.read(wav) }.getOrNull() ?: return "no decode available"
            runCatching { PhonemeDecoder.annotate(ctx, wav, data.samples, data.sampleRate) }.getOrNull()
        } ?: return "no decode available"
        val decoderPart = "decoder: ${decoded.label} (${(decoded.confidence * 100).let { "%.0f".format(it) }}%)"
        // Show the in-domain vote's P(cough) when we can read the samples (the reliable "why").
        val votePart = runCatching { com.example.FFTT04M.WavReader.read(wav) }.getOrNull()?.let { d ->
            CoughVote.probability(ctx, d.samples, d.sampleRate, decoded.emb)
        }?.let { "vote P(cough)=${"%.2f".format(it)}" }
        return if (votePart != null) "$decoderPart · $votePart" else decoderPart
    }
}
