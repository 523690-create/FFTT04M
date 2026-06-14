package com.example.FFTT04M.cough

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.FFTT04M.GalleryTransfer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Hands-free cough auto-capture (FUTURE VISION). Listens on the mic, runs [CoughDetector] on the
 * live stream, and auto-saves each detected, speech-rejected cough as a Gallery-compatible WAV.
 * No manual freeze/crop — the app catches coughs on its own and ignores speech.
 */
class CoughCaptureActivity : AppCompatActivity() {

    private val sampleRate = 44100
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var listContainer: LinearLayout
    private var count = 0

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCapture() else toast("Microphone permission is needed to capture coughs")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Cough Auto-Capture"
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        status = TextView(this).apply {
            textSize = 15f; setPadding(dp(16), dp(16), dp(16), dp(8))
            text = "Tap Start, then cough. Speech is ignored automatically."
        }
        toggle = Button(this).apply {
            text = "Start listening"
            setOnClickListener { if (capturing) stopCapture() else ensurePermissionAndStart() }
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listContainer)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), 0, dp(16), dp(8))
            })
            addView(scroll)
        })
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startCapture()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startCapture() {
        if (capturing) return
        capturing = true
        toggle.text = "Stop listening"
        status.text = "Listening… cough away (speech ignored)."
        captureThread = thread(name = "cough-capture") { captureLoop() }
    }

    private fun stopCapture() {
        capturing = false
        toggle.text = "Start listening"
        status.text = "Stopped. Captured $count cough(s) this session."
    }

    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 4)
        val recorder = try {
            AudioRecord(com.example.FFTT04M.MicSource.sources(this)[0], sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        } catch (e: Throwable) {
            runOnUiThread { toast("Cannot open microphone: ${e.message}"); stopCapture() }; return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runOnUiThread { toast("Microphone unavailable"); stopCapture() }; recorder.release(); return
        }

        val detector = CoughDetector(sampleRate) { onCough(it) }
        val shortBuf = ShortArray(minBuf)
        val floatBuf = FloatArray(minBuf)
        try {
            recorder.startRecording()
            while (capturing) {
                val n = recorder.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
                detector.process(if (n == floatBuf.size) floatBuf else floatBuf.copyOf(n))
            }
            detector.finish()
        } catch (e: Throwable) {
            runOnUiThread { toast("Capture error: ${e.message}") }
        } finally {
            try { recorder.stop() } catch (_: Throwable) {}
            recorder.release()
        }
    }

    private fun onCough(c: CoughDetector.CapturedCough) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val dir = GalleryTransfer.recordingsDir(this) ?: filesDir
        val file = File(dir, "cough_$ts.wav")
        runCatching { CoughWav.write(file, c.pcm, sampleRate) }
        count++
        runOnUiThread {
            status.text = "Listening… captured $count cough(s)."
            addRow(file.nameWithoutExtension, c)
        }
    }

    private fun addRow(name: String, c: CoughDetector.CapturedCough) {
        val d = resources.displayMetrics.density
        val r = c.ridge
        val ridgeStr = if (r.valid)
            "  ridge a=%.0f cf=%.0fHz R²=%.2f".format(Locale.US, r.curvature, r.centerFreqHz, r.rSquared)
        else "  ridge: none"
        listContainer.addView(TextView(this).apply {
            textSize = 13f
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            text = "$name  —  %.0fms  Q=%.2f Fmax=%.0fHz%s".format(
                Locale.US, c.pcm.size * 1000.0 / sampleRate, c.fft.qRatio, c.fft.fmaxHz, ridgeStr)
        }, 0)
        listContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt())
            setBackgroundColor(0xFF222222.toInt())
        }, 1)
    }

    override fun onPause() {
        super.onPause()
        if (capturing) stopCapture()   // don't hold the mic in the background
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
