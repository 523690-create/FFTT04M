package com.example.FFTT04M.cough

/**
 * The deployable cough/not-cough detector: [WholeClipFeatures] → bundled [CoughForest] verdict.
 * Detection-independent (no segmenter), so it works on short/1-sec clips the old engine missed.
 * Held-out on ALLDATA (cough vs speech/crying/non-human; sneezing/breathing/snoring are don't-care):
 * ~91% sensitivity / ~83% specificity at the bundled threshold.
 */
object CoughClassifier {
    private val forest by lazy { CoughForest.loadBundled() }

    /** Override the model's built-in decision threshold to move along the ROC (null = model default).
     *  Lower → more sensitive, higher → more specific. */
    @Volatile var thresholdOverride: Double? = null

    fun available(): Boolean = forest != null

    /** Effective decision threshold (override if set, else the model's bundled value). */
    fun threshold(): Double = thresholdOverride ?: (forest?.threshold ?: 0.5)

    /** Cough probability for a whole clip (or candidate window), or -1 if the model is missing. */
    fun coughProb(pcm: FloatArray, sampleRate: Int): Double =
        forest?.coughProb(WholeClipFeatures.extract(pcm, sampleRate)) ?: -1.0

    fun isCough(pcm: FloatArray, sampleRate: Int): Boolean {
        val p = coughProb(pcm, sampleRate)
        return p >= 0.0 && p >= threshold()
    }
}
