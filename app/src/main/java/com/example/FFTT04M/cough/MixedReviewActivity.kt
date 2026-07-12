package com.example.FFTT04M.cough

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.FFTT04M.GalleryTransfer
import com.example.FFTT04M.WavReader
import java.io.File
import kotlin.concurrent.thread

/**
 * Manual verification for MIXED (cough + voice) clips only. Scans the gallery for clips whose cached decode
 * says they contain both a cough and a voice span ([MixedClipBreaker]), then walks them one at a time:
 * shows the proposed split, plays the whole clip and each component, and on Confirm writes the components
 * as new labelled clips (clean cough/voice ground truth) and archives the original blob to rejected/.
 *
 * Entry: Gallery → Tools → "Break up mixed clips (N)".
 */
class MixedReviewActivity : AppCompatActivity() {

    private data class Item(val wav: File, val pcm: FloatArray, val sr: Int, val comps: List<MixedClipBreaker.Component>)

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var componentBar: LinearLayout
    private lateinit var confirmBtn: Button
    private var player: MediaPlayer? = null

    private var queue: List<File> = emptyList()
    private var pos = 0
    private var current: Item? = null
    private var brokenThisSession = 0

    private fun mainDir(): File = GalleryTransfer.recordingsDir(this) ?: filesDir

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Break up mixed clips"
        buildLayout(); setContentView(root)
        status.text = "Scanning for mixed clips…"
        thread {
            val dir = mainDir()
            val wavs = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }?.toList() ?: emptyList()
            // Fast pass: trust the cached "mixed" flag in the .phon (no audio read).
            val mixed = wavs.filter { PhonemeDecoder.read(dir, it.nameWithoutExtension)?.mixed == true }
                .sortedByDescending { it.lastModified() }
            runOnUiThread {
                queue = mixed; pos = 0
                if (mixed.isEmpty()) { status.text = "No mixed clips found."; detail.text = ""; confirmBtn.isEnabled = false }
                else loadCurrent()
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }

    private fun buildLayout() {
        val d = resources.displayMetrics.density; val pad = (16 * d).toInt()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF111111.toInt()); setPadding(pad, pad, pad, pad) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        status = TextView(this).apply { setTextColor(0xFFEEEEEE.toInt()); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        top.addView(status)
        top.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        root.addView(top)

        detail = TextView(this).apply { setTextColor(0xFFCCCCCC.toInt()); textSize = 13f; setPadding(0, (12 * d).toInt(), 0, (8 * d).toInt()) }
        root.addView(detail)

        root.addView(Button(this).apply { text = "▶ Play whole clip"; setOnClickListener { current?.let { playPcm(it.pcm, it.sr) } } })

        componentBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * d).toInt(), 0, 0) }
        root.addView(componentBar)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (16 * d).toInt(), 0, 0) }
        confirmBtn = Button(this).apply {
            text = "✓ Break & save components"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { confirmBreak() }
        }
        row.addView(confirmBtn)
        row.addView(Button(this).apply {
            text = "Skip"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { advance() }
        })
        root.addView(row)
    }

    private fun loadCurrent() {
        val f = queue.getOrNull(pos)
        if (f == null) {
            status.text = "All done — broke $brokenThisSession clip(s) this session."
            detail.text = ""; componentBar.removeAllViews(); confirmBtn.isEnabled = false; current = null
            return
        }
        status.text = "${pos + 1} of ${queue.size} (broke $brokenThisSession)"
        detail.text = "Loading ${f.name}…"; componentBar.removeAllViews(); confirmBtn.isEnabled = false
        thread {
            val data = runCatching { WavReader.read(f) }.getOrNull()
            val decoded = PhonemeDecoder.read(f.parentFile ?: mainDir(), f.nameWithoutExtension)
            val comps = if (data != null && decoded != null)
                MixedClipBreaker.components(decoded.word, PhonemeDecoder.windowSpans(data.samples, data.sampleRate)) else null
            runOnUiThread {
                if (data == null || comps == null || !MixedClipBreaker.isMixed(comps)) {
                    // stale flag (e.g. codebook changed) — skip transparently
                    advance(); return@runOnUiThread
                }
                current = Item(f, data.samples, data.sampleRate, comps)
                showComponents(f, comps, data.samples, data.sampleRate)
            }
        }
    }

    private fun showComponents(f: File, comps: List<MixedClipBreaker.Component>, pcm: FloatArray, sr: Int) {
        val cough = comps.count { it.kind == MixedClipBreaker.Kind.COUGH }
        val voice = comps.count { it.kind == MixedClipBreaker.Kind.VOICE }
        detail.text = "${f.name}\nProposed split: $cough cough + $voice voice component(s). " +
            "Confirm to save each as its own labelled clip and archive the original."
        componentBar.removeAllViews()
        val d = resources.displayMetrics.density
        for (c in comps) {
            if (c.kind == MixedClipBreaker.Kind.OTHER) continue
            val label = if (c.kind == MixedClipBreaker.Kind.COUGH) "cough" else "voice"
            componentBar.addView(Button(this).apply {
                text = "▶ $label  ${c.startMs}–${c.endMs} ms"
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                setOnClickListener { playPcm(MixedClipBreaker.extract(pcm, sr, c), sr) }
            })
        }
        confirmBtn.isEnabled = true
    }

    private fun confirmBreak() {
        val it = current ?: return
        confirmBtn.isEnabled = false
        thread {
            val dir = it.wav.parentFile ?: mainDir()
            val base = it.wav.nameWithoutExtension
            val written = MixedClipBreaker.writeComponents(dir, base, it.pcm, it.sr, it.comps)
            val archived = if (written.isNotEmpty()) AutoReject.archiveToRejected(dir, it.wav) else false
            runOnUiThread {
                if (written.isNotEmpty()) {
                    brokenThisSession++
                    Toast.makeText(this, "Saved ${written.size} component(s)" + if (archived) "; archived original" else "", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Nothing to write", Toast.LENGTH_SHORT).show()
                advance()
            }
        }
    }

    private fun advance() { player?.release(); player = null; pos++; loadCurrent() }

    private fun playPcm(pcm: FloatArray, sr: Int) {
        player?.release(); player = null
        // MediaPlayer needs a file/URI — write a tiny temp WAV in cache and play it.
        val tmp = File(cacheDir, "mix_play.wav")
        runCatching {
            CoughWav.write(tmp, pcm, sr)
            player = MediaPlayer.create(this, Uri.fromFile(tmp))?.apply {
                setOnCompletionListener { it.release(); if (player === it) player = null }
                start()
            }
            if (player == null) Toast.makeText(this, "Couldn't play", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, "Playback failed: ${it.message}", Toast.LENGTH_SHORT).show() }
    }
}
