package com.example.FFTT04M.cough

import java.io.File
import kotlin.math.sqrt

/**
 * Splits a MIXED capture — one that contains BOTH cough and voice (the detector sometimes fires on a
 * cough that lands in the middle of speech) — into its component spans, so the clean cough can be kept
 * as training data instead of a cough+voice blob that muddies the codebook.
 *
 * Candidate spans come from the per-window phoneme decode (each 180/90 ms window's letter → coarse class),
 * but the codebook LETTERS are unreliable on out-of-distribution sounds — printer/mechanical noise and
 * silence scatter into stray cough+voice letters, and a pure cough/voice clip picks up the odd wrong
 * window. So [analyze] does NOT trust the letters alone: it ACOUSTICALLY VALIDATES each candidate (a real
 * cough span must be an impulsive burst; a real voice span must be voiced+tonal) and REFINES the cough
 * boundary to the actual burst via the energy envelope. Only clips with a validated cough AND a validated
 * voice span survive as "mixed" — this is what cuts the false positives (see the 2026-07-12 review: 13/33
 * good under the letter-only version).
 *
 * Extraction/splitting only happens for clips the user confirms in [MixedReviewActivity] — the "manual
 * verification for only those clips" requirement.
 */
object MixedClipBreaker {

    // Phoneme letters by coarse class (match CoughVote / the codebook letters). Cough family = the cough
    // subtypes; voice = spoken; everything else (noise/snore/sneeze/music/?) is neither.
    private val COUGH_LETTERS = setOf("D", "DH", "B", "BT", "C", "CX", "CR", "W", "DC")
    private val VOICE_LETTERS = setOf("V", "SP")
    private const val MIN_SPAN_MS = 220            // ignore spans shorter than this
    private const val MIN_WINDOWS = 3              // …and require at least this many windows (not a stray)

    // Acoustic validation thresholds (WholeClipFeatures on the span; tunable). A cough is an IMPULSIVE
    // burst → high crest; sustained voice and steady printer/fan noise are much less peaky. Voice is
    // VOICED + TONAL → strong pitch periodicity and a non-flat (harmonic) spectrum; cough and noise are
    // aperiodic and spectrally flat.
    private const val COUGH_MIN_CREST = 4.5
    private const val VOICE_MIN_PITCH = 0.30
    private const val VOICE_MAX_FLATNESS = 0.55
    // WholeClipFeatures indices used here: 0 crest, 6 flatness, 11 pitch_strength.

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
            val startIdx = i; val endIdx = j
            if (endIdx - startIdx + 1 >= MIN_WINDOWS) comps.add(Component(k, spans[startIdx].first, spans[endIdx].second))
            i = j + 1
        }
        return comps.filter { it.durMs >= MIN_SPAN_MS }
    }

    /**
     * The VALIDATED, boundary-refined components — the letters propose candidates, the AUDIO confirms them.
     * A cough candidate is kept only if, after trimming to its energy burst, it's impulsive enough
     * (crest); a voice candidate only if it's voiced+tonal (pitch high, flatness low). This is what
     * rejects the "neither cough nor voice" false positives (printer noise, silence) and the pure-cough/
     * pure-voice clips that picked up a stray wrong-class window. Needs the audio; returns null if the
     * decode/grid don't line up.
     */
    fun analyze(word: List<String>, spans: List<Pair<Int, Int>>, pcm: FloatArray, sr: Int): List<Component>? {
        val raw = components(word, spans) ?: return null
        val out = ArrayList<Component>()
        for (c in raw) when (c.kind) {
            Kind.COUGH -> {
                val refined = refineBurst(pcm, sr, c)
                val f = WholeClipFeatures.extract(extract(pcm, sr, refined), sr)
                if (f[0] >= COUGH_MIN_CREST) out.add(refined)          // crest → impulsive → real cough burst
            }
            Kind.VOICE -> {
                val f = WholeClipFeatures.extract(extract(pcm, sr, c), sr)
                if (f[11] >= VOICE_MIN_PITCH && f[6] <= VOICE_MAX_FLATNESS) out.add(c)   // voiced + tonal
            }
            else -> {}
        }
        return out
    }

    /** A clip is mixed when it has at least one (validated) cough span AND one (validated) voice span. */
    fun isMixed(comps: List<Component>?): Boolean {
        val c = comps ?: return false
        return c.any { it.kind == Kind.COUGH } && c.any { it.kind == Kind.VOICE }
    }

    /** Trim a cough candidate to its actual burst: drop leading/trailing frames below 18% of the span's
     *  peak RMS envelope (10 ms frames), so voice/silence bleed at the edges doesn't widen the cough clip
     *  and the split boundary lands on the real onset/offset. Falls back to the input if too short. */
    private fun refineBurst(pcm: FloatArray, sr: Int, c: Component): Component {
        val s0 = (c.startMs / 1000.0 * sr).toInt().coerceIn(0, pcm.size)
        val e0 = (c.endMs / 1000.0 * sr).toInt().coerceIn(s0, pcm.size)
        val frame = (sr / 100).coerceAtLeast(1)                 // 10 ms
        val nF = (e0 - s0) / frame
        if (nF < 4) return c
        val env = DoubleArray(nF) { i -> var s = 0.0; val base = s0 + i * frame; for (j in 0 until frame) { val v = pcm[base + j].toDouble(); s += v * v }; sqrt(s / frame) }
        val peak = env.max()
        if (peak <= 0) return c
        val thr = peak * 0.18
        var a = 0; while (a < nF && env[a] < thr) a++
        var b = nF - 1; while (b > a && env[b] < thr) b--
        if (b <= a) return c
        val ns = s0 + a * frame; val ne = s0 + (b + 1) * frame
        return Component(c.kind, (ns * 1000L / sr).toInt(), (ne * 1000L / sr).toInt())
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
