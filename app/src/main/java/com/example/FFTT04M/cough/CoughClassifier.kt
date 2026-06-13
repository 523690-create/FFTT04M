package com.example.FFTT04M.cough

/**
 * The deployable cough/not-cough detector: [WholeClipFeatures] → bundled [CoughForest] verdict.
 * Detection-independent (no segmenter), so it works on short/1-sec clips the old engine missed.
 * Held-out on ALLDATA (cough vs speech/crying/non-human; sneezing/breathing/snoring are don't-care):
 * ~91% sensitivity / ~83% specificity at the bundled threshold.
 */
object CoughClassifier {
    private val forest by lazy { CoughForest.loadBundled() }

    fun available(): Boolean = forest != null

    /** Cough probability for a whole clip (or candidate window), or -1 if the model is missing. */
    fun coughProb(pcm: FloatArray, sampleRate: Int): Double =
        forest?.coughProb(WholeClipFeatures.extract(pcm, sampleRate)) ?: -1.0

    fun isCough(pcm: FloatArray, sampleRate: Int): Boolean =
        forest?.let { it.isCough(WholeClipFeatures.extract(pcm, sampleRate)) } ?: false
}
