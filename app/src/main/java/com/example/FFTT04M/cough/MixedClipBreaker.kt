package com.example.FFTT04M.cough

import java.io.File

/**
 * Splits a MIXED capture — one that contains BOTH cough and voice (the detector sometimes fires on a
 * cough that lands in the middle of speech) — into its component spans, so the clean cough can be kept
 * as training data instead of a cough+voice blob that muddies the codebook.
 *
 * It reuses the per-window phoneme decode already cached in the `.phon`: each 180/90 ms window carries a
 * phoneme code whose LETTER maps to a coarse class (cough family vs voice vs other). Contiguous same-class
 * windows are merged into spans; a clip with a cough span AND a voice span (each long enough) is "mixed".
 *
 * Detection is cheap (reads the cached decode). Extraction/splitting only happens for clips the user
 * confirms in [MixedReviewActivity] — the "manual verification for only those clips" requirement.
 */
object MixedClipBreaker {

    // Phoneme letters by coarse class (match CoughVote / the codebook letters). Cough family = the cough
    // subtypes; voice = spoken; everything else (noise/snore/sneeze/music/?) is neither.
    private val COUGH_LETTERS = setOf("D", "DH", "B", "BT", "C", "CX", "CR", "W", "DC")
    private val VOICE_LETTERS = setOf("V", "SP")
    private const val MIN_SPAN_MS = 200            // ignore spans shorter than this (stray single window)

    enum class Kind { COUGH, VOICE, OTHER }
    data class Component(val kind: Kind, val startMs: Int, val endMs: Int) {
        val durMs get() = endMs - startMs
    }

    private fun letterOf(code: String) = code.takeWhile { it.isLetter() }
    private fun kindOf(code: String): Kind = when (letterOf(code)) {
        in COUGH_LETTERS -> Kind.COUGH
        in VOICE_LETTERS -> Kind.VOICE
        else -> Kind.OTHER
    }

    /**
     * Merge the per-window decode into contiguous cough / voice spans (a lone OTHER/`?` window between two
     * same-kind windows is bridged, so a single mis-decoded window doesn't fragment a span). Returns null
     * when the word and the window grid don't line up (can't trust the split).
     */
    fun components(word: List<String>, spans: List<Pair<Int, Int>>): List<Component>? {
        if (word.isEmpty() || word.size != spans.size) return null
        val kinds = word.map { kindOf(it) }
        val comps = ArrayList<Component>()
        var i = 0
        while (i < kinds.size) {
            val k = kinds[i]
            if (k == Kind.OTHER) { i++; continue }
            var j = i
            while (j + 1 < kinds.size &&
                (kinds[j + 1] == k || (kinds[j + 1] == Kind.OTHER && j + 2 < kinds.size && kinds[j + 2] == k))) j++
            comps.add(Component(k, spans[i].first, spans[j].second))
            i = j + 1
        }
        return comps.filter { it.durMs >= MIN_SPAN_MS }
    }

    /** A clip is mixed when it has at least one qualifying cough span AND one qualifying voice span. */
    fun isMixed(comps: List<Component>?): Boolean {
        val c = comps ?: return false
        return c.any { it.kind == Kind.COUGH } && c.any { it.kind == Kind.VOICE }
    }

    /** Detect mixed purely from the cached decode (no audio read) — for the fast gallery scan. Needs the
     *  clip's duration to rebuild the window grid; pass it from the WAV header. */
    fun isMixedCached(decoded: PhonemeDecoder.Decoded?, durationMs: Int): Boolean {
        val word = decoded?.word ?: return false
        if (word.isEmpty()) return false
        val spans = gridFor(durationMs, word.size) ?: return false
        return isMixed(components(word, spans))
    }

    /** Rebuild the fixed 180/90 ms window grid for a duration, trusting [nWindows] from the cached word
     *  (so we don't need to re-read the audio just to count windows). Null if it can't be reconciled. */
    private fun gridFor(durationMs: Int, nWindows: Int): List<Pair<Int, Int>>? {
        if (durationMs <= 0 || nWindows <= 0) return null
        val win = 180; val hop = 90
        val out = ArrayList<Pair<Int, Int>>()
        if (durationMs <= win) { out.add(0 to durationMs) }
        else {
            var s = 0
            while (s < durationMs) {
                val e = (s + win).coerceAtMost(durationMs)
                if (e - s >= win / 2) out.add(s to e)
                if (e >= durationMs) break
                s += hop
            }
        }
        return if (out.size == nWindows) out else null
    }

    /** Extract [c]'s PCM sub-range from the whole-clip [pcm]. */
    fun extract(pcm: FloatArray, sr: Int, c: Component): FloatArray {
        val s = (c.startMs / 1000.0 * sr).toInt().coerceIn(0, pcm.size)
        val e = (c.endMs / 1000.0 * sr).toInt().coerceIn(s, pcm.size)
        return pcm.copyOfRange(s, e)
    }

    /**
     * Write each component as a new clip in [dir], labelled by its kind (cough/voice) so it becomes clean
     * ground truth. Names `<base>_c1.wav`, `<base>_v1.wav`, … Returns the files written. Non-destructive:
     * the caller decides what to do with the original (see [MixedReviewActivity], which archives it).
     */
    fun writeComponents(dir: File, base: String, pcm: FloatArray, sr: Int, comps: List<Component>): List<File> {
        val written = ArrayList<File>()
        var ci = 0; var vi = 0
        for (c in comps) {
            if (c.kind == Kind.OTHER) continue
            val tag = if (c.kind == Kind.COUGH) "c${++ci}" else "v${++vi}"
            val label = if (c.kind == Kind.COUGH) "cough" else "voice"
            val wav = File(dir, "${base}_$tag.wav")
            runCatching {
                CoughWav.write(wav, extract(pcm, sr, c), sr)
                File(dir, "${base}_$tag.txt").writeText(label)   // ground-truth label for the component
                written.add(wav)
            }
        }
        return written
    }
}
