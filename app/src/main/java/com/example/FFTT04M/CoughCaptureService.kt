package com.example.FFTT04M

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.FFTT04M.cough.CoughClassifier
import com.example.FFTT04M.cough.CoughDetector
import com.example.FFTT04M.cough.CoughWav
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Foreground MICROPHONE service: hands-free cough auto-capture that keeps running in the background
 * and behind the lock screen, saving each detected cough as a Gallery WAV. A persistent
 * "Listening…" notification with a Stop action is mandatory — Android only allows background mic
 * access from a foreground service.
 *
 * Power policy (per user decision): capture continuously while **charging OR battery > 20%**. On
 * battery at **≤ 20%** it pauses background capture (releases the mic + wake lock) — only the Listen
 * screen captures in that low-battery window — and auto-resumes when charged or back above 20%.
 *
 * Capture uses [MicSource] (UNPROCESSED when trusted, else MIC) and the precision-favoring
 * [CoughClassifier.PRECISION_THRESHOLD]. Legacy-safe: any overload stops capture rather than
 * crashing; a partial wake lock keeps the CPU alive with the screen off.
 */
class CoughCaptureService : Service() {

    private val sampleRate = 44100
    @Volatile private var capturing = false        // mic loop currently active
    private var captureThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var count = 0
    @Volatile private var lowBatteryPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        createChannel()
        startForegroundCompat("Listening for coughs… (0 saved)")
        running = true
        // Sticky ACTION_BATTERY_CHANGED → current state immediately; receiver handles changes.
        val sticky = try { registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
                     catch (_: Throwable) { null }
        applyPowerPolicy(sticky)
        return START_STICKY   // keep listening across transient kills
    }

    // ---- power policy -------------------------------------------------------------------------

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = applyPowerPolicy(i)
    }

    /** Capture allowed while charging/plugged or above the low-battery floor. */
    private fun batteryAllows(i: Intent?): Boolean {
        if (i == null) return true
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        return charging || pct > LOW_PCT
    }

    private fun applyPowerPolicy(batteryIntent: Intent?) {
        val allow = batteryAllows(batteryIntent)
        when {
            allow && !capturing -> {            // (re)start capture
                lowBatteryPaused = false
                acquireWakeLock()
                capturing = true
                captureThread = thread(name = "cough-capture-svc") { captureLoop() }
                updateNotification("Listening for coughs… ($count saved)")
            }
            !allow && capturing -> {            // pause for low battery
                capturing = false
                try { captureThread?.join(800) } catch (_: Throwable) {}
                releaseWakeLock()
                lowBatteryPaused = true
                updateNotification("Paused — battery ≤$LOW_PCT%. Listen screen still captures.")
            }
        }
    }

    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 4)
        // UNPROCESSED when trusted (else MIC), with the existing legacy-safe fallback loop.
        var recorder: AudioRecord? = null
        for (src in MicSource.sources(this)) {
            try {
                val r = AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                if (r.state == AudioRecord.STATE_INITIALIZED) { recorder = r; break } else r.release()
            } catch (_: Throwable) {}
        }
        if (recorder == null) { stopSelf(); return }

        CoughClassifier.thresholdOverride = CoughClassifier.PRECISION_THRESHOLD
        val detector = CoughDetector(sampleRate) { onCough(it) }
        val shortBuf = ShortArray(minBuf); val floatBuf = FloatArray(minBuf)
        try {
            recorder.startRecording()
            while (capturing) {
                val n = recorder.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
                try { detector.process(if (n == floatBuf.size) floatBuf else floatBuf.copyOf(n)) }
                catch (t: Throwable) {                  // overload on a weak device → stop, don't crash
                    updateNotification("Auto-capture stopped (device overloaded) · $count saved"); break
                }
            }
            detector.finish()
        } catch (t: Throwable) {
            // mic error mid-stream — fall through to cleanup
        } finally {
            try { recorder.stop() } catch (_: Throwable) {}
            recorder.release()
        }
    }

    private fun onCough(c: CoughDetector.CapturedCough) {
        val now = Date()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(now)
        val dir = GalleryTransfer.recordingsDir(this) ?: filesDir
        runCatching { CoughWav.write(File(dir, "cough_$ts.wav"), c.pcm, sampleRate) }
        // Live-refresh an open Gallery (reuses the import service's broadcast that triggers loadFiles()).
        runCatching { sendBroadcast(Intent(TransferService.ACTION_PROGRESS).setPackage(packageName)) }
        count++
        val seconds = c.pcm.size / sampleRate.toFloat()
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
        pushCaptureAlert(String.format(Locale.US, "FFT %.1f seconds captured at %s", seconds, clock))
        updateNotification("Listening for coughs… ($count saved)")
    }

    override fun onDestroy() {
        running = false; capturing = false
        try { unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}
        try { captureThread?.join(800) } catch (_: Throwable) {}
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- foreground notification --------------------------------------------------------------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CH, "Cough auto-capture", NotificationManager.IMPORTANCE_LOW))
        // Separate HIGH channel so each capture pings/peeks (the ongoing "Listening…" channel is silent/LOW).
        mgr.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Cough captured", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }

    /** Heads-up ping on every capture: "FFT 1.4 seconds captured at 14:23:07". */
    private fun pushCaptureAlert(text: String) {
        android.util.Log.i("CoughCapture", "alert: $text")
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Cough captured").setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)        // peek (heads-up) on pre-O too
            .setVibrate(longArrayOf(0, 60))                       // brief buzz so the ping is noticed
            .setOnlyAlertOnce(false).setAutoCancel(true)
            .setContentIntent(openGallery())
            .build()
        try { NotificationManagerCompat.from(this).notify(CAPTURE_NID, n) } catch (_: SecurityException) {}
    }

    private fun openGallery() = PendingIntent.getActivity(this, 0,
        Intent(this, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1,
            Intent(this, CoughCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("FFTT cough capture").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NID, n)
        } catch (e: Throwable) { try { startForeground(NID, n) } catch (_: Throwable) {} }
    }

    private fun updateNotification(text: String) {
        try { NotificationManagerCompat.from(this).notify(NID, buildNotification(text)) } catch (_: SecurityException) {}
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FFTT:coughCapture")
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {}
    }
    private fun releaseWakeLock() { try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {} }

    companion object {
        @Volatile var running = false; private set
        private const val CH = "cough_capture"
        private const val CH_ALERT = "cough_capture_alert"
        private const val NID = 4201
        private const val CAPTURE_NID = 4202
        private const val LOW_PCT = 20          // power-saver floor: ≤20% on battery → background pauses
        const val ACTION_STOP = "com.example.FFTT04M.STOP_COUGH_CAPTURE"
        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, CoughCaptureService::class.java))
        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, CoughCaptureService::class.java).setAction(ACTION_STOP))
    }
}
