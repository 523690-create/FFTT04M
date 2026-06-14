package com.example.FFTT04M

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build

/**
 * Chooses the capture [MediaRecorder.AudioSource]. We prefer **UNPROCESSED** — no AGC / noise
 * suppression / echo cancellation, so the captured waveform is the truest acoustic signal (best
 * cough spectral/ridge fidelity, and a match for the desktop analysis). But we fall back to plain
 * **MIC PROACTIVELY** — decided up front from the API level, the device's own support flag, and a
 * known-bad-model denylist — rather than trying UNPROCESSED and waiting for capture to fail.
 *
 * This is INDEPENDENT of microphone *selection*: the MIC spinner still routes to a chosen physical
 * input via `AudioRecord.setPreferredDevice()`, whatever source preset is in effect.
 */
object MicSource {

    /**
     * Models that advertise UNPROCESSED support but mishandle it (route to the wrong mic, return
     * silence, etc.). Matched as a lowercase "manufacturer model" substring. Empty by default —
     * add only CONFIRMED-bad devices as they are identified (the goal is to avoid them up front,
     * not to guess). The API-level + support-flag gates below already exclude most bad cases.
     */
    private val DENYLIST: Set<String> = emptySet()

    /** True when UNPROCESSED can be trusted on this device (proactive, no try-and-fail). */
    fun unprocessedOk(ctx: Context): Boolean {
        // UNPROCESSED (and its support property) exist only on API 24+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val id = "${Build.MANUFACTURER} ${Build.MODEL}".lowercase()
        if (DENYLIST.any { it.isNotEmpty() && id.contains(it) }) return false
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Capture sources to try in order. When UNPROCESSED is trusted it leads, with MIC kept as a
     * final runtime safety net; otherwise the legacy MIC→CAMCORDER list. Plug this into the existing
     * `AudioRecord` init loop in place of the hard-coded source array.
     */
    fun sources(ctx: Context): IntArray =
        if (unprocessedOk(ctx))
            intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        else
            intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER)
}
