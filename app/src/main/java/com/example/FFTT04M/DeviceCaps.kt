package com.example.FFTT04M

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Coarse device-capability tiers, so heavy analysis/enhancement features can be hidden or eased
 * on old / low-RAM hardware (e.g. Nexus 7, Galaxy J7) instead of running unusably slowly.
 *
 *   tier 0 = legacy  (pre-Oreo OR < ~2 GB RAM)
 *   tier 1 = mid     (Oreo .. Android 11)
 *   tier 2 = modern  (Android 12 / API 31+)
 *
 * Gate at three levels (cheapest → most graceful):
 *   - hide the control          (e.g. heavy enhancement checkboxes on tier 0)
 *   - disable + annotate        (visible but "(needs newer device)")
 *   - auto-degrade parameters   (fewer CWT scales / iterations on tier 0)
 * Whenever a feature silently degrades, surface it (Toast / suffix) so cross-device behaviour
 * differences aren't mistaken for bugs.
 */
object DeviceCaps {

    fun tier(ctx: Context): Int {
        val lowRam = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            mi.totalMem in 1..(2L * 1024 * 1024 * 1024)   // < ~2 GB
        } catch (_: Exception) {
            false
        }
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || lowRam -> 0
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> 1
            else -> 2
        }
    }

    /** Heavy per-pixel enhancement filters (Gabor bank, Frangi, NLM) need at least mid tier. */
    fun heavyEnhancementsAllowed(ctx: Context): Boolean = tier(ctx) >= 1

    /** Total physical RAM in bytes (0 if unknown). */
    fun totalRamBytes(ctx: Context): Long = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    } catch (_: Exception) { 0L }

    /**
     * On-device HuBERT decode (the 89%-CV gold-standard path) — runs the 377 MB fp32 model, so it
     * needs headroom. Gated on RAM rather than tier because minSdk already forces every install to
     * tier 2; the real risk is OOM on small-RAM phones. Floor ≈ 3 GB (a nominal-4 GB device such as
     * the Pixel 3a reports ~3.6 GB, which the probe ran without OOM; a true 3 GB device clears it).
     * Below the floor, PhonemeDecoder falls back to the DSP codebook.
     */
    fun hubertAllowed(ctx: Context): Boolean = totalRamBytes(ctx) >= 3L * 1000 * 1000 * 1000
}
