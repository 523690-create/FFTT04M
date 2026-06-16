package com.example.FFTT04M

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Tracks whether the device is currently producing audio output — media, **notification chimes**,
 * Bluetooth-transfer sounds, our own PLAY, etc. — so capture loops can suspend input and avoid
 * recording the device's own output (the user heard auto-capture pick up notification chimes during
 * Bluetooth transfers).
 *
 * API 26+ uses an event-driven [AudioManager.AudioPlaybackCallback] (no per-loop cost). A short tail
 * keeps [active] true briefly after playback stops so a chime's decay isn't captured. On API < 28 a
 * caller may additionally OR `AudioManager.isMusicActive()` for the media stream (the callback already
 * covers notifications on 26+). Registered once per process; lightweight, so never unregistered.
 */
object AudioOutputMonitor {

    @Volatile var active = false
        private set

    private const val TAIL_MS = 700L
    private val main = Handler(Looper.getMainLooper())
    private val clear = Runnable { active = false }
    private var registered = false

    @Synchronized
    fun start(ctx: Context) {
        if (registered || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                update(configs.isNotEmpty())
            }
        }, main)
        registered = true
        update(am.activePlaybackConfigurations.isNotEmpty())   // seed with the current state
    }

    private fun update(playing: Boolean) {
        main.removeCallbacks(clear)
        if (playing) active = true
        else main.postDelayed(clear, TAIL_MS)   // hold 'active' through the output's tail
    }
}
