package com.example.FFTT04M

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.view.isGone
import com.google.android.material.slider.Slider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var fftHeatMap: FFTHeatMapView
    private lateinit var cropOverlay: CropOverlayView
    
    // Nullable controls for orientation safety
    private var micSpinner: Spinner? = null
    private var btnColor: Button? = null
    private var btnSaveInRow: Button? = null
    private var btnEscInRow: Button? = null
    private var saveEscContainer: View? = null
    private var mainButtonsLayout: View? = null
    private var micAndColorLayout: View? = null
    private var eqSlidersLayout: View? = null
    private var filterControlsLayout: View? = null
    private var sliderNoiseFilter: Slider? = null
    private var sliderNoiseRise: Slider? = null
    private var sliderNoiseFall: Slider? = null
    private var btnLatency: Button? = null

    private var noiseRiseCoeff = 0.015f
    private var noiseFallCoeff = 0.05f
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var sampleRate = 44100
    // Hands-free cough auto-capture on the Listen screen (forest-verdict CoughDetector on the live mic).
    private var coughDetector: com.example.FFTT04M.cough.CoughDetector? = null
    private var autoCoughCount = 0
    private val fftSize = 2048
    private val stepSize = 1024
    
    private val eqBands = floatArrayOf(100f, 300f, 1000f, 3000f, 8000f)
    private var filters = Array(5) { i ->
        BiquadFilter(BiquadFilter.Type.PEAKING, sampleRate.toFloat(), eqBands[i], 1.0f, 0f)
    }

    private var noiseFloor = FloatArray(fftSize / 2)
    private var noiseFilterStrength = 0f

    private var selectedDevice: AudioDeviceInfo? = null
    private var availableDevices = mutableListOf<AudioDeviceInfo>()
    private var audioDeviceCallback: AudioDeviceCallback? = null
    // True while we rebuild the spinner programmatically (hot-plug refresh). Setting an adapter posts
    // a spurious onItemSelected(0) that would otherwise clobber the persisted mic_device with the
    // default — so the listener ignores selections while this is set. Cleared after the async event.
    // True only after a real touch on the mic spinner, so programmatic selections (adapter swap,
    // setSelection, auto-position-0 on device removal) don't persist/restart and clobber the choice.
    private var userTouchedSpinner = false
    // Bluetooth SCO routes asynchronously: a capture started before the SCO link is up records from
    // the built-in mic. We verify the routed device shortly after start and re-route ONCE if needed.
    private var btRerouteAttempted = false
    // Guards the hot-plug callback against FALSE "disconnect": an intentional stop (restart, or going
    // to background) clears the SCO comm device, which removes the BT input and would otherwise be
    // read as the user unplugging the mic. We ignore device changes while restarting or backgrounded.
    private var btRestarting = false
    private var isForeground = false
    // User's BACKGROUND intent (persisted), distinct from CoughCaptureService.running: while Listen is
    // foreground the live view owns the mic, so the service runs only when armed AND we leave/lock.
    private var backgroundDesired = false
    private var isCalibrating = false
    @Volatile private var latestFrameEnergy = 0f

    private var audioBufferSize = sampleRate * 3
    private var audioCircularBuffer = FloatArray(audioBufferSize)
    @Volatile private var audioWriteIndex = 0

    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5f * (1 - cos(2 * PI * i.toDouble() / (fftSize - 1)))).toFloat()
    }
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.widget.TextView>(R.id.titleHeading)?.setVersionRoundelStart()

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        // Core Views (must exist)
        fftHeatMap = findViewById(R.id.fftHeatMap)
        cropOverlay = findViewById(R.id.cropOverlay)

        // Nullable Controls (Safe lookups)
        micSpinner = findViewById(R.id.micSpinner)
        btnColor = findViewById(R.id.btnColor)
        btnSaveInRow = findViewById(R.id.btnSaveInRow)
        btnEscInRow = findViewById(R.id.btnEscInRow)
        saveEscContainer = findViewById(R.id.saveEscContainer)
        eqSlidersLayout = findViewById(R.id.eqSlidersLayout)
        filterControlsLayout = findViewById(R.id.filterControlsLayout)
        btnLatency = findViewById(R.id.btnLatency)

        mainButtonsLayout = findViewById(R.id.mainButtonsLayout)
        micAndColorLayout = findViewById(R.id.micAndColorLayout)
        sliderNoiseFilter = findViewById(R.id.sliderNoiseFilter)
        sliderNoiseRise = findViewById(R.id.sliderNoiseRise)
        sliderNoiseFall = findViewById(R.id.sliderNoiseFall)

        btnLatency?.setOnClickListener { runLatencyMeasurement() }

        findViewById<Button>(R.id.btnGallery).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, GalleryActivity::class.java)) }
            // Long-press the Gallery button → hands-free cough auto-capture (blue_sky expansion).
            setOnLongClickListener {
                startActivity(Intent(this@MainActivity, com.example.FFTT04M.cough.CoughCaptureActivity::class.java))
                true
            }
        }

        // BACKGROUND: arm/disarm always-on hands-free cough auto-capture. While Listen is foreground the
        // live FFT owns the mic; the armed service runs only when you leave Listen or lock (see onStop).
        backgroundDesired = prefs.getBoolean("bg_capture_enabled", false)
        findViewById<Button>(R.id.btnBackground)?.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Grant the mic first: tap Listen once, then try again.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            backgroundDesired = !backgroundDesired
            prefs.edit { putBoolean("bg_capture_enabled", backgroundDesired) }
            if (backgroundDesired) {
                Toast.makeText(this, "Background capture armed — runs when you leave Listen or lock the screen.", Toast.LENGTH_LONG).show()
            } else {
                if (CoughCaptureService.running) CoughCaptureService.stop(this)
                Toast.makeText(this, "Background cough capture off", Toast.LENGTH_SHORT).show()
            }
            updateBackgroundButton()
        }

        findViewById<Button>(R.id.btnQuitTop).setOnClickListener {
            showQuitSanityCheck()
        }

        btnSaveInRow?.setOnClickListener { saveFragment() }
        btnEscInRow?.setOnClickListener {
            hideFrozenUi()
            fftHeatMap.isFrozen = false
            startRecording()
        }

        val historySize = (3 * sampleRate) / stepSize
        fftHeatMap.setMaxHistory(historySize)
        fftHeatMap.setParams(fftSize, sampleRate.toFloat(), stepSize)
        
        setupEqSliders()
        
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) {
            setupNoiseFilter()
            setupColorSpinner()
        } else {
            noiseFilterStrength = prefs.getFloat("noise_filter_strength", 0f)
            fftHeatMap.setColorScheme(ColorMaps.loadGlobal(this))
            scaleLandscapeControls()
        }

        findViewById<android.view.ViewGroup>(android.R.id.content).post {
            updateAllLabelPositions()
            fitSpinner(micSpinner)
        }

        applyAutoSizeText(findViewById(android.R.id.content))

        fftHeatMap.onSingleTap = {
            fftHeatMap.isFrozen = !fftHeatMap.isFrozen
            if (fftHeatMap.isFrozen) {
                stopRecording()
                showFrozenUi()
            } else {
                hideFrozenUi()
                startRecording()
            }
        }

        fftHeatMap.onDoubleTap = {
            fftHeatMap.isFrozen = false
            hideFrozenUi()
            startRecording()
        }

        requestPermissions()
        ensureStorageAccess()
    }

    // Returns from the system "All files access" settings screen (API 30+); migrate if now granted.
    private val allFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (GalleryTransfer.hasPublicStorageAccess(this)) runMigration()
    }

    /** Ensure recordings can live in public storage (Documents/FFTT04M) so they survive uninstall.
     *  API ≤29: WRITE_EXTERNAL_STORAGE rides along in the up-front batch. API 30+: "All files access"
     *  can only be granted from Settings, so explain why and deep-link there. */
    private fun ensureStorageAccess() {
        if (GalleryTransfer.hasPublicStorageAccess(this)) { runMigration(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Keep recordings after uninstall")
                .setMessage("To store recordings in Documents/FFTT04M so they survive reinstalling the app, " +
                    "allow “All files access” on the next screen.")
                .setPositiveButton("Open settings") { _, _ ->
                    val pkgIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"))
                    try { allFilesLauncher.launch(pkgIntent) } catch (_: Exception) {
                        try { allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        catch (_: Exception) {}
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun runMigration() {
        thread { try { GalleryTransfer.migrateToPublicIfNeeded(this) } catch (_: Throwable) {} }
    }

    private fun requestPermissions() {
        // Ask for everything the app can ever need, once, up front — so device-to-device transfer
        // and in-app pairing never surprise the user with a camera/Bluetooth prompt mid-flow. Only
        // RECORD_AUDIO is critical (it gates audio init); the rest are best-effort pre-grants.
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_SCAN
        } else {
            // Pre-31 Bluetooth discovery needs location to return any results.
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        // API ≤29 reaches public storage via WRITE_EXTERNAL_STORAGE (API 30+ uses All-files access).
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val toAsk = wanted.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isEmpty()) {
            initAudio()
        } else {
            ActivityCompat.requestPermissions(this, toAsk.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            // The batch may include several permissions; find RECORD_AUDIO's result specifically
            // rather than assuming it's first, then gate audio init on it.
            val i = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            val audioGranted = if (i >= 0) grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
                else ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioGranted) initAudio()
            // If WRITE_EXTERNAL_STORAGE (API ≤29) was just granted, migrate recordings to public storage.
            if (GalleryTransfer.hasPublicStorageAccess(this)) runMigration()
        }
    }

    private fun initAudio() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        availableDevices = enumerateInputs(audioManager)
        setupMicSpinnerWithDevices(availableDevices)
        registerDeviceCallback()

        selectedDevice?.let { dev ->
            val index = availableDevices.indexOf(dev)
            if (index >= 0) {
                micSpinner?.setSelection(index)
            }
        }

        startRecording()
    }

    private fun showFrozenUi() {
        saveEscContainer?.visibility = View.VISIBLE
        micAndColorLayout?.let { if (it.id != -1) it.visibility = View.GONE }
        mainButtonsLayout?.let { if (it.id != -1) it.visibility = View.GONE }
        cropOverlay.visibility = View.VISIBLE
    }

    private fun hideFrozenUi() {
        saveEscContainer?.visibility = View.GONE
        micAndColorLayout?.let { if (it.id != -1) it.visibility = View.VISIBLE }
        mainButtonsLayout?.let { if (it.id != -1) it.visibility = View.VISIBLE }
        cropOverlay.visibility = View.GONE
    }

    private fun showQuitSanityCheck() {
        AlertDialog.Builder(this)
            .setTitle("Quit")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { _, _ -> finish() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun saveFragment() {
        val rect = cropOverlay.cropRect
        val w = cropOverlay.width.toFloat()
        
        val startFrac = rect.left / w
        val endFrac = rect.right / w
        
        val totalSamples = audioBufferSize
        val fragmentStart = (startFrac * totalSamples).toInt()
        val fragmentEnd = (endFrac * totalSamples).toInt()
        val fragmentSize = fragmentEnd - fragmentStart
        
        if (fragmentSize <= 0) return

        val fragment = FloatArray(fragmentSize)
        val frozenWriteIndex = audioWriteIndex
        for (i in 0 until fragmentSize) {
            val idx = (frozenWriteIndex - totalSamples + fragmentStart + i + totalSamples * 10) % totalSamples
            fragment[i] = audioCircularBuffer[idx]
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // Audio is saved by saveFragmentAudio below: FLAC preferred, WAV fallback.
        
        saveFragmentAudio(GalleryTransfer.recordingsDir(this), timestamp, fragment)
        fftHeatMap.saveIcon(rect, "$timestamp.png")
        
        hideFrozenUi()
        fftHeatMap.isFrozen = false
        startRecording()
    }

    /**
     * Persist the captured fragment as 16-bit PCM WAV (works reliably on all devices).
     * Returns the file written, or null if encoding failed.
     *
     * FLAC container format is broken in the MediaCodec FLAC encoder (writes raw bytes with no
     * BUFFER_FLAG_CODEC_CONFIG handling), so we use WAV exclusively for consistency and reliability.
     */
    private fun saveFragmentAudio(dir: File?, timestamp: String, data: FloatArray): File? {
        val wavFile = File(dir, "$timestamp.wav")
        return try {
            encodeWav(wavFile, data)
            wavFile
        } catch (e: Throwable) {
            android.util.Log.e("FFTT04M", "WAV encoding failed: ${e.message}")
            try { if (wavFile.exists()) wavFile.delete() } catch (_: Throwable) {}
            null
        }
    }

    private fun encodeFlac(file: File, data: FloatArray) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)

        // createEncoderByType throws if no FLAC encoder exists on this device -> caller falls back to WAV.
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        var fos: FileOutputStream? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            fos = FileOutputStream(file)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleIndex = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                        inputBuffer.clear()

                        val samplesToCopy = min(data.size - sampleIndex, inputBuffer.remaining() / 2)
                        for (unused in 0 until samplesToCopy) {
                            val s = (data[sampleIndex++] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                            inputBuffer.putShort(s)
                        }

                        if (sampleIndex >= data.size) {
                            codec.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), 0, 0)
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                    val outData = ByteArray(info.size)
                    outputBuffer.get(outData, 0, info.size)
                    fos.write(outData)
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
            try { fos?.close() } catch (_: Throwable) {}
        }
    }

    /** Minimal 16-bit PCM, mono, little-endian WAV writer used as the FLAC fallback. */
    private fun encodeWav(file: File, data: FloatArray) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = data.size * 2
        FileOutputStream(file).use { fos ->
            val header = ByteArray(44)
            val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            bb.putInt(36 + dataSize)
            bb.put(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
            bb.put(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
            bb.putInt(16)
            bb.putShort(1)
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(byteRate)
            bb.putShort(blockAlign.toShort())
            bb.putShort(bitsPerSample.toShort())
            bb.put(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
            bb.putInt(dataSize)
            fos.write(header)

            val pcm = ByteArray(dataSize)
            val pb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (sample in data) {
                val s = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                pb.putShort(s)
            }
            fos.write(pcm)
        }
    }

    private fun setupColorSpinner() {
        val button = btnColor ?: return
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return

        val savedColorScheme = ColorMaps.loadGlobal(this)
        fftHeatMap.setColorScheme(savedColorScheme)
        styleColorButton(savedColorScheme)
        button.setOnClickListener {
            showColorSchemeDialog(ColorMaps.loadGlobal(this)) { sel ->
                fftHeatMap.setColorScheme(sel)
                ColorMaps.saveGlobal(this, sel)
                styleColorButton(sel)
            }
        }
    }

    /** Style the COLOR button to preview the active scheme (bg = high colour, text = low colour). */
    private fun styleColorButton(idx: Int) {
        btnColor?.apply {
            text = "COLOR"
            backgroundTintList = android.content.res.ColorStateList.valueOf(fftHeatMap.highColorFor(idx))
            setTextColor(fftHeatMap.lowColorFor(idx))
        }
    }

    /** On big (tablet) screens, thicken the landscape top buttons proportionally so they aren't stuck
     *  at phone size. Fonts scale via uiScale() elsewhere; this matches the button heights to them. */
    private fun scaleLandscapeControls() {
        val s = uiScale(resources)
        if (s <= 1.01f) return
        intArrayOf(R.id.btnQuitTop, R.id.btnGallery, R.id.btnLatency).forEach { id ->
            findViewById<View>(id)?.let { v ->
                val lp = v.layoutParams ?: return@let
                if (lp.height > 0) {
                    lp.height = (lp.height * s).toInt()
                    v.layoutParams = lp
                }
            }
        }
    }

    private fun fitSpinner(s: Spinner?) {
        s?.post { (s.selectedView as? TextView)?.let { it.setMaxTextSizeToFit(it.text.toString()) } }
    }

    private fun setupMicSpinnerWithDevices(devices: List<AudioDeviceInfo>) {
        val spinner = micSpinner ?: return
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return

        val deviceNames = devices.map { it.productName.toString() + " (" + getDeviceTypeName(it.type) + ")" }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_orange, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Restore the globally-persisted mic choice; setSelection here is programmatic (no touch) so
        // the listener below ignores it and won't clobber the saved choice.
        val savedName = prefs.getString("mic_device", null)
        if (savedName != null) {
            val idx = deviceNames.indexOf(savedName)
            if (idx >= 0) {
                selectedDevice = devices[idx]
                spinner.setSelection(idx)
                fitSpinner(spinner)
            }
        }

        // Touch-based guard: a spinner fires onItemSelected for programmatic changes too (adapter swap,
        // setSelection, auto-position-0 after a device removal). Those were overwriting the persisted
        // mic with the built-in default, so a reconnected mic couldn't be reacquired. Only a real TAP
        // sets userTouchedSpinner, so only a genuine user choice persists / restarts.
        spinner.setOnTouchListener { _, _ -> userTouchedSpinner = true; false }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isCalibrating || !userTouchedSpinner) return
                userTouchedSpinner = false
                val newDevice = devices[position]
                if (newDevice != selectedDevice) {
                    selectedDevice = newDevice
                    prefs.edit { putString("mic_device", deviceNames[position]) }
                    fitSpinner(micSpinner)
                    restartRecording()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            else -> "Other"
        }
    }

    /**
     * Selectable input devices = the real input list PLUS Bluetooth comm devices (SCO/LE). A BT
     * headset's mic is NOT in GET_DEVICES_INPUTS until SCO is active — but you can't select it to
     * start SCO if it never shows. getAvailableCommunicationDevices (API 31+) lists BT comm devices
     * before SCO, so they appear in the spinner; selecting one starts SCO in startRecording.
     */
    private fun enumerateInputs(am: AudioManager): MutableList<AudioDeviceInfo> {
        val list = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The input-list BT entry has a DIFFERENT id than the comm-device one and is NOT valid for
            // setCommunicationDevice() — selecting it can't start SCO. Replace any input-list BT with
            // the comm-device version, which IS routable, so the BT mic is both visible AND recordable.
            list.removeAll { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            for (d in am.availableCommunicationDevices) {
                if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) list.add(d)
            }
        }
        return list
    }

    private fun Slider.setSafeValue(v: Float) {
        val step = this.stepSize
        if (step > 0f) {
            val numSteps = round((v - valueFrom) / step)
            this.value = (valueFrom + numSteps * step).coerceIn(valueFrom, valueTo)
        } else {
            this.value = v.coerceIn(valueFrom, valueTo)
        }
    }

    private fun setupEqSliders() {
        val sliderIds = intArrayOf(R.id.eq100, R.id.eq300, R.id.eq1k, R.id.eq3k, R.id.eq8k)
        val valueTxtIds = intArrayOf(R.id.txtEqValue100, R.id.txtEqValue300, R.id.txtEqValue1k, R.id.txtEqValue3k, R.id.txtEqValue8k)
        for (i in 0 until 5) {
            val slider = findViewById<Slider>(sliderIds[i]) ?: continue
            val valueTxt = findViewById<TextView>(valueTxtIds[i])
            val key = "eq_gain_$i"
            val savedGain = prefs.getFloat(key, 0f)
            slider.setSafeValue(savedGain)
            valueTxt?.text = getString(R.string.db_value, savedGain.toInt())
            filters[i].gainDb = savedGain
            filters[i].updateCoefficients()

            slider.addOnChangeListener { s, value, _ ->
                filters[i].gainDb = value
                filters[i].updateCoefficients()
                valueTxt?.text = getString(R.string.db_value, value.toInt())
                prefs.edit { putFloat(key, value) }
                updateLabelPosition(s, valueTxt)
            }
        }
    }

    private fun setupNoiseFilter() {
        val filter = sliderNoiseFilter ?: return
        val txtFilterValue = findViewById<TextView>(R.id.txtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.txtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.txtFallValue)

        noiseFilterStrength = prefs.getFloat("noise_filter_strength", 0f)
        filter.setSafeValue(noiseFilterStrength)
        txtFilterValue?.text = "${(noiseFilterStrength * 100).toInt()}\n%"
        adjustSliderThickness(filter, txtFilterValue)
        filter.addOnChangeListener { slider, value, _ ->
            noiseFilterStrength = value
            txtFilterValue?.text = getString(R.string.percent_value, (value * 100).toInt())
            prefs.edit { putFloat("noise_filter_strength", value) }
            if (value == 0f) {
                noiseFloor.fill(0f)
            }
            updateLabelPosition(slider, txtFilterValue)
        }

        sliderNoiseRise?.let { sRise ->
            sRise.valueFrom = 0f
            sRise.valueTo = 3f
            sRise.stepSize = 0.01f
            val savedRiseMs = prefs.getFloat("noise_filter_rise_ms", 50f).coerceIn(1f, 1000f)
            sRise.setSafeValue(log10(savedRiseMs.toDouble()).toFloat())
            adjustSliderThickness(sRise, txtRiseValue)
            sRise.addOnChangeListener { slider, value, _ ->
                val ms = 10.0.pow(value.toDouble()).toFloat()
                prefs.edit { putFloat("noise_filter_rise_ms", ms) }
                updateCoeffsFromMs()
                updateLabelPosition(slider, txtRiseValue)
            }
        }

        sliderNoiseFall?.let { sFall ->
            sFall.valueFrom = 0f
            sFall.valueTo = 3f
            sFall.stepSize = 0.01f
            val savedFallMs = prefs.getFloat("noise_filter_fall_ms", 200f).coerceIn(1f, 1000f)
            sFall.setSafeValue(log10(savedFallMs.toDouble()).toFloat())
            adjustSliderThickness(sFall, txtFallValue)
            sFall.addOnChangeListener { slider, value, _ ->
                val ms = 10.0.pow(value.toDouble()).toFloat()
                prefs.edit { putFloat("noise_filter_fall_ms", ms) }
                updateCoeffsFromMs()
                updateLabelPosition(slider, txtFallValue)
            }
        }

        updateCoeffsFromMs()
    }

    private fun updateCoeffsFromMs() {
        val frameDurationMs = (stepSize.toFloat() / sampleRate) * 1000f
        val k = ln(2.0f) * frameDurationMs
        
        val riseMs = 10.0.pow(sliderNoiseRise?.value?.toDouble() ?: 1.7).toFloat()
        val fallMs = 10.0.pow(sliderNoiseFall?.value?.toDouble() ?: 2.3).toFloat()
        
        noiseRiseCoeff = (k / riseMs).coerceIn(0.001f, 1.0f)
        noiseFallCoeff = (k / fallMs).coerceIn(0.001f, 1.0f)
        
        findViewById<TextView>(R.id.txtRiseValue)?.text = getString(R.string.ms_value, riseMs.toInt())
        findViewById<TextView>(R.id.txtFallValue)?.text = getString(R.string.ms_value, fallMs.toInt())
    }

    private fun updateAllLabelPositions() {
        val txtFilterValue = findViewById<TextView>(R.id.txtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.txtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.txtFallValue)
        
        sliderNoiseFilter?.let { adjustSliderThickness(it, txtFilterValue) }
        sliderNoiseRise?.let { adjustSliderThickness(it, txtRiseValue) }
        sliderNoiseFall?.let { adjustSliderThickness(it, txtFallValue) }
        
        val sliderIds = intArrayOf(R.id.eq100, R.id.eq300, R.id.eq1k, R.id.eq3k, R.id.eq8k)
        val valueTxtIds = intArrayOf(R.id.txtEqValue100, R.id.txtEqValue300, R.id.txtEqValue1k, R.id.txtEqValue3k, R.id.txtEqValue8k)
        for (i in 0 until 5) {
            val slider = findViewById<Slider>(sliderIds[i]) ?: continue
            val label = findViewById<TextView>(valueTxtIds[i]) ?: continue
            adjustSliderThickness(slider, label)
        }
    }

    private fun barColorForSlider(slider: Slider): Int =
        if (slider.id == R.id.sliderNoiseFilter || slider.id == R.id.sliderNoiseRise || slider.id == R.id.sliderNoiseFall)
            Color.CYAN else Color.GREEN

    // Unified value-bar rendering (see ViewUtils.styleValueBarSlider) — shared with FFT-Viewer & Wavelet.
    private fun adjustSliderThickness(slider: Slider, label: TextView?) =
        styleValueBarSlider(slider, label, barColorForSlider(slider))

    private fun updateLabelPosition(slider: Slider, label: TextView?) =
        updateValueBarLabel(slider, label, barColorForSlider(slider))

    private var activeEncoding = AudioFormat.ENCODING_PCM_FLOAT

    /** Reflect background-capture service state on the BACKGROUND toggle (label + colour). */
    private fun updateBackgroundButton(on: Boolean = backgroundDesired) {
        val btn = findViewById<Button>(R.id.btnBackground) ?: return
        btn.text = if (on) "● BG ON" else getString(R.string.btn_background)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (on) "#CC0000" else "#444444"))
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        // Refresh the mic spinner so it reflects what's actually connected NOW — drops a mic unplugged
        // while we were away (the stale-entry report) and surfaces newly connected ones.
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            // Diagnostic: raw type ints for both the input list and the comm-device list, to see if a
            // BT mic shows up anywhere and as what type. (BT type ints: SCO=7, A2DP=8, BLE_HEADSET=26.)
            android.util.Log.i("FFTT04M", "INPUTS: [" +
                am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { "${it.productName}/t${it.type}#${it.id}" } + "]")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.util.Log.i("FFTT04M", "COMM: [" +
                am.availableCommunicationDevices.joinToString { "${it.productName}/t${it.type}#${it.id}" } + "]")
            availableDevices = enumerateInputs(am)
            setupMicSpinnerWithDevices(availableDevices)
        }
        // Returning to Listen ALWAYS resumes the live view (this is what was frozen when BACKGROUND
        // was on). startRecording takes the mic back from the background service for the live FFT.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            fftHeatMap.isFrozen = false
            hideFrozenUi()
            startRecording()
        }
        updateBackgroundButton()
    }

    override fun onStop() {
        super.onStop()
        // Privacy: release the mic once Listen isn't visible (on API < 28 a backgrounded AudioRecord
        // would otherwise capture REAL audio). If BACKGROUND is armed, hand off to the foreground
        // CoughCaptureService so auto-capture continues while away/locked.
        isForeground = false   // also tells the hot-plug callback to ignore the SCO teardown below
        stopRecording()
        if (backgroundDesired &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CoughCaptureService.start(this)
        }
    }

    private fun startRecording(isRetry: Boolean = false) {
        if (recording.get()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        AudioOutputMonitor.start(this)   // suspend cough detection while the device plays audio output

        // The live spectrogram needs the mic, which can't be shared with the background capture
        // service — pause the service for the live view. BACKGROUND stays ARMED (backgroundDesired),
        // so it resumes when we leave Listen (onStop); don't change the button's armed state here.
        if (CoughCaptureService.running) CoughCaptureService.stop(this)

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Drop a stale selection (e.g. a mic that disconnected while we weren't recording) so we
        // never route to a dead device; null means "system default input".
        selectedDevice?.let { sel ->
            val stillConnected = enumerateInputs(audioManager).any { it.id == sel.id }
            if (!stillConnected) selectedDevice = null
        }

        // Bluetooth mics (classic SCO or LE Audio) only capture through the COMMUNICATION route, and
        // that route ONLY actually streams when the AudioManager is in MODE_IN_COMMUNICATION —
        // setCommunicationDevice() alone leaves capture silently on the built-in mic (the reported
        // bug). Establish mode + route here; below we use VOICE_COMMUNICATION (the source that follows
        // the communication device; VOICE_RECOGNITION/UNPROCESSED stick to the built-in mic).
        val isBtMic = selectedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                selectedDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        if (isBtMic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION   // REQUIRED for SCO audio
                    if (!audioManager.setCommunicationDevice(selectedDevice!!)) selectedDevice = null
                } catch (e: Exception) {
                    selectedDevice = null // SCO/LE route unavailable; fall back to default
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } catch (e: Exception) {
                    selectedDevice = null
                }
            }
        }
        val btRoute = isBtMic && selectedDevice != null

        var encoding = AudioFormat.ENCODING_PCM_FLOAT
        // SCO tops out at 8/16 kHz; try 16 kHz first for BT, full rate otherwise.
        var tryRates = if (btRoute) intArrayOf(16000, 44100) else intArrayOf(44100, 16000)
        var record: AudioRecord? = null
        var activeRate = 44100

        outer@for (rate in tryRates) {
            // Bluetooth SCO is 16-bit PCM only — float capture would silently fall back to built-in.
            val encs = if (rate == 44100 && !btRoute) intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
                       else intArrayOf(AudioFormat.ENCODING_PCM_16BIT)

            for (enc in encs) {
                val minSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, enc)
                if (minSize <= 0) continue

                val bufSize = max(minSize, fftSize * (if (enc == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2))
                // BT: with MODE_IN_COMMUNICATION + setCommunicationDevice active, MIC follows the SCO
                // route and gives the raw mic. Try it FIRST — VOICE_COMMUNICATION's echo-canceller can
                // gate to SILENCE over SCO (the solid-color FFT), so it's only the fallback now.
                val sources = if (btRoute)
                    intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                else MicSource.sources(this@MainActivity)   // UNPROCESSED when trusted, else MIC

                for (src in sources) {
                    try {
                        val r = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, enc, bufSize)
                        if (r.state == AudioRecord.STATE_INITIALIZED) {
                            record = r
                            encoding = enc
                            activeRate = rate
                            android.util.Log.i("FFTT04M", "Started recording: rate=$rate, enc=$enc, src=$src")
                            break@outer
                        }
                        r.release()
                    } catch (e: Exception) {
                        android.util.Log.e("FFTT04M", "Failed AudioRecord init (rate=$rate, enc=$enc, src=$src): ${e.message}")
                    }
                }
            }
        }

        if (record == null) {
            // The mic HAL can briefly refuse a re-acquire right after release (e.g. returning from
            // Gallery/another app) — that left the live FFT frozen. Retry once shortly before failing.
            if (!isRetry) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!recording.get() && isForeground && !CoughCaptureService.running) startRecording(isRetry = true)
                }, 350)
            } else {
                Toast.makeText(this, "Failed to initialize audio recording", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (activeRate != sampleRate) {
            sampleRate = activeRate
            audioBufferSize = sampleRate * 3
            audioCircularBuffer = FloatArray(audioBufferSize)
            audioWriteIndex = 0
            // Re-init filters for new sample rate
            filters = Array(5) { i ->
                BiquadFilter(BiquadFilter.Type.PEAKING, sampleRate.toFloat(), eqBands[i], 1.0f, 0f)
            }
            fftHeatMap.setParams(fftSize, sampleRate.toFloat(), stepSize)
        }

        activeEncoding = encoding
        selectedDevice?.let { record.setPreferredDevice(it) }
        
        audioRecord = record
        recording.set(true)
        // STOPGAP specificity for noisy real-world audio (movie speech/music) until the forest is
        // retrained with those as hard negatives. Higher threshold = fewer false captures (model
        // default is 0.48). Remove/lower once the retrained model ships.
        com.example.FFTT04M.cough.CoughClassifier.thresholdOverride = com.example.FFTT04M.cough.CoughClassifier.PRECISION_THRESHOLD
        // Auto-detect + auto-save coughs from the live mic (forest verdict via CoughClassifier).
        coughDetector = com.example.FFTT04M.cough.CoughDetector(sampleRate) { onAutoCough(it) }
        record.startRecording()

        // Diagnostics: log the device audio is ACTUALLY captured from (vs. what the user picked). This
        // is how to confirm the "shows BT connected but records from the built-in mic" symptom.
        val routedNow = record.routedDevice
        android.util.Log.i("FFTT04M",
            "Capture routed to: ${routedNow?.productName} (type=${routedNow?.type}, id=${routedNow?.id}); " +
            "selected=${selectedDevice?.productName}(${selectedDevice?.id}) btRoute=$btRoute")
        if (btRoute) {
            // SCO link may still be warming up; re-check once and restart capture if we landed on the
            // wrong (built-in) input so the AudioRecord re-binds to the now-connected BT mic.
            val am = audioManager
            val want = selectedDevice
            Handler(Looper.getMainLooper()).postDelayed({
                val rec = audioRecord ?: return@postDelayed
                val routed = rec.routedDevice
                val onBt = routed != null && routed.id == want?.id
                android.util.Log.i("FFTT04M",
                    "BT capture re-check: routed=${routed?.productName}(type=${routed?.type}) " +
                    "commDevice=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.communicationDevice?.productName else "n/a"} " +
                    "onBt=$onBt lastFrameEnergy=${"%.4f".format(latestFrameEnergy)} (≈0 = silent capture)")
                if (!onBt && !btRerouteAttempted && recording.get() && selectedDevice?.id == want?.id) {
                    btRerouteAttempted = true
                    android.util.Log.w("FFTT04M", "BT mic not yet routed (SCO warm-up) — restarting capture once")
                    restartRecording()
                } else {
                    btRerouteAttempted = false   // settled (on BT, or gave up) — allow a future attempt
                }
            }, 1200)
        } else {
            btRerouteAttempted = false
        }

        recordingThread = Thread {
            val audioBuffer = FloatArray(stepSize)
            val fftInput = FloatArray(fftSize)
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            val shortBuffer = if (activeEncoding == AudioFormat.ENCODING_PCM_16BIT) ShortArray(stepSize) else null

            while (recording.get() && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = if (activeEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    record.read(audioBuffer, 0, stepSize, AudioRecord.READ_BLOCKING)
                } else {
                    val r = record.read(shortBuffer!!, 0, stepSize, AudioRecord.READ_BLOCKING)
                    if (r > 0) {
                        for (i in 0 until r) audioBuffer[i] = shortBuffer[i] / 32768f
                    }
                    r
                }

                if (read > 0) {
                    // RAW mic → recording buffer first. The EQ must NOT colour what we save/play;
                    // it is a DISPLAY-only control now (applied to the FFT copy below).
                    for (i in 0 until read) {
                        audioCircularBuffer[audioWriteIndex] = audioBuffer[i]
                        audioWriteIndex = (audioWriteIndex + 1) % audioBufferSize
                    }

                    // Feed the RAW mic (pre-EQ) to the hands-free cough detector — but NOT while the
                    // device is playing audio output (notification chimes, BT-transfer sounds, our own
                    // PLAY), so we don't auto-capture our own output. The waterfall still scrolls.
                    if (!AudioOutputMonitor.active) {
                        coughDetector?.process(if (read == audioBuffer.size) audioBuffer.copyOf() else audioBuffer.copyOf(read))
                    }

                    // EQ affects only the waterfall: run the biquads on the samples fed to the FFT.
                    // (Run on every sample to keep the IIR state continuous.)
                    for (i in 0 until read) {
                        var s = audioBuffer[i]
                        for (f in filters) s = f.process(s)
                        audioBuffer[i] = s
                    }

                    System.arraycopy(fftInput, read, fftInput, 0, fftSize - read)
                    System.arraycopy(audioBuffer, 0, fftInput, fftSize - read, read)

                    for (i in 0 until fftSize) {
                        real[i] = fftInput[i] * hannWindow[i]
                        imag[i] = 0f
                    }

                    FFTUtils.compute(real, imag)

                    val magnitudes = FloatArray(fftSize / 2)
                    var energy = 0f
                    for (i in 0 until fftSize / 2) {
                        var mag = sqrt(real[i] * real[i] + imag[i] * imag[i])

                        if (noiseFilterStrength > 0f) {
                            if (noiseFloor[i] == 0f) {
                                noiseFloor[i] = mag
                            } else {
                                if (mag < noiseFloor[i]) {
                                    noiseFloor[i] = noiseFloor[i] * (1f - noiseFallCoeff) + mag * noiseFallCoeff
                                } else {
                                    noiseFloor[i] = noiseFloor[i] * (1f - noiseRiseCoeff) + mag * noiseRiseCoeff
                                }
                            }
                            val reduction = noiseFloor[i] * noiseFilterStrength
                            mag = (mag - reduction).coerceAtLeast(0f)
                        }

                        energy += mag
                        magnitudes[i] = (20 * log10(mag + 1e-9f) + 80) / 80f
                    }
                    latestFrameEnergy = energy
                    runOnUiThread { fftHeatMap.updateFFT(magnitudes) }
                }
            }
        }
        recordingThread?.start()
    }

    /**
     * Auto-saved cough from the live mic: draw white borders around it in the scrolling display,
     * use the between-borders spectrogram as the recording's default icon, write the Gallery WAV,
     * and flash an on-screen confirmation.
     */
    private fun onAutoCough(c: com.example.FFTT04M.cough.CoughDetector.CapturedCough) {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
        val dir = GalleryTransfer.recordingsDir(this) ?: filesDir
        val wav = java.io.File(dir, "cough_$ts.wav")
        val png = java.io.File(dir, "cough_$ts.png")
        // Borders + thumbnail of the captured cough (snapshot now, before it scrolls off-screen).
        val icon = fftHeatMap.markCoughAndSnapshot(c.pcm.size / stepSize)
        autoCoughCount++
        kotlin.concurrent.thread {                       // file I/O off the audio thread
            runCatching { com.example.FFTT04M.cough.CoughWav.write(wav, c.pcm, sampleRate) }
            icon?.let { bmp -> runCatching {
                java.io.FileOutputStream(png).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } }
            // Auto-label from the closest cough/non-cough DB match (no-op if no bundled ref).
            runCatching { com.example.FFTT04M.cough.ClipMatcher.annotate(this, wav, c.pcm, sampleRate) }
        }
        runOnUiThread { flashCoughSaved(autoCoughCount) }
    }

    /** Brief centred green "✓ cough saved (N)" overlay that fades out. */
    private fun flashCoughSaved(n: Int) {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val tv = android.widget.TextView(this).apply {
            text = "✓ cough saved  ($n)"
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xE0177245.toInt())
            textSize = 22f; gravity = android.view.Gravity.CENTER
            val p = (18 * resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, p / 2)
        }
        root.addView(tv, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        tv.alpha = 0f
        tv.animate().alpha(1f).setDuration(120).withEndAction {
            tv.animate().alpha(0f).setStartDelay(850).setDuration(450).withEndAction { root.removeView(tv) }
        }
    }

    private fun stopRecording() {
        recording.set(false)
        recordingThread?.join()
        coughDetector?.finish(); coughDetector = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val t = audioManager.communicationDevice?.type
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                audioManager.clearCommunicationDevice()
                audioManager.mode = AudioManager.MODE_NORMAL   // leave communication mode we set for SCO
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                if (audioManager.isBluetoothScoOn) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            } catch (_: Exception) {}
        }
    }

    private fun restartRecording() {
        // Mark the teardown+rebuild as intentional so the hot-plug callback doesn't misread the
        // transient SCO-device removal (from clearCommunicationDevice in stopRecording) as a
        // disconnect. Cleared after the queued add/remove device events have drained.
        btRestarting = true
        stopRecording()
        startRecording()
        Handler(Looper.getMainLooper()).postDelayed({ btRestarting = false }, 1500)
    }

    /**
     * Live mic hot-plug handling: when an input device is plugged in or pulled out mid-session we
     * (1) refresh the spinner immediately so new mics appear without a restart, and (2) keep the
     * user's chosen mic — falling back to the default while it's gone and reacquiring it the moment
     * it reconnects (see [handleDevicesChanged]).
     */
    private fun registerDeviceCallback() {
        if (audioDeviceCallback != null) return
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = handleDevicesChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = handleDevicesChanged()
        }
        audioManager.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        audioDeviceCallback = cb
    }

    private fun unregisterDeviceCallback() {
        audioDeviceCallback?.let {
            (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it)
        }
        audioDeviceCallback = null
    }

    /**
     * Re-read the input device list and refresh the spinner whenever mics are plugged/unplugged.
     *  - New mic appears  → it shows up in the spinner right away (no QUIT/restart needed).
     *  - Chosen mic pulled → fall back to the default for now, but KEEP the persisted choice so it
     *    can be reacquired (we no longer delete "mic_device").
     *  - Chosen mic reconnects → setupMicSpinnerWithDevices restores it from prefs; we detect the
     *    transition from "default" back to a real device and restart capture on it.
     */
    private fun handleDevicesChanged() {
        // Don't manage the mic while backgrounded (privacy / the background service owns it). We do NOT
        // bail on btRestarting here — that would swallow real reconnects (the reacquire regression);
        // btRestarting is only used below to suppress the FALSE disconnect from our own SCO teardown.
        if (!isForeground) return
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        availableDevices = enumerateInputs(audioManager)
        android.util.Log.i("FFTT04M", "devices changed: [" +
            availableDevices.joinToString { "${it.productName}/${getDeviceTypeName(it.type)}#${it.id}" } +
            "] selected=${selectedDevice?.productName}#${selectedDevice?.id} btRestarting=$btRestarting")

        val activeId = selectedDevice?.id
        // Active mic vanished:
        if (activeId != null && availableDevices.none { it.id == activeId }) {
            if (btRestarting) {
                // Our own restart transiently dropped the SCO device — NOT a real unplug. Refresh the
                // spinner but keep the selection so it reattaches when the device re-appears.
                setupMicSpinnerWithDevices(availableDevices)
                return
            }
            // Real disconnect → drop the dead handle (keep the persisted name) and use the default.
            val name = selectedDevice?.productName?.toString() ?: "the selected mic"
            selectedDevice = null
            setupMicSpinnerWithDevices(availableDevices)
            restartRecording()
            Toast.makeText(this, "Mic \"$name\" disconnected — using default (will reacquire on reconnect)", Toast.LENGTH_LONG).show()
            return
        }

        // Rebuild the spinner so newly-connected mics appear; this also restores the persisted choice
        // into selectedDevice if that mic is (now) present.
        val before = selectedDevice
        setupMicSpinnerWithDevices(availableDevices)
        if (before == null && selectedDevice != null) {     // chosen mic just came back
            android.util.Log.i("FFTT04M", "reacquired mic ${selectedDevice?.productName}#${selectedDevice?.id}")
            restartRecording()
            Toast.makeText(this, "Reacquired mic \"${selectedDevice?.productName}\"", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runLatencyMeasurement() {
        val chirpDurationMs = 100
        val numSamples = (sampleRate * chirpDurationMs / 1000f).toInt()
        val chirp = FloatArray(numSamples)
        val f0 = 1000f
        val f1 = 8000f
        val t1 = chirpDurationMs / 1000f
        
        for (i in 0 until numSamples) {
            val t = i.toFloat() / sampleRate
            val k = (f1 / f0).pow(1f / t1)
            val phase = 2f * PI.toFloat() * f0 * (k.pow(t) - 1f) / ln(k)
            chirp[i] = sin(phase) * (0.5f * (1f - cos(2f * PI.toFloat() * i / (numSamples - 1))))
        }

        Thread {
            var player: AudioTrack? = null
            try {
                player = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(chirp.size * 4)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                player.write(chirp, 0, chirp.size, AudioTrack.WRITE_BLOCKING)
                val captureSize = sampleRate
                val captured = FloatArray(captureSize)
                val startWriteIndex = audioWriteIndex
                
                player.play()
                Thread.sleep(1200)
                
                for (i in 0 until captureSize) {
                    val idx = (startWriteIndex + i) % audioBufferSize
                    captured[i] = audioCircularBuffer[idx]
                }
                player.stop()

                var maxCorr = -1f
                var maxLag = 0
                for (lag in 0 until captureSize - chirp.size) {
                    var corr = 0f
                    for (j in 0 until chirp.size) {
                        corr += captured[lag + j] * chirp[j]
                    }
                    if (corr > maxCorr) {
                        maxCorr = corr
                        maxLag = lag
                    }
                }
                val latencyMs = (maxLag.toFloat() / sampleRate * 1000).toInt()
                runOnUiThread { btnLatency?.text = "Latency ${latencyMs}ms" }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                player?.release()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterDeviceCallback()
        stopRecording()
    }
}
