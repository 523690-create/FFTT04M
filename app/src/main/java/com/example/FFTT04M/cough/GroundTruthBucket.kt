package com.example.FFTT04M.cough

import java.io.File

/**
 * Coarse ground-truth bucket for on-device data-collection (desktop finding, 2026-07-10: the cough-vs-
 * breath false-positive rate is far worse in-domain than on public benchmarks, and the on-device codebook
 * has NO dedicated "breathing" class yet — real breath clips currently fall into NOISE/UNKNOWN and need
 * manual correction to become usable training data). Buckets every captured clip so the RARE cough clips
 * can be reviewed individually and the ABUNDANT breath/voice/noise clips can be reviewed efficiently.
 *
 * A clip's bucket is CONFIRMED when the user has set a manual `<base>.txt` comment (real ground truth);
 * otherwise it's the PREDICTED bucket from [PhonemeDecoder]'s cached `.phon` decode (unconfirmed); else
 * UNKNOWN (not yet decoded). Mirrors the same "manual wins over decoded label" rule already used by
 * `ClipCloudActivity.labelFor` and the desktop's `CoughTruth`, kept independent here (new code, not a
 * refactor of already-working call sites) so this can be reasoned about in isolation.
 */
object GroundTruthBucket {

    enum class Category { COUGH, BREATH, VOICE, NOISE, SNORE, SNEEZE, OTHER, UNKNOWN }

    /** Quick-label vocabulary offered by the review UI — one tap sets ground truth. "breathing" is the
     *  category the desktop investigation flagged as most in need of on-device ground truth. */
    val QUICK_LABELS: List<Pair<Category, String>> = listOf(
        Category.COUGH to "cough", Category.BREATH to "breathing",
        Category.VOICE to "voice", Category.NOISE to "noise",
        Category.SNORE to "snoring", Category.SNEEZE to "sneeze",
    )

    private val COUGH_WORDS = listOf("cough", "bronchit", "hacking", "wheez", "expector", "productive", "phlegm", "croup", "dry")
    private val BREATH_WORDS = listOf("breath", "sigh")
    private val VOICE_WORDS = listOf("voice", "speech", "talk", "sing", "vowel", "counting")
    private val NOISE_WORDS = listOf("noise", "silence", "music")
    private val SNORE_WORDS = listOf("snor")
    private val SNEEZE_WORDS = listOf("sneeze")

    private fun categorize(text: String): Category {
        val t = text.lowercase()
        return when {
            t.isBlank() -> Category.UNKNOWN
            SNORE_WORDS.any { it in t } -> Category.SNORE
            SNEEZE_WORDS.any { it in t } -> Category.SNEEZE
            BREATH_WORDS.any { it in t } -> Category.BREATH
            COUGH_WORDS.any { it in t } -> Category.COUGH
            VOICE_WORDS.any { it in t } -> Category.VOICE
            NOISE_WORDS.any { it in t } -> Category.NOISE
            t == "?" -> Category.UNKNOWN
            else -> Category.OTHER
        }
    }

    data class Bucketed(val category: Category, val confirmed: Boolean, val sourceText: String)

    /** Bucket one clip: an existing manual comment (confirmed ground truth) wins over the on-device
     *  phoneme decode (predicted, unconfirmed); a clip with neither is UNKNOWN. */
    fun bucketOf(wav: File): Bucketed {
        val dir = wav.parentFile ?: return Bucketed(Category.UNKNOWN, false, "")
        val base = wav.nameWithoutExtension
        val txt = File(dir, "$base.txt")
        if (txt.isFile) {
            val comment = runCatching { txt.readText() }.getOrDefault("").trim()
            if (comment.isNotEmpty() && !comment.startsWith("auto-match", true))
                return Bucketed(categorize(comment), true, comment)
        }
        val decoded = PhonemeDecoder.read(dir, base)
        if (decoded != null && decoded.label != "?")
            return Bucketed(categorize(decoded.label), false, decoded.label)
        return Bucketed(Category.UNKNOWN, false, "")
    }

    /** Write [label] as the clip's manual ground-truth comment (same plain-text sidecar format the
     *  existing ViewerActivity comment dialog already writes — no prefix, matched by the desktop's
     *  word-list `CoughTruth` parser regardless). */
    fun setGroundTruth(wav: File, label: String) {
        val dir = wav.parentFile ?: return
        File(dir, "${wav.nameWithoutExtension}.txt").writeText(label)
    }

    fun displayName(cat: Category): String = when (cat) {
        Category.COUGH -> "Cough"; Category.BREATH -> "Breath"; Category.VOICE -> "Voice"
        Category.NOISE -> "Noise"; Category.SNORE -> "Snore"; Category.SNEEZE -> "Sneeze"
        Category.OTHER -> "Other"; Category.UNKNOWN -> "Unlabeled"
    }

    /** One-line "Category: X (confirmed|predicted)" tag shared by every clip listing (regular Gallery
     *  and the bucket list), so ground truth status is visible everywhere, not just inside Review. */
    fun statusLine(b: Bucketed): String =
        "Category: ${displayName(b.category)} (${if (b.confirmed) "confirmed" else "predicted"})"

    fun statusLine(wav: File): String = statusLine(bucketOf(wav))
}
