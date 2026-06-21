package com.example.FFTT04M

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.edit
import androidx.core.view.isGone
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import java.io.File
import kotlin.math.*
import kotlin.concurrent.thread

class ViewerActivity : AppCompatActivity() {

    private lateinit var viewerFft: FFTHeatMapView
    private lateinit var sizeSpinner: Spinner
    private lateinit var stepSpinner: Spinner
    private lateinit var btnColor: Button
    private lateinit var btnGrid: Button
    private lateinit var blurSpinner: Spinner
    private lateinit var btnSweep: Button
    private var viewerProgress: android.widget.ProgressBar? = null
    private var isCoughAnalysisShown = false

    // Busy indicator shown only when a procedure runs longer than 100 ms (so quick refreshes don't
    // flash a spinner). Reference-counted; every beginBusy() is paired with an endBusy() in a finally.
    private val busyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var busyCount = 0
    private val busyShowRunnable = Runnable { if (busyCount > 0) viewerProgress?.visibility = View.VISIBLE }

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var filePath: String? = null

    private var sampleRate = 44100
    private var currentFftSize = 2048
    private var currentStepSize = 1024
    
    private val eqBands = floatArrayOf(100f, 300f, 1000f, 3000f, 8000f)
    private val filters = Array(5) { i ->
        BiquadFilter(BiquadFilter.Type.PEAKING, sampleRate.toFloat(), eqBands[i], 1.0f, 0f)
    }

    private val fftValues = intArrayOf(256, 512, 1024, 2048, 4096)
    private var rawPcmData: FloatArray? = null
    private lateinit var prefs: SharedPreferences

    private var noiseFilterStrength = 0f
    private var noiseRiseCoeff = 0.015f
    private var noiseFallCoeff = 0.05f

    // Retained from the last STANDARD render so PROCESSED PLAYBACK can reproduce the ENHANCE
    // post-processing audibly (WYSIWYH): per (frame,row) gain = processed / original magnitude,
    // applied to the STFT magnitude with the original phase. Null on tier-0 devices (memory) and
    // for engine renders (reassignment/etc. aren't cleanly invertible). Display rows are the same
    // 80–10000 Hz log axis used by the renderer.
    private var lastOrigGrid: Array<FloatArray>? = null
    private var lastProcGrid: Array<FloatArray>? = null
    private var lastGridFftSize = 0
    private var lastGridStep = 0
    private var lastGridViewHeight = 0
    // Enhance modes. Indices 0..4 are the mutually-exclusive *engines* that build the base map
    // (shown as radio buttons). Indices 5..8 are *post-processors* that stack, in order, on top of
    // whichever engine ran (or on the plain STFT when no engine is selected).
    private val enhanceModeNames = arrayOf(
        "Sweep+", "Constant-Q", "Reassignment", "Synchrosqueeze", "Multitaper",
        "Gaussian", "Bilateral", "TV Denoise", "Butterworth", "Anisotropic", "Gabor ridges", "Frangi ridges"
    )
    private val enhanceSelected = BooleanArray(enhanceModeNames.size)
    private val engineCount = 5
    // Post-processors heavy enough to gate off legacy hardware (see DeviceCaps). Indices into
    // enhanceModeNames: Gabor's oriented convolution bank and Frangi's multi-scale Hessian.
    private val heavyEnhanceModes = setOf(10, 11)

    private val refreshLock = Any()
    private var refreshCount = 0
    private var activeThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        viewerFft = findViewById(R.id.viewerFft)
        viewerProgress = findViewById(R.id.viewerProgress)
        filePath = intent.getStringExtra("FILE_PATH")
        // After a reinstall the app-private per-recording prefs are gone; restore them from the
        // <base>.json sidecar in public storage if one survived (tier-gated for legacy devices).
        filePath?.let { GalleryTransfer.restoreMetaSidecarIfNeeded(this, File(it).nameWithoutExtension) }
        // Persist analysis/display settings per recording: each file gets its own prefs namespace.
        prefs = getSharedPreferences(prefsNameForFile(filePath), Context.MODE_PRIVATE)

        setupControls()
        applyAutoSizeText(findViewById(android.R.id.content))

        viewerFft.post {
            // Restore this recording's saved pan/zoom, and persist it whenever the user changes it.
            viewerFft.setViewState(
                prefs.getFloatCoerced("view_zoom_x", 1f), prefs.getFloatCoerced("view_zoom_y", 1f),
                prefs.getFloatCoerced("view_off_x", 0f), prefs.getFloatCoerced("view_off_y", 0f)
            )
            viewerFft.onViewStateChanged = { saveViewTransform() }

            filePath?.let { path ->
                loadAndDecode(File(path))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<android.view.View>(android.R.id.content).post {
            updateAllLabelPositions()
        }
    }

    private fun saveViewTransform() {
        val s = viewerFft.getViewState()
        prefs.edit {
            putFloat("view_zoom_x", s[0]); putFloat("view_zoom_y", s[1])
            putFloat("view_off_x", s[2]); putFloat("view_off_y", s[3])
        }
    }

    /**
     * Per-recording settings namespace. Each recording stores its own analysis/display settings so
     * reopening it restores exactly what was last used for that file. Falls back to the shared
     * "app_settings" when there is no file (shouldn't happen from the Gallery).
     */
    private fun prefsNameForFile(path: String?): String {
        if (path.isNullOrEmpty()) return "app_settings"
        val base = File(path).nameWithoutExtension
        val safe = buildString { for (c in base) append(if (c.isLetterOrDigit()) c else '_') }
        return "rec_$safe"
    }

    private fun updateAllLabelPositions() {
        if (findViewById<View>(android.R.id.content).width <= 0) return

        val sliderFilter = findViewById<Slider>(R.id.vSliderNoiseFilter)
        val sliderRise = findViewById<Slider>(R.id.vSliderNoiseRise)
        val sliderFall = findViewById<Slider>(R.id.vSliderNoiseFall)
        val txtFilterValue = findViewById<TextView>(R.id.vTxtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.vTxtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.vTxtFallValue)

        if (sliderFilter != null) adjustSliderThickness(sliderFilter, txtFilterValue)
        if (sliderRise != null) adjustSliderThickness(sliderRise, txtRiseValue)
        if (sliderFall != null) adjustSliderThickness(sliderFall, txtFallValue)

        val eqIds = intArrayOf(R.id.vEq100, R.id.vEq300, R.id.vEq1k, R.id.vEq3k, R.id.vEq8k)
        val eqLabels = intArrayOf(R.id.txtEq100Value, R.id.txtEq300Value, R.id.txtEq1kValue, R.id.txtEq3kValue, R.id.txtEq8kValue)
        for (i in eqIds.indices) {
            val slider = findViewById<Slider>(eqIds[i])
            val label = findViewById<TextView>(eqLabels[i])
            if (slider != null && label != null) {
                adjustSliderThickness(slider, label)
            }
        }

        // Headers ("100 Hz", "Rise Time"): one consistent size — portrait keeps the established 11sp;
        // landscape gets a larger cap so labels aren't dwarfed by the wider bars. Value labels are now
        // auto-sized by the shared styleValueBarSlider(), so they're not re-fitted here.
        val headerIds = intArrayOf(R.id.lblEq100, R.id.lblEq300, R.id.lblEq1k, R.id.lblEq3k, R.id.lblEq8k, R.id.lblFilterPercent, R.id.lblFilterRise, R.id.lblFilterFall)
        val buttonIds = intArrayOf(R.id.btnViewerGalleryTop, R.id.btnViewerListenTop, R.id.btnViewerWavelet, R.id.btnViewerNote, R.id.btnViewerPlay)
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val headerCap = (if (isLandscape) 20f else 11f) * uiScale(resources)

        for (id in headerIds) {
            val tv = findViewById<TextView>(id) ?: continue
            if ((tv.visibility == View.VISIBLE) && ((tv.parent as? View)?.width ?: 0 > 0)) {
                tv.setMaxTextSizeToFit(tv.text.toString(), maxSizeSp = headerCap)
            }
        }

        for (id in buttonIds) {
            val tv = findViewById<TextView>(id) ?: continue
            if ((tv.visibility == View.VISIBLE) && ((tv.parent as? View)?.width ?: 0 > 0)) {
                tv.setMaxTextSizeToFit(tv.text.toString(), maxSizeSp = 10f)
            }
        }
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

    // Cap at 12sp so short labels ("COLOR", "Blur:0") don't balloon to fill their full-width row;
    // keeps all Display-tab controls at a consistent size with the top-bar buttons.
    private fun fitSpinner(s: Spinner) {
        (s.selectedView as? TextView)?.let { it.setMaxTextSizeToFit(it.text.toString(), maxSizeSp = 12f) }
    }

    private fun setupControls() {
        sizeSpinner = findViewById(R.id.vSizeSpinner)
        stepSpinner = findViewById(R.id.vStepSpinner)
        btnColor = findViewById(R.id.btnViewerColor)
        btnGrid = findViewById(R.id.btnViewerGrid)
        blurSpinner = findViewById(R.id.vBlurSpinner)
        // enhanceSpinner is redundant (hidden in layout); logic moved to Enhance button.
        btnSweep = findViewById(R.id.btnViewerSweep)

        findViewById<Button>(R.id.btnViewerGalleryTop)?.setOnClickListener { finish() }
        findViewById<Button>(R.id.btnViewerListenTop)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        findViewById<Button>(R.id.btnViewerPlay)?.setOnClickListener { playAudio() }
        findViewById<Button>(R.id.btnViewerProcessed)?.setOnClickListener { playProcessedAudio() }
        findViewById<Button>(R.id.btnViewerCoughAnalysis)?.setOnClickListener { toggleCoughAnalysis() }

        findViewById<Button?>(R.id.btnViewerNote)?.setOnClickListener { showCommentDialog() }

        // btnViewerWavelet is optional in landscape (View placeholder used).
        // Wavelet analysis is too heavy for older devices, so the entry point is hidden below API 26.
        val waveletBtn = findViewById<View?>(R.id.btnViewerWavelet)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            waveletBtn?.visibility = View.GONE
        } else {
            waveletBtn?.setOnClickListener {
                val intent = Intent(this, WaveletActivity::class.java).apply {
                    putExtra("FILE_PATH", filePath)
                }
                startActivity(intent)
            }
        }

        setupFftSpinners()

        // Controls now exist in both orientations (landscape uses the same tabbed sidebar), so the
        // full control set is wired regardless of orientation.
        setupColorSpinner()
        setupGridButton()
        setupNoiseFilter()
        setupBlurSpinner()
        setupEnhanceButton()

        setupEqSliders()
        setupViewerTabs()
        
        // Initial fitting for non-slider controls
        findViewById<View>(android.R.id.content).post {
            fitSpinner(sizeSpinner)
            fitSpinner(stepSpinner)
            fitSpinner(blurSpinner)
        }
    }

    /**
     * Tabbed control panel: one TabLayout switches which control group (EQ / FILTER / DISPLAY) is
     * visible, reusing the existing control views. Null-guarded so the (stub-only) landscape layout
     * that lacks the tabs is unaffected. The selected tab is persisted per recording.
     */
    private fun setupViewerTabs() {
        val tabs = findViewById<com.google.android.material.tabs.TabLayout?>(R.id.viewerTabs) ?: return
        val pages = listOf<View?>(
            findViewById(R.id.pageEq), findViewById(R.id.pageFilter), findViewById(R.id.pageDisplay)
        )
        fun show(index: Int) {
            pages.forEachIndexed { i, p -> p?.visibility = if (i == index) View.VISIBLE else View.GONE }
            // Slider value labels can only be placed once their page has a real size.
            findViewById<View>(android.R.id.content).post { updateAllLabelPositions() }
        }
        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                show(tab.position)
                prefs.edit { putInt("viewer_tab", tab.position) }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
        val initial = prefs.getInt("viewer_tab", 0).coerceIn(0, tabs.tabCount - 1)
        tabs.getTabAt(initial)?.select()
        show(initial)
    }

    private fun setupColorSpinner() {
        val savedColorScheme = ColorMaps.loadForRecording(this, prefs)
        viewerFft.setColorScheme(savedColorScheme)
        styleColorButton(savedColorScheme)
        btnColor.setOnClickListener {
            showColorSchemeDialog(ColorMaps.loadForRecording(this, prefs)) { sel ->
                viewerFft.setColorScheme(sel)
                ColorMaps.saveForRecording(this, prefs, sel)
                styleColorButton(sel)
            }
        }
    }

    /** Style the COLOR button to preview the active scheme (bg = high colour, text = low colour). */
    private fun styleColorButton(idx: Int) {
        btnColor.text = "COLOR"
        btnColor.backgroundTintList = android.content.res.ColorStateList.valueOf(viewerFft.highColorFor(idx))
        btnColor.setTextColor(viewerFft.lowColorFor(idx))
    }

    // Time-grid overlay: bit0 = 1 s thick lines, bit1 = 100 ms thin lines. Persisted per recording.
    private fun setupGridButton() {
        val mask = prefs.getInt("time_grid_mask", 0)
        viewerFft.setTimeGrid(mask)
        updateGridButton(mask)
        btnGrid.setOnClickListener { showGridDialog() }
    }

    private fun updateGridButton(mask: Int) {
        val labels = buildList {
            if (mask and 1 != 0) add("1s")
            if (mask and 2 != 0) add("100ms")
        }
        btnGrid.text = if (labels.isEmpty()) "TIME GRID" else "GRID: " + labels.joinToString("+")
    }

    private fun showGridDialog() {
        val current = prefs.getInt("time_grid_mask", 0)
        val checked = booleanArrayOf(current and 1 != 0, current and 2 != 0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Time grid")
            .setMultiChoiceItems(
                arrayOf("1 s lines (thick)", "100 ms lines (thin)"), checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Apply") { _, _ ->
                var mask = 0
                if (checked[0]) mask = mask or 1
                if (checked[1]) mask = mask or 2
                prefs.edit { putInt("time_grid_mask", mask) }
                viewerFft.setTimeGrid(mask)
                updateGridButton(mask)
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.edit { putInt("time_grid_mask", 0) }
                viewerFft.setTimeGrid(0)
                updateGridButton(0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // --- Enhance control (borrowed from FFTT02M, extended to mixed-mode checkboxes) ---
    // Indices 0..3 are the mutually-exclusive engines that build the base map (shown as radio
    // buttons); indices 4..8 are post-processors (denoise filters + Multitaper) that stack on top
    // of the engine output (shown as checkboxes).

    private fun setupEnhanceButton() {
        // "_v3" key: the mode list was reordered again when Multitaper became a 5th engine, so any
        // older saved mask would map to the wrong modes. Start fresh under a new key.
        val mask = prefs.getInt("enhance_mask_v3", 0)
        for (i in enhanceSelected.indices) enhanceSelected[i] = (mask shr i) and 1 == 1
        // Safety: only one engine may be active. If a stale mask has several, keep the highest-priority.
        var seenEngine = false
        for (i in 0 until engineCount) {
            if (enhanceSelected[i]) {
                if (seenEngine) enhanceSelected[i] = false else seenEngine = true
            }
        }
        updateEnhanceButton()
        btnSweep.setOnClickListener { showEnhanceDialog() }
    }

    private fun showEnhanceDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Engines: one exclusive choice (radio), with an explicit "None" = plain STFT.
        root.addView(enhanceHeader("Engine (pick one)"))
        val engineGroup = RadioGroup(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val noneId = View.generateViewId()
        engineGroup.addView(RadioButton(this).apply { id = noneId; text = "None (plain STFT)" })
        val engineIds = IntArray(engineCount)
        var checkedEngineId = noneId
        for (i in 0 until engineCount) {
            val id = View.generateViewId()
            engineIds[i] = id
            engineGroup.addView(RadioButton(this).apply { this.id = id; text = enhanceModeNames[i] })
            if (enhanceSelected[i]) checkedEngineId = id
        }
        engineGroup.check(checkedEngineId)
        root.addView(engineGroup)

        // Post-processors: independent toggles (checkboxes) that stack.
        root.addView(enhanceHeader("Post-processors (stack)"))
        val postBoxes = ArrayList<Pair<Int, CheckBox>>()
        val heavyBlocked = !DeviceCaps.heavyEnhancementsAllowed(this)
        for (i in engineCount until enhanceModeNames.size) {
            val gatedOff = i in heavyEnhanceModes && heavyBlocked
            val cb = CheckBox(this).apply {
                text = enhanceModeNames[i] + if (gatedOff) "  (needs newer device)" else ""
                isChecked = enhanceSelected[i] && !gatedOff
                isEnabled = !gatedOff
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(cb)
            postBoxes.add(i to cb)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(root)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enhance")
            .setView(scrollView)
            .setPositiveButton("Apply") { _, _ ->
                for (i in 0 until engineCount) enhanceSelected[i] = (engineIds[i] == engineGroup.checkedRadioButtonId)
                for ((i, cb) in postBoxes) enhanceSelected[i] = cb.isChecked
                var m = 0
                for (i in enhanceSelected.indices) if (enhanceSelected[i]) m = m or (1 shl i)
                prefs.edit { putInt("enhance_mask_v3", m) }
                updateEnhanceButton()
                triggerRefresh()
            }
            .setNeutralButton("CLEAR") { _, _ ->
                enhanceSelected.fill(false)
                prefs.edit { putInt("enhance_mask_v3", 0) }
                updateEnhanceButton()
                triggerRefresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enhanceHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#FF69B4"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    /**
     * Edit the recording's comment (a `<name>.txt` sidecar shown in the Gallery). Saving also
     * refreshes the gallery thumbnail with the FFT map exactly as currently displayed.
     */
    private fun showCommentDialog() {
        val path = filePath ?: return
        val rec = File(path)
        val txtFile = File(rec.parentFile, rec.nameWithoutExtension + ".txt")
        val existing = try { if (txtFile.exists()) txtFile.readText() else "" } catch (_: Exception) { "" }

        val input = EditText(this).apply {
            setText(existing)
            setSelection(text.length)
            hint = "Comment for ${rec.name}"
            setSingleLine(false)
            maxLines = 4
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Comment")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                try {
                    if (text.isEmpty()) {
                        if (txtFile.exists()) txtFile.delete()
                    } else {
                        txtFile.writeText(text)
                    }
                    // Replace the gallery icon with the current FFT analysis rendering as displayed.
                    viewerFft.saveSnapshot(rec.nameWithoutExtension + ".png")
                    Toast.makeText(this, "Comment saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateEnhanceButton() {
        val count = enhanceSelected.count { it }
        btnSweep.text = "Enhance:$count"
        btnSweep.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (count == 0) Color.parseColor("#FF69B4") else Color.RED
        )
        // Every engine recomputes its own FFT sizing, so lock the size/step spinners (which only
        // drive the plain STFT path) whenever any engine is selected.
        val engineActive = (0 until engineCount).any { enhanceSelected[it] }
        sizeSpinner.isEnabled = !engineActive
        stepSpinner.isEnabled = !engineActive
        sizeSpinner.alpha = if (engineActive) 0.4f else 1.0f
        stepSpinner.alpha = if (engineActive) 0.4f else 1.0f
        
        // Notify the heat map to hide Sz/St labels when an engine is building the map.
        viewerFft.setEngineActive(engineActive)
    }

    /**
     * Mixed-mode spectrogram enhancement. Applies each selected post-processor (indices 5..8) in
     * sequence on top of whichever engine produced the input map.
     */
    private fun applyEnhancements(input: Array<FloatArray>): Array<FloatArray> {
        var data = input
        if (enhanceSelected.getOrElse(5) { false }) data = enhGaussian(data)
        if (enhanceSelected.getOrElse(6) { false }) data = enhBilateral(data)
        if (enhanceSelected.getOrElse(7) { false }) data = enhTvDenoise(data)
        if (enhanceSelected.getOrElse(8) { false }) data = enhButterworth(data)
        if (enhanceSelected.getOrElse(9) { false }) data = enhAnisotropic(data)
        // Gabor is heavy: only run it where the device tier allows (belt-and-suspenders; the dialog
        // also disables the checkbox on tier 0).
        if (enhanceSelected.getOrElse(10) { false } && DeviceCaps.heavyEnhancementsAllowed(this)) data = enhGabor(data)
        if (enhanceSelected.getOrElse(11) { false } && DeviceCaps.heavyEnhancementsAllowed(this)) data = enhFrangi(data)
        return data
    }

    private fun enhGaussian(history: Array<FloatArray>): Array<FloatArray> {
        val rows = history.size
        if (rows == 0) return history
        val cols = history[0].size
        val result = Array(rows) { FloatArray(cols) }
        val kernel = floatArrayOf(0.0625f, 0.125f, 0.0625f, 0.125f, 0.25f, 0.125f, 0.0625f, 0.125f, 0.0625f)
        for (r in 0 until rows) for (c in 0 until cols) {
            var sum = 0f
            for (i in -1..1) for (j in -1..1) {
                val rr = (r + i).coerceIn(0, rows - 1)
                val cc = (c + j).coerceIn(0, cols - 1)
                sum += history[rr][cc] * kernel[(i + 1) * 3 + (j + 1)]
            }
            result[r][c] = sum
        }
        return result
    }

    private fun enhBilateral(history: Array<FloatArray>): Array<FloatArray> {
        val rows = history.size
        if (rows == 0) return history
        val cols = history[0].size
        val result = Array(rows) { FloatArray(cols) }
        val sigmaD = 2.0f
        val sigmaR = 0.1f
        for (r in 0 until rows) for (c in 0 until cols) {
            var sum = 0f
            var norm = 0f
            val center = history[r][c]
            for (i in -2..2) for (j in -2..2) {
                val rr = (r + i).coerceIn(0, rows - 1)
                val cc = (c + j).coerceIn(0, cols - 1)
                val v = history[rr][cc]
                val distSq = (i * i + j * j).toFloat()
                val rangeSq = (v - center) * (v - center)
                val w = exp(-distSq / (2 * sigmaD * sigmaD) - rangeSq / (2 * sigmaR * sigmaR))
                sum += v * w
                norm += w
            }
            result[r][c] = if (norm > 0f) sum / norm else center
        }
        return result
    }

    private fun enhTvDenoise(history: Array<FloatArray>): Array<FloatArray> {
        val rows = history.size
        if (rows == 0) return history
        val cols = history[0].size
        val result = Array(rows) { history[it].copyOf() }
        val lambda = 0.5f
        repeat(5) {
            for (r in 1 until rows - 1) for (c in 1 until cols - 1) {
                val diffR = result[r + 1][c] - result[r - 1][c]
                val diffC = result[r][c + 1] - result[r][c - 1]
                result[r][c] = result[r][c] + lambda * (diffR + diffC) / 4f
            }
        }
        return result
    }

    private fun enhButterworth(history: Array<FloatArray>): Array<FloatArray> {
        val rows = history.size
        if (rows == 0) return history
        val cols = history[0].size
        val result = Array(rows) { FloatArray(cols) }
        val alpha = 0.3f
        for (r in 0 until rows) for (c in 0 until cols) {
            val prevR = if (r > 0) result[r - 1][c] else history[r][c]
            val prevC = if (c > 0) result[r][c - 1] else history[r][c]
            result[r][c] = history[r][c] * alpha + (prevR + prevC) * (1f - alpha) / 2f
        }
        return result
    }

    /**
     * Anisotropic diffusion (Perona–Malik). Unlike Gaussian/Butterworth (which blur everything),
     * the per-pixel conduction `exp(-(∇/K)²)` suppresses smoothing across strong gradients, so it
     * smooths *along* spectral ridges (the "squiggles") while keeping the edges across them sharp.
     * Self-contained iterative PDE; runs on whatever grid applyEnhancements receives.
     */
    private fun enhAnisotropic(history: Array<FloatArray>): Array<FloatArray> {
        val rows = history.size
        if (rows == 0) return history
        val cols = history[0].size
        if (cols == 0) return history
        val result = Array(rows) { history[it].copyOf() }
        val lambda = 0.20f          // step size; ≤ 0.25 keeps the 4-neighbour scheme stable
        val k2 = 0.08f * 0.08f      // gradient threshold² (magnitudes are ~0..1 normalised)
        repeat(8) {
            val src = Array(rows) { result[it].copyOf() }
            for (r in 0 until rows) {
                val up = if (r > 0) r - 1 else 0
                val dn = if (r < rows - 1) r + 1 else rows - 1
                for (c in 0 until cols) {
                    val center = src[r][c]
                    val gN = src[up][c] - center
                    val gS = src[dn][c] - center
                    val gE = src[r][if (c < cols - 1) c + 1 else cols - 1] - center
                    val gW = src[r][if (c > 0) c - 1 else 0] - center
                    result[r][c] = center + lambda * (
                        exp(-(gN * gN) / k2) * gN +
                        exp(-(gS * gS) / k2) * gS +
                        exp(-(gE * gE) / k2) * gE +
                        exp(-(gW * gW) / k2) * gW
                    )
                }
            }
        }
        return result
    }

    /**
     * Gabor ridge bank. A set of zero-mean, even-symmetric Gabor kernels at several orientations
     * is correlated with the map; the per-pixel MAX response across orientations measures local
     * ridge strength (oriented line energy). That ridge map is added back onto the original, so
     * "squiggly" spectral lines are boosted regardless of their slope while flat regions are left
     * alone. HEAVY (orientations × kernel² per pixel) — gated to mid+ devices via DeviceCaps.
     */
    private fun enhGabor(history: Array<FloatArray>): Array<FloatArray> {
        val rows = history.size
        if (rows == 0) return history
        val cols = history[0].size
        if (cols == 0) return history

        val rad = 4
        val sigma = 1.8f
        val lambda = 4.0f
        val gamma = 0.5f
        val gain = 1.2f
        val orientationsDeg = floatArrayOf(0f, 45f, 90f, 135f)

        // Pre-build zero-mean even-Gabor kernels (so flat regions give ~0 response).
        val kernels = Array(orientationsDeg.size) { oi ->
            val theta = Math.toRadians(orientationsDeg[oi].toDouble())
            val cosT = cos(theta).toFloat()
            val sinT = sin(theta).toFloat()
            val k = Array(2 * rad + 1) { FloatArray(2 * rad + 1) }
            var sum = 0f
            for (y in -rad..rad) for (x in -rad..rad) {
                val xt = x * cosT + y * sinT
                val yt = -x * sinT + y * cosT
                val env = exp(-(xt * xt + gamma * gamma * yt * yt) / (2f * sigma * sigma))
                val v = env * cos(2.0 * PI * xt / lambda).toFloat()
                k[y + rad][x + rad] = v
                sum += v
            }
            val mean = sum / ((2 * rad + 1) * (2 * rad + 1))
            for (yy in k.indices) for (xx in k[yy].indices) k[yy][xx] -= mean
            k
        }

        val result = Array(rows) { history[it].copyOf() }
        for (r in 0 until rows) for (c in 0 until cols) {
            var maxResp = 0f
            for (ker in kernels) {
                var acc = 0f
                for (y in -rad..rad) {
                    val rr = (r + y).coerceIn(0, rows - 1)
                    for (x in -rad..rad) {
                        val cc = (c + x).coerceIn(0, cols - 1)
                        acc += history[rr][cc] * ker[y + rad][x + rad]
                    }
                }
                val a = abs(acc)
                if (a > maxResp) maxResp = a
            }
            result[r][c] = history[r][c] + gain * maxResp
        }
        return result
    }

    /** Separable Gaussian blur at an arbitrary sigma (used by the multi-scale Frangi filter). */
    private fun gaussianBlur(src: Array<FloatArray>, sigma: Float): Array<FloatArray> {
        val rows = src.size
        if (rows == 0) return src
        val cols = src[0].size
        val rad = ceil(3f * sigma).toInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * rad + 1)
        var ksum = 0f
        for (i in -rad..rad) { val v = exp(-(i * i) / (2f * sigma * sigma)); kernel[i + rad] = v; ksum += v }
        for (i in kernel.indices) kernel[i] /= ksum
        val tmp = Array(rows) { FloatArray(cols) }
        for (r in 0 until rows) for (c in 0 until cols) {
            var acc = 0f
            for (k in -rad..rad) acc += src[r][(c + k).coerceIn(0, cols - 1)] * kernel[k + rad]
            tmp[r][c] = acc
        }
        val out = Array(rows) { FloatArray(cols) }
        for (r in 0 until rows) for (c in 0 until cols) {
            var acc = 0f
            for (k in -rad..rad) acc += tmp[(r + k).coerceIn(0, rows - 1)][c] * kernel[k + rad]
            out[r][c] = acc
        }
        return out
    }

    /**
     * Frangi vesselness. At each of several scales the image is Gaussian-smoothed, its 2×2 Hessian
     * is formed per pixel, and the eigenvalues (|λ1| ≤ |λ2|) feed the Frangi measure
     * V = exp(-Rb²/2β²)·(1 - exp(-S²/2c²)) for bright ridges (λ2 < 0). The per-pixel MAX over
     * scales is added back onto the map, highlighting continuous "tubular" spectral ridges while
     * suppressing blobs/noise. HEAVY (multi-scale Hessian + eigen) — gated to mid+ via DeviceCaps.
     */
    private fun enhFrangi(history: Array<FloatArray>): Array<FloatArray> {
        val rows = history.size
        if (rows == 0) return history
        val cols = history[0].size
        if (cols == 0) return history

        val beta2 = 2f * 0.5f * 0.5f          // 2β², β = 0.5 (standard blobness sensitivity)
        val gain = 0.8f
        val scales = floatArrayOf(1.0f, 2.0f, 3.0f)
        val vessel = Array(rows) { FloatArray(cols) }

        for (sigma in scales) {
            val sm = gaussianBlur(history, sigma)
            val s2 = sigma * sigma            // γ-normalisation of the derivatives
            val lam1 = Array(rows) { FloatArray(cols) }
            val lam2 = Array(rows) { FloatArray(cols) }
            var maxS = 1e-6f
            for (r in 0 until rows) {
                val rm = if (r > 0) r - 1 else 0
                val rp = if (r < rows - 1) r + 1 else rows - 1
                for (c in 0 until cols) {
                    val cm = if (c > 0) c - 1 else 0
                    val cp = if (c < cols - 1) c + 1 else cols - 1
                    val hxx = (sm[r][cp] - 2f * sm[r][c] + sm[r][cm]) * s2
                    val hyy = (sm[rp][c] - 2f * sm[r][c] + sm[rm][c]) * s2
                    val hxy = (sm[rp][cp] - sm[rp][cm] - sm[rm][cp] + sm[rm][cm]) * 0.25f * s2
                    val mean = 0.5f * (hxx + hyy)
                    val d = sqrt(0.25f * (hxx - hyy) * (hxx - hyy) + hxy * hxy)
                    var e1 = mean - d
                    var e2 = mean + d
                    if (abs(e1) > abs(e2)) { val t = e1; e1 = e2; e2 = t }  // |e1| <= |e2|
                    lam1[r][c] = e1
                    lam2[r][c] = e2
                    val s = sqrt(e1 * e1 + e2 * e2)
                    if (s > maxS) maxS = s
                }
            }
            val c2 = 2f * (0.5f * maxS) * (0.5f * maxS)   // c = half the max Hessian norm
            for (r in 0 until rows) for (c in 0 until cols) {
                val e1 = lam1[r][c]
                val e2 = lam2[r][c]
                if (e2 < 0f) {                            // bright ridge on darker background
                    val rb = e1 / (e2 - 1e-12f)
                    val s = e1 * e1 + e2 * e2
                    val v = exp(-(rb * rb) / beta2) * (1f - exp(-s / c2))
                    if (v > vessel[r][c]) vessel[r][c] = v
                }
            }
        }

        val out = Array(rows) { history[it].copyOf() }
        for (r in 0 until rows) for (c in 0 until cols) out[r][c] = history[r][c] + gain * vessel[r][c]
        return out
    }

    private fun setupBlurSpinner() {
        val blurValues = (0..10).toList()
        val displayNames = blurValues.map { getString(R.string.label_blur, it) }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_blur_cyan, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        blurSpinner.adapter = adapter
        
        val savedBlur = prefs.getInt("blur_radius", 0).coerceIn(0, 10)
        blurSpinner.setSelection(savedBlur)
        viewerFft.setBlur(savedBlur)

        blurSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                viewerFft.setBlur(pos)
                prefs.edit { putInt("blur_radius", pos) }
                fitSpinner(blurSpinner)
                triggerRefresh()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupFftSpinners() {
        // Size Spinner
        val sizeDisplayNames = fftValues.map { getString(R.string.fft_size_prefix, it) }
        val sizeAdapter = ArrayAdapter(this, R.layout.spinner_item_size_purple, sizeDisplayNames)
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sizeSpinner.adapter = sizeAdapter
        val savedSizeIdx = prefs.getInt("fft_size_idx", 3).coerceIn(0, fftValues.size - 1)
        sizeSpinner.setSelection(savedSizeIdx, false)
        currentFftSize = fftValues[savedSizeIdx]

        // Initialize Step Spinner based on saved Size
        updateStepSpinner(currentFftSize)

        // Listeners
        sizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                val newSize = fftValues[pos]
                if (newSize != currentFftSize) {
                    currentFftSize = newSize
                    prefs.edit { putInt("fft_size_idx", pos) }
                    updateStepSpinner(currentFftSize)
                    fitSpinner(sizeSpinner)
                    triggerRefresh()
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun updateStepSpinner(selectedSize: Int) {
        // Filter options: Step <= Size / 2
        val maxAllowedStep = selectedSize / 2
        val validSteps = fftValues.filter { it <= maxAllowedStep }
        
        // If no values from our standard set are valid (e.g. Size=256), we must allow at least one smaller step.
        // But since fftValues starts at 256, 256/2 = 128 is valid. I'll ensure 128 is handled if needed.
        val finalSteps = if (validSteps.isEmpty()) listOf(selectedSize / 2) else validSteps

        val stepDisplayNames = finalSteps.map { getString(R.string.fft_step_prefix, it) }
        val stepAdapter = ArrayAdapter(this, R.layout.spinner_item_step_blue, stepDisplayNames)
        stepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        // Important: preserve selection if possible, otherwise clamp
        val oldStep = currentStepSize
        stepSpinner.adapter = stepAdapter
        
        val newSelection = finalSteps.indexOf(oldStep).coerceAtLeast(0)
        stepSpinner.setSelection(newSelection, false)
        currentStepSize = finalSteps[newSelection]

        stepSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                val newStep = finalSteps[pos]
                if (newStep != currentStepSize) {
                    currentStepSize = newStep
                    // Save index relative to standard fftValues for simplicity if it exists, otherwise just save value
                    val globalIdx = fftValues.indexOf(newStep)
                    if (globalIdx != -1) prefs.edit { putInt("fft_step_idx", globalIdx) }
                    fitSpinner(stepSpinner)
                    triggerRefresh()
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupEqSliders() {
        val eqIds = intArrayOf(R.id.vEq100, R.id.vEq300, R.id.vEq1k, R.id.vEq3k, R.id.vEq8k)
        val eqLabels = intArrayOf(R.id.txtEq100Value, R.id.txtEq300Value, R.id.txtEq1kValue, R.id.txtEq3kValue, R.id.txtEq8kValue)
        
        for (i in 0 until 5) {
            val slider = findViewById<Slider>(eqIds[i])
            val txtValue = findViewById<TextView>(eqLabels[i])
            val key = "eq_gain_$i"
            val savedGain = prefs.getFloatCoerced(key, 0f)
            slider.setSafeValue(savedGain)
            txtValue.text = getString(R.string.db_value, slider.value.toInt())
            filters[i].gainDb = slider.value
            filters[i].updateCoefficients()

            adjustSliderThickness(slider, txtValue)

            slider.addOnChangeListener { s, value, _ ->
                filters[i].gainDb = value
                filters[i].updateCoefficients()
                txtValue.text = getString(R.string.db_value, value.toInt())
                prefs.edit { putFloat(key, value) }
                updateLabelPosition(s, txtValue)
                triggerRefresh()
            }
        }
    }

    /** Schedule the busy spinner to appear in 100 ms; cancelled by endBusy() if work finishes first. */
    private fun beginBusy() = runOnUiThread {
        if (busyCount == 0) busyHandler.postDelayed(busyShowRunnable, 100)
        busyCount++
    }

    private fun endBusy() = runOnUiThread {
        busyCount = (busyCount - 1).coerceAtLeast(0)
        if (busyCount == 0) {
            busyHandler.removeCallbacks(busyShowRunnable)
            viewerProgress?.visibility = View.GONE
        }
    }

    private fun triggerRefresh() {
        val currentRefresh = synchronized(refreshLock) {
            refreshCount++
            refreshCount
        }

        thread {
            synchronized(refreshLock) {
                if (currentRefresh < refreshCount) return@thread
                activeThread?.interrupt()
                activeThread = Thread.currentThread()
            }

            // Invalidate retained WYSIWYH grids; only a completed standard render repopulates them.
            lastProcGrid = null
            lastOrigGrid = null

            try {
                beginBusy()
                // Engine priority: highest-priority selected engine wins. Each is isolated, so a
                // failure inside one is caught here and leaves the rest of the app untouched.
                when {
                    enhanceSelected.getOrElse(4) { false } -> runMultitaperInternal(currentRefresh)
                    enhanceSelected.getOrElse(3) { false } -> runSynchrosqueezeInternal(currentRefresh)
                    enhanceSelected.getOrElse(2) { false } -> runReassignmentInternal(currentRefresh)
                    enhanceSelected.getOrElse(1) { false } -> runConstantQInternal(currentRefresh)
                    enhanceSelected.getOrElse(0) { false } -> runSweepPlusInternal(currentRefresh)
                    else -> refreshFftInternal(currentRefresh)
                }
            } catch (e: InterruptedException) {
                // Task cancelled
            } catch (e: Throwable) {
                // Throwable (incl. OutOfMemoryError): heavy settings (large FFT / CWT / high level)
                // from a shared recording may exceed this device. Fail gracefully, don't crash.
                e.printStackTrace()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed)
                        Toast.makeText(this, "This view is too heavy for this device", Toast.LENGTH_LONG).show()
                }
            } finally {
                endBusy()
                synchronized(refreshLock) {
                    if (activeThread == Thread.currentThread()) {
                        activeThread = null
                    }
                }
            }
        }
    }

    private fun setupNoiseFilter() {
        val sliderFilter = findViewById<Slider>(R.id.vSliderNoiseFilter)
        val sliderRise = findViewById<Slider>(R.id.vSliderNoiseRise)
        val sliderFall = findViewById<Slider>(R.id.vSliderNoiseFall)
        val txtFilterValue = findViewById<TextView>(R.id.vTxtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.vTxtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.vTxtFallValue)

        noiseFilterStrength = prefs.getFloatCoerced("noise_filter_strength", 0f)
        sliderFilter.setSafeValue(noiseFilterStrength)
        txtFilterValue?.text = getString(R.string.percent_value, (noiseFilterStrength * 100).toInt())
        adjustSliderThickness(sliderFilter, txtFilterValue)
        sliderFilter.addOnChangeListener { slider, value, _ ->
            noiseFilterStrength = value
            txtFilterValue?.text = getString(R.string.percent_value, (value * 100).toInt())
            prefs.edit { putFloat("noise_filter_strength", value) }
            updateLabelPosition(slider, txtFilterValue)
            triggerRefresh()
        }

        // Rise: Milliseconds (1 to 1000) Logarithmic
        sliderRise.valueFrom = 0f
        sliderRise.valueTo = 3f
        sliderRise.stepSize = 0.01f
        val savedRiseMs = prefs.getFloatCoerced("noise_filter_rise_ms", 50f).coerceIn(1f, 1000f)
        sliderRise.setSafeValue(log10(savedRiseMs.toDouble()).toFloat())
        
        adjustSliderThickness(sliderRise, txtRiseValue)
        sliderRise.addOnChangeListener { slider, value, _ ->
            val ms = 10.0.pow(value.toDouble()).toFloat()
            prefs.edit { putFloat("noise_filter_rise_ms", ms) }
            updateCoeffsFromMs()
            updateLabelPosition(slider, txtRiseValue)
            triggerRefresh()
        }

        // Fall: Milliseconds (1 to 1000) Logarithmic
        sliderFall.valueFrom = 0f
        sliderFall.valueTo = 3f
        sliderFall.stepSize = 0.01f
        val savedFallMs = prefs.getFloatCoerced("noise_filter_fall_ms", 200f).coerceIn(1f, 1000f)
        sliderFall.setSafeValue(log10(savedFallMs.toDouble()).toFloat())
        
        adjustSliderThickness(sliderFall, txtFallValue)
        sliderFall.addOnChangeListener { slider, value, _ ->
            val ms = 10.0.pow(value.toDouble()).toFloat()
            prefs.edit { putFloat("noise_filter_fall_ms", ms) }
            updateCoeffsFromMs()
            updateLabelPosition(slider, txtFallValue)
            triggerRefresh()
        }

        updateCoeffsFromMs()
    }

    private fun updateCoeffsFromMs() {
        val sliderRise = findViewById<Slider>(R.id.vSliderNoiseRise)
        val sliderFall = findViewById<Slider>(R.id.vSliderNoiseFall)
        val txtRiseValue = findViewById<TextView>(R.id.vTxtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.vTxtFallValue)

        val frameDurationMs = (currentStepSize.toFloat() / sampleRate) * 1000f
        val k = ln(2.0f) * frameDurationMs

        val riseMs = 10.0.pow(sliderRise.value.toDouble()).toFloat()
        val fallMs = 10.0.pow(sliderFall.value.toDouble()).toFloat()

        noiseRiseCoeff = (k / riseMs).coerceIn(0.001f, 1.0f)
        noiseFallCoeff = (k / fallMs).coerceIn(0.001f, 1.0f)

        txtRiseValue?.text = getString(R.string.ms_value, riseMs.toInt())
        txtFallValue?.text = getString(R.string.ms_value, fallMs.toInt())
    }

    private fun loadAndDecode(file: File) {
        thread {
            beginBusy()
            try {
                // WAV is raw PCM — MediaExtractor/MediaCodec has no decoder for audio/raw, so parse
                // the header directly. FLAC (legacy files) still goes through MediaCodec.
                if (file.extension.equals("wav", ignoreCase = true)) {
                    decodeWav(file)
                } else {
                    decodeViaMediaCodec(file)
                }
                runOnUiThread { triggerRefresh() }
            } catch (e: Throwable) {
                // Throwable (incl. OutOfMemoryError): a shared recording's settings may exceed this
                // device. Fail gracefully instead of crashing.
                e.printStackTrace()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed)
                        Toast.makeText(this, "Couldn't open this recording on this device: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                endBusy()
            }
        }
    }

    /** Parse a raw PCM WAV (as written by MainActivity.encodeWav) into rawPcmData. */
    private fun decodeWav(file: File) {
        val wav = WavReader.read(file)
        sampleRate = wav.sampleRate
        for (f in filters) {
            f.sampleRate = sampleRate.toFloat()
            f.updateCoefficients()
        }
        rawPcmData = wav.samples
    }

    /** Decode compressed audio (e.g. legacy FLAC) via MediaExtractor + MediaCodec. */
    private fun decodeViaMediaCodec(file: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        extractor.selectTrack(0)
        val format = extractor.getTrackFormat(0)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        for (f in filters) {
            f.sampleRate = sampleRate.toFloat()
            f.updateCoefficients()
        }

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var isEOS = false
        val pcmList = mutableListOf<Float>()

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10000)
            if (outIndex >= 0) {
                val outBuffer = codec.getOutputBuffer(outIndex)!!
                while (outBuffer.remaining() >= 2) {
                    pcmList.add(outBuffer.short / 32768f)
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        rawPcmData = pcmList.toFloatArray()
    }

    private fun refreshFftInternal(refreshId: Int) {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) {
            viewerFft.post { triggerRefresh() }
            return
        }
        
        // Count number of columns first
        var columnCount = 0
        var countOffset = 0
        while (countOffset + currentFftSize <= pcm.size) {
            columnCount++
            countOffset += currentStepSize
        }
        if (columnCount <= 0) return

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            viewerFft.setParams(currentFftSize, sampleRate.toFloat(), currentStepSize)
            viewerFft.setMaxHistory(columnCount)
            viewerFft.clearHistory()
            viewerFft.isFrozen = true
        }

        // Accumulation buffer for standard FFT
        val accumulationBuffer = Array(columnCount) { FloatArray(viewHeight) }

        // Filter PCM
        for (f in filters) f.reset()
        val filteredPcm = FloatArray(pcm.size)
        for (i in pcm.indices) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            var s = pcm[i]
            for (f in filters) s = f.process(s)
            filteredPcm[i] = s
        }

        val hannWindow = FloatArray(currentFftSize) { i ->
            (0.5f * (1 - cos(2 * PI * i / (currentFftSize - 1)))).toFloat()
        }

        val minFreq = 80f
        val maxFreq = 10000f
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        
        // Precalculate mapping
        val mapping = IntArray(viewHeight) { y ->
            val logF = logMax - (y.toFloat() / viewHeight) * (logMax - logMin)
            val freq = 10.0.pow(logF.toDouble()).toFloat()
            (freq * currentFftSize / sampleRate).toInt().coerceIn(0, currentFftSize / 2 - 1)
        }

        var globalMax = 1e-9f
        val noiseFloor = FloatArray(currentFftSize / 2)

        var offset = 0
        var c = 0
        while (offset + currentFftSize <= filteredPcm.size) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val real = FloatArray(currentFftSize)
            val imag = FloatArray(currentFftSize)
            for (i in 0 until currentFftSize) {
                real[i] = filteredPcm[offset + i] * hannWindow[i]
            }
            
            FFTUtils.compute(real, imag)
            
            val mags = FloatArray(currentFftSize / 2)
            for (i in 0 until currentFftSize / 2) {
                var mag = sqrt(real[i] * real[i] + imag[i] * imag[i])

                if (noiseFilterStrength > 0f) {
                    if (noiseFloor[i] == 0f) noiseFloor[i] = mag
                    else if (mag < noiseFloor[i]) noiseFloor[i] = noiseFloor[i] * (1f - noiseFallCoeff) + mag * noiseFallCoeff
                    else noiseFloor[i] = noiseFloor[i] * (1f - noiseRiseCoeff) + mag * noiseRiseCoeff
                    mag = (mag - noiseFloor[i] * noiseFilterStrength).coerceAtLeast(0f)
                }
                mags[i] = mag
            }

            for (y in 0 until viewHeight) {
                val mag = mags[mapping[y]]
                accumulationBuffer[c][y] = mag
                if (mag > globalMax) globalMax = mag
            }

            offset += currentStepSize
            c++
        }

        val processed = applyEnhancements(accumulationBuffer)
        // Retain for WYSIWYH processed playback (tier-gated inside; skipped on low-RAM devices).
        retainWysiwyhGrids(accumulationBuffer, processed, currentFftSize, currentStepSize, viewHeight)
        var enhMax = 1e-9f
        for (col in processed) for (v in col) if (v > enhMax) enhMax = v
        val maxDB = 20 * log10(enhMax + 1e-9f)
        for (idx in 0 until columnCount) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val normalized = FloatArray(viewHeight)
            for (y in 0 until viewHeight) {
                val dB = 20 * log10(processed[idx][y] + 1e-9f)
                normalized[y] = ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    viewerFft.updateFFT(normalized, force = true)
                }
            }
        }
    }

    /**
     * Sweep+ : de-striped multi-resolution map. Each window size is computed on the common base
     * time grid, normalized to its own peak, then merged across sizes by per-pixel max - so no
     * single resolution's additive overlap creates banding. Isolated; a failure here is contained.
     */
    private fun runSweepPlusInternal(refreshId: Int) {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) {
            viewerFft.post { triggerRefresh() }
            return
        }
        val sizes = intArrayOf(512, 1024, 2048, 4096)
        val baseStep = 256
        val baseSize = 512
        var baseHistory = 0
        var t = 0
        while (t + baseSize <= pcm.size) { baseHistory++; t += baseStep }
        if (baseHistory <= 0) return

        for (f in filters) f.reset()
        val filteredPcm = FloatArray(pcm.size)
        for (i in pcm.indices) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            var s = pcm[i]
            for (fl in filters) s = fl.process(s)
            filteredPcm[i] = s
        }

        val minFreq = 80f
        val maxFreq = 10000f
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)

        val combined = Array(baseHistory) { FloatArray(viewHeight) }

        for (size in sizes) {
            if (size > filteredPcm.size) continue
            val hann = FloatArray(size) { i -> (0.5f * (1 - cos(2 * PI * i / (size - 1)))).toFloat() }
            val mapping = IntArray(viewHeight) { y ->
                val logF = logMax - (y.toFloat() / viewHeight) * (logMax - logMin)
                val freq = 10.0.pow(logF.toDouble()).toFloat()
                (freq * size / sampleRate).toInt().coerceIn(0, size / 2 - 1)
            }
            val sizeMap = Array(baseHistory) { FloatArray(viewHeight) }
            var localMax = 1e-9f
            for (c in 0 until baseHistory) {
                if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
                val start = c * baseStep
                if (start + size > filteredPcm.size) break
                val real = FloatArray(size)
                val imag = FloatArray(size)
                for (i in 0 until size) real[i] = filteredPcm[start + i] * hann[i]
                FFTUtils.compute(real, imag)
                for (y in 0 until viewHeight) {
                    val bin = mapping[y]
                    val mag = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                    sizeMap[c][y] = mag
                    if (mag > localMax) localMax = mag
                }
            }
            val inv = 1f / localMax
            for (c in 0 until baseHistory) for (y in 0 until viewHeight) {
                val v = sizeMap[c][y] * inv
                if (v > combined[c][y]) combined[c][y] = v
            }
        }

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            viewerFft.setParams(baseSize, sampleRate.toFloat(), baseStep)
            viewerFft.setMaxHistory(baseHistory)
            viewerFft.clearHistory()
            viewerFft.isFrozen = true
        }

        val processed = applyEnhancements(combined)
        // WYSIWYH: enhanced (Sweep+ multi-resolution + post-processors) vs a plain 512/256 baseline.
        retainWysiwyhGrids(plainBaselineGrid(filteredPcm, baseSize, baseStep, baseHistory, viewHeight),
            processed, baseSize, baseStep, viewHeight)
        var enhMax = 1e-9f
        for (col in processed) for (v in col) if (v > enhMax) enhMax = v
        val maxDB = 20 * log10(enhMax + 1e-9f)
        for (c in 0 until baseHistory) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val normalized = FloatArray(viewHeight)
            for (y in 0 until viewHeight) {
                val dB = 20 * log10(processed[c][y] + 1e-9f)
                normalized[y] = ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) viewerFft.updateFFT(normalized, force = true)
            }
        }
    }

    // ---- Shared engine helpers (Constant-Q / Reassignment / Synchrosqueeze) ----

    private val engineMinFreq = 80f
    private val engineMaxFreq = 10000f

    /** Apply the EQ biquads to a fresh copy of the PCM, exactly as the other engines do. */
    private fun filterPcm(pcm: FloatArray, refreshId: Int): FloatArray {
        for (f in filters) f.reset()
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            var s = pcm[i]
            for (f in filters) s = f.process(s)
            out[i] = s
        }
        return out
    }

    /** Map a frequency (Hz) onto the log-frequency view axis; -1 if it falls outside the view. */
    private fun freqToRow(freq: Float, viewHeight: Int): Int {
        if (freq <= 0f) return -1
        val logMin = log10(engineMinFreq)
        val logMax = log10(engineMaxFreq)
        val y = (((logMax - log10(freq)) / (logMax - logMin)) * viewHeight).roundToInt()
        return if (y in 0 until viewHeight) y else -1
    }

    /**
     * Shared engine tail: stream a [baseHistory][viewHeight] magnitude map to the heat-map view.
     * Stacks the selected post-processors, converts to dB across an 80 dB window, and pushes
     * columns - the same finishing path the Sweep+/STFT engines use, factored out for reuse.
     */
    /** Plain single-resolution STFT magnitude grid in DISPLAY coords (80–10000 Hz log over [viewHeight]
     *  rows; [columns] columns spaced [step] apart, [size] window). Serves as the un-enhanced baseline
     *  so processed playback can recover the pure enhancement gain (enhanced ÷ plain) for ANY engine. */
    private fun plainBaselineGrid(filtered: FloatArray, size: Int, step: Int, columns: Int, viewHeight: Int): Array<FloatArray> {
        val logMin = log10(engineMinFreq); val logMax = log10(engineMaxFreq)
        val mapping = IntArray(viewHeight) { y ->
            val logF = logMax - (y.toFloat() / viewHeight) * (logMax - logMin)
            val freq = 10.0.pow(logF.toDouble()).toFloat()
            (freq * size / sampleRate).toInt().coerceIn(0, size / 2 - 1)
        }
        val hann = FloatArray(size) { (0.5f * (1 - cos(2 * PI * it / (size - 1)))).toFloat() }
        val grid = Array(columns) { FloatArray(viewHeight) }
        for (c in 0 until columns) {
            val start = c * step
            if (start + size > filtered.size) break
            val re = FloatArray(size); val im = FloatArray(size)
            for (i in 0 until size) re[i] = filtered[start + i] * hann[i]
            FFTUtils.compute(re, im)
            for (y in 0 until viewHeight) {
                val bin = mapping[y]
                grid[c][y] = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            }
        }
        return grid
    }

    /** Retain the (baseline, enhanced) display grids for WYSIWYH processed playback. The baseline is
     *  rescaled to the enhanced grid's overall level so the playback gain (proc÷orig) is a ~unit-mean
     *  RELATIVE reshaping — an engine transform can sit on a very different magnitude scale, which
     *  would otherwise saturate the gain clamp uniformly and leave the enhancement inaudible. Tier-
     *  gated so low-RAM devices skip the extra grids (playback then just does EQ + noise filter). */
    private fun retainWysiwyhGrids(orig: Array<FloatArray>, proc: Array<FloatArray>, fft: Int, step: Int, vh: Int) {
        if (DeviceCaps.tier(this) < 1) { lastOrigGrid = null; lastProcGrid = null; return }
        var so = 0.0; var sp = 0.0
        for (col in orig) for (v in col) so += v
        for (col in proc) for (v in col) sp += v
        val k = if (so > 1e-9) (sp / so).toFloat() else 1f
        lastOrigGrid = if (kotlin.math.abs(k - 1f) < 1e-3f) orig
            else Array(orig.size) { c -> FloatArray(orig[c].size) { i -> orig[c][i] * k } }
        lastProcGrid = proc
        lastGridFftSize = fft
        lastGridStep = step
        lastGridViewHeight = vh
    }

    private fun renderEngineMap(refreshId: Int, baseSize: Int, baseStep: Int, map: Array<FloatArray>, filtered: FloatArray) {
        val baseHistory = map.size
        if (baseHistory == 0) return
        val viewHeight = map[0].size
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            viewerFft.setParams(baseSize, sampleRate.toFloat(), baseStep)
            viewerFft.setMaxHistory(baseHistory)
            viewerFft.clearHistory()
            viewerFft.isFrozen = true
        }
        val processed = applyEnhancements(map)
        // WYSIWYH: enhanced (engine + post-processors) vs a plain 512/256 STFT baseline.
        retainWysiwyhGrids(plainBaselineGrid(filtered, baseSize, baseStep, baseHistory, viewHeight),
            processed, baseSize, baseStep, viewHeight)
        var enhMax = 1e-9f
        for (col in processed) for (v in col) if (v > enhMax) enhMax = v
        val maxDB = 20 * log10(enhMax + 1e-9f)
        for (c in 0 until baseHistory) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val normalized = FloatArray(viewHeight)
            for (y in 0 until viewHeight) {
                val dB = 20 * log10(processed[c][y] + 1e-9f)
                normalized[y] = ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) viewerFft.updateFFT(normalized, force = true)
            }
        }
    }

    /**
     * Constant-Q / Morlet engine. For each row of the log-frequency axis the signal is convolved
     * with a complex Morlet wavelet whose Gaussian support scales as 1/f (fixed cycle count => fixed
     * Q), sampled on the common base time grid. Because the analysis frequencies are themselves
     * log-spaced, the result lands directly on the view's axis with no additive banding. Isolated.
     */
    private fun runConstantQInternal(refreshId: Int) {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) { viewerFft.post { triggerRefresh() }; return }

        val baseStep = 256
        val baseSize = 512
        var baseHistory = 0
        var t = 0
        while (t + baseSize <= pcm.size) { baseHistory++; t += baseStep }
        if (baseHistory <= 0) return

        val filtered = filterPcm(pcm, refreshId)
        val sr = sampleRate.toFloat()
        val logMin = log10(engineMinFreq)
        val logMax = log10(engineMaxFreq)

        val nCycles = 7f          // wavelet cycle count -> Q
        val maxHalf = 4096        // cap the low-frequency wavelet half-length so it stays tractable
        val combined = Array(baseHistory) { FloatArray(viewHeight) }

        for (y in 0 until viewHeight) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val logF = logMax - (y.toFloat() / viewHeight) * (logMax - logMin)
            val freq = 10.0.pow(logF.toDouble()).toFloat()
            val sigma = (nCycles / (2f * PI.toFloat() * freq)) * sr   // Gaussian std, in samples
            val half = min((3.5f * sigma).toInt(), maxHalf)
            if (half < 1) continue
            val len = 2 * half + 1
            val cosK = FloatArray(len)
            val sinK = FloatArray(len)
            val w = 2.0 * PI * freq / sr
            val inv2s2 = 1f / (2f * sigma * sigma)
            var enorm = 0f
            for (k in -half..half) {
                val idx = k + half
                val e = exp(-(k.toFloat() * k) * inv2s2)
                enorm += e
                cosK[idx] = (cos(w * k)).toFloat() * e
                sinK[idx] = (sin(w * k)).toFloat() * e
            }
            val inv = if (enorm > 0f) 1f / enorm else 1f   // normalize so scales are comparable
            for (c in 0 until baseHistory) {
                val center = c * baseStep + baseSize / 2
                var re = 0f
                var im = 0f
                var k = -half
                while (k <= half) {
                    val s = center + k
                    if (s in filtered.indices) {
                        val x = filtered[s]
                        val idx = k + half
                        re += x * cosK[idx]
                        im += x * sinK[idx]
                    }
                    k++
                }
                combined[c][y] = sqrt(re * re + im * im) * inv
            }
        }

        renderEngineMap(refreshId, baseSize, baseStep, combined, filtered)
    }

    /**
     * Reassignment engine (Auger-Flandrin). A Hann STFT is sharpened by relocating each bin's
     * energy to the local centre of gravity in *both* time and frequency, estimated from the
     * time-ramped window (group delay) and the derivative window (instantaneous frequency). Energy
     * is splatted onto the base grid. Isolated; a failure here is caught by the dispatch.
     */
    private fun runReassignmentInternal(refreshId: Int) {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) { viewerFft.post { triggerRefresh() }; return }

        val size = 2048
        val baseStep = 256
        val baseSize = 512
        if (size > pcm.size) return
        var baseHistory = 0
        var t = 0
        while (t + baseSize <= pcm.size) { baseHistory++; t += baseStep }
        if (baseHistory <= 0) return

        val filtered = filterPcm(pcm, refreshId)
        val sr = sampleRate.toFloat()

        // Hann (h), time-ramped (Th, centred index) and derivative (Dh) windows.
        val h = FloatArray(size)
        val th = FloatArray(size)
        val dh = FloatArray(size)
        val step = 2.0 * PI / (size - 1)
        val mid = (size - 1) / 2.0
        for (n in 0 until size) {
            val hn = (0.5 * (1 - cos(step * n))).toFloat()
            h[n] = hn
            th[n] = ((n - mid).toFloat()) * hn
            dh[n] = (0.5 * step * sin(step * n)).toFloat()
        }

        val combined = Array(baseHistory) { FloatArray(viewHeight) }
        val halfBins = size / 2
        val binToBin = (size / (2.0 * PI)).toFloat()

        var offset = 0
        var col = 0
        while (offset + size <= filtered.size && col < baseHistory) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val rH = FloatArray(size); val iH = FloatArray(size)
            val rT = FloatArray(size); val iT = FloatArray(size)
            val rD = FloatArray(size); val iD = FloatArray(size)
            for (n in 0 until size) {
                val x = filtered[offset + n]
                rH[n] = x * h[n]
                rT[n] = x * th[n]
                rD[n] = x * dh[n]
            }
            FFTUtils.compute(rH, iH)
            FFTUtils.compute(rT, iT)
            FFTUtils.compute(rD, iD)

            val frameCenter = offset + mid
            for (k in 0 until halfBins) {
                val hr = rH[k]; val hi = iH[k]
                val power = hr * hr + hi * hi
                if (power < 1e-12f) continue
                // Time offset (samples): -Re(X_Th * conj(X_h)) / |X_h|²
                val dt = -((rT[k] * hr + iT[k] * hi) / power)
                // Freq offset (bins): -Im(X_Dh * conj(X_h)) / |X_h|² * N/(2π)
                val dBin = -((iD[k] * hr - rD[k] * hi) / power) * binToBin

                val reBin = k + dBin
                if (reBin < 0f || reBin > halfBins) continue
                val row = freqToRow(reBin * sr / size, viewHeight)
                if (row < 0) continue
                val rc = ((frameCenter + dt) / baseStep).roundToInt()
                if (rc < 0 || rc >= baseHistory) continue
                combined[rc][row] += power
            }
            offset += baseStep
            col++
        }

        // Power -> magnitude for the shared dB tail.
        for (c in 0 until baseHistory) for (y in 0 until viewHeight) combined[c][y] = sqrt(combined[c][y])
        renderEngineMap(refreshId, baseSize, baseStep, combined, filtered)
    }

    /**
     * Synchrosqueeze engine. Like reassignment, but energy is moved only along frequency (the time
     * column is preserved), using the instantaneous-frequency estimate from the derivative window.
     * This yields a sharp ridge map that stays faithful in time. Isolated.
     */
    private fun runSynchrosqueezeInternal(refreshId: Int) {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) { viewerFft.post { triggerRefresh() }; return }

        val size = 2048
        val baseStep = 256
        val baseSize = 512
        if (size > pcm.size) return
        var baseHistory = 0
        var t = 0
        while (t + baseSize <= pcm.size) { baseHistory++; t += baseStep }
        if (baseHistory <= 0) return

        val filtered = filterPcm(pcm, refreshId)
        val sr = sampleRate.toFloat()

        val h = FloatArray(size)
        val dh = FloatArray(size)
        val step = 2.0 * PI / (size - 1)
        for (n in 0 until size) {
            h[n] = (0.5 * (1 - cos(step * n))).toFloat()
            dh[n] = (0.5 * step * sin(step * n)).toFloat()
        }

        val combined = Array(baseHistory) { FloatArray(viewHeight) }
        val halfBins = size / 2
        val binToBin = (size / (2.0 * PI)).toFloat()

        var offset = 0
        var col = 0
        while (offset + size <= filtered.size && col < baseHistory) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val rH = FloatArray(size); val iH = FloatArray(size)
            val rD = FloatArray(size); val iD = FloatArray(size)
            for (n in 0 until size) {
                val x = filtered[offset + n]
                rH[n] = x * h[n]
                rD[n] = x * dh[n]
            }
            FFTUtils.compute(rH, iH)
            FFTUtils.compute(rD, iD)

            for (k in 0 until halfBins) {
                val hr = rH[k]; val hi = iH[k]
                val power = hr * hr + hi * hi
                if (power < 1e-12f) continue
                val reBin = k - ((iD[k] * hr - rD[k] * hi) / power) * binToBin
                if (reBin < 0f || reBin > halfBins) continue
                val row = freqToRow(reBin * sr / size, viewHeight)
                if (row < 0) continue
                combined[col][row] += sqrt(power)
            }
            offset += baseStep
            col++
        }

        renderEngineMap(refreshId, baseSize, baseStep, combined, filtered)
    }

    /**
     * Multitaper engine (Thomson). For each base column it computes K orthogonal sine-tapered
     * periodograms of the same segment and averages their power, cutting spectral-estimate variance
     * (a smoother, less speckly map) while keeping the true frequency resolution of the window.
     * Sine tapers (Riedel-Sidorenko) approximate the DPSS/Slepian set but are cheap to generate.
     * Operates on the raw PCM, isolated like the other engines.
     */
    private fun runMultitaperInternal(refreshId: Int) {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) { viewerFft.post { triggerRefresh() }; return }

        val size = 1024
        val baseStep = 256
        val baseSize = 512
        if (size > pcm.size) return
        var baseHistory = 0
        var t = 0
        while (t + baseSize <= pcm.size) { baseHistory++; t += baseStep }
        if (baseHistory <= 0) return

        val filtered = filterPcm(pcm, refreshId)
        val logMin = log10(engineMinFreq)
        val logMax = log10(engineMaxFreq)
        val half = size / 2
        val mapping = IntArray(viewHeight) { y ->
            val logF = logMax - (y.toFloat() / viewHeight) * (logMax - logMin)
            val freq = 10.0.pow(logF.toDouble()).toFloat()
            (freq * size / sampleRate).toInt().coerceIn(0, half - 1)
        }

        // K orthogonal sine tapers.
        val k = 5
        val tapers = Array(k) { j ->
            FloatArray(size) { n -> (sqrt(2.0 / (size + 1)) * sin(PI * (j + 1) * (n + 1) / (size + 1))).toFloat() }
        }

        val combined = Array(baseHistory) { FloatArray(viewHeight) }
        val invK = 1f / k
        for (c in 0 until baseHistory) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val start = c * baseStep
            if (start + size > filtered.size) break
            val avgPow = FloatArray(half)
            for (taper in tapers) {
                val re = FloatArray(size)
                val im = FloatArray(size)
                for (n in 0 until size) re[n] = filtered[start + n] * taper[n]
                FFTUtils.compute(re, im)
                for (i in 0 until half) avgPow[i] += re[i] * re[i] + im[i] * im[i]
            }
            for (y in 0 until viewHeight) combined[c][y] = sqrt(avgPow[mapping[y]] * invK)
        }

        renderEngineMap(refreshId, baseSize, baseStep, combined, filtered)
    }

    private fun playAudio() {
        val pcm = rawPcmData ?: return

        // Stop any current playback
        stopAudio()

        thread {
            for (f in filters) f.reset()
            val filteredPcm = FloatArray(pcm.size)
            for (i in pcm.indices) {
                var s = pcm[i]
                for (f in filters) s = f.process(s)
                filteredPcm[i] = s
            }

            if (noiseFilterStrength > 0f) {
                val noiseFloor = FloatArray(currentFftSize / 2)
                val hannWindow = FloatArray(currentFftSize) { i ->
                    (0.5f * (1 - cos(2 * PI * i / (currentFftSize - 1)))).toFloat()
                }

                // Process in blocks to apply spectral subtraction
                var offset = 0
                while (offset + currentFftSize <= filteredPcm.size) {
                    val real = FloatArray(currentFftSize)
                    val imag = FloatArray(currentFftSize)
                    for (i in 0 until currentFftSize) {
                        real[i] = filteredPcm[offset + i] * hannWindow[i]
                    }

                    FFTUtils.compute(real, imag)

                    for (i in 0 until currentFftSize / 2) {
                        var mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
                        val phase = atan2(imag[i], real[i])

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

                        real[i] = mag * cos(phase)
                        imag[i] = mag * sin(phase)
                        // Mirror for IFFT
                        if (i > 0) {
                            real[currentFftSize - i] = real[i]
                            imag[currentFftSize - i] = -imag[i]
                        }
                    }
                    real[currentFftSize / 2] = 0f 
                    imag[currentFftSize / 2] = 0f

                    FFTUtils.inverse(real, imag)

                    for (i in 0 until currentFftSize) {
                        if (offset + i < filteredPcm.size) {
                             filteredPcm[offset + i] = real[i]
                        }
                    }
                    offset += currentStepSize
                }
            }

            // Play the raw, unmodified recording. The EQ biquads and the noise-filter spectral
            // subtraction are visualization aids; applying them to playback distorts the audio
            // (EQ boosts clip past full-scale, and the spectral-subtraction reconstruction
            // overwrites overlapping windowed blocks with no overlap-add/COLA normalization).
            // So playback ignores the Filter settings, matching FFTT02M's raw playback.
            // Peak-normalize so quiet clips are audible on playback (loud clips unchanged — no clipping;
            // gain capped at 40x so near-silent clips aren't blown up).
            var peak = 0f; for (x in pcm) { val a = kotlin.math.abs(x); if (a > peak) peak = a }
            val gain = if (peak > 1e-4f) (0.95f / peak).coerceAtMost(40f) else 1f
            val audioData = ShortArray(pcm.size)
            for (i in pcm.indices) {
                audioData[i] = ((pcm[i] * gain).coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }

            startPlayback(audioData)
        }
    }

    /** Build the AudioTrack, play [audioData], and drive the play cursor. Call on a background
     *  thread (blocking write + cursor poll). */
    private fun startPlayback(audioData: ShortArray) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize.coerceAtLeast(audioData.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        // Mute the always-on capture so it doesn't re-record our own playback as new clips.
        CoughCaptureService.playbackActive = true
        audioTrack?.apply {
            write(audioData, 0, audioData.size, AudioTrack.WRITE_BLOCKING)
            play()
        }
        val track = audioTrack
        if (track != null) {
            val totalFrames = audioData.size.coerceAtLeast(1)
            try {
                while (audioTrack === track) {
                    val pos = track.playbackHeadPosition
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            viewerFft.setPlayCursor((pos.toFloat() / totalFrames).coerceIn(0f, 1f))
                        }
                    }
                    if (pos >= totalFrames || track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                    Thread.sleep(30)
                }
            } catch (_: Throwable) {}
            runOnUiThread {
                if (!isFinishing && !isDestroyed) viewerFft.clearPlayCursor()
            }
        }
        CoughCaptureService.playbackActive = false   // resume capture after playback
    }

    /**
     * PROCESSED PLAYBACK — hear EQ + noise filter + ENHANCE applied to the recording (vs. the raw
     * PLAY button). EQ is the time-domain biquads; the noise filter is spectral subtraction with Hann
     * analysis+synthesis windows and COLA overlap-add. ENHANCE is WYSIWYH: the displayed enhancement
     * (engine transform and/or post-processors) is turned into a per-frequency gain mask = enhanced ÷
     * plain-baseline display grid, applied to the STFT magnitudes (original phase retained). Engine
     * transforms aren't truly invertible, so this is a spectral-ENVELOPE approximation — "sounds like
     * it looks" — clamped to [0,8]× per bin. Output is peak-normalized so boosts don't clip.
     * EXPERIMENTAL — the clamp range and baseline scaling are the knobs to tune by ear.
     */
    private fun playProcessedAudio() {
        val pcm = rawPcmData ?: return
        stopAudio()
        // Snapshot the retained grids (WYSIWYH enhance) so a concurrent re-render can't swap them.
        val procGrid = lastProcGrid
        val origGrid = lastOrigGrid
        val gridFft = lastGridFftSize
        val gridStep = lastGridStep
        val gridVH = lastGridViewHeight
        thread {
            // 1) EQ (time-domain biquads).
            for (f in filters) f.reset()
            val out = FloatArray(pcm.size)
            for (i in pcm.indices) {
                var s = pcm[i]
                for (f in filters) s = f.process(s)
                out[i] = s
            }

            // WYSIWYH enhance: the retained grids carry the displayed enhancement (engine transform
            // and/or post-processors) vs a plain baseline, in DISPLAY coords. Drive this pass from the
            // grid's OWN fft size/step (engines use 512/256; the standard render uses the live params)
            // so columns and the bin→row mapping line up. proc÷orig is the pure ENHANCE gain — EQ and
            // the noise filter cancel in the ratio and are (re)applied explicitly in step 1 and below.
            val applyEnhance = procGrid != null && origGrid != null && gridFft >= 2 && gridVH > 0
            val n = if (applyEnhance) gridFft else currentFftSize
            val applyNoise = noiseFilterStrength > 0f

            // 2) Single WOLA STFT pass: noise subtraction + enhance gain, original phase retained.
            if ((applyNoise || applyEnhance) && n >= 2) {
                val step = (if (applyEnhance) gridStep else currentStepSize).coerceAtLeast(1)
                val win = FloatArray(n) { (0.5f * (1 - cos(2 * PI * it / (n - 1)))).toFloat() }
                val acc = FloatArray(out.size)
                val wsum = FloatArray(out.size)            // Σ synthesis-window² for COLA normalization
                val noiseFloor = FloatArray(n / 2)
                // Bin → display row (exact inverse of the renderer's 80–10000 Hz log mapping).
                val logMin = log10(80f); val logMax = log10(10000f)
                val invRow = if (applyEnhance) IntArray(n / 2) { i ->
                    val freq = i.toFloat() * sampleRate / n
                    if (freq <= 0f) gridVH - 1
                    else ((logMax - log10(freq)) / (logMax - logMin) * gridVH).toInt().coerceIn(0, gridVH - 1)
                } else IntArray(0)

                var offset = 0
                var c = 0
                while (offset + n <= out.size) {
                    val re = FloatArray(n); val im = FloatArray(n)
                    for (i in 0 until n) re[i] = out[offset + i] * win[i]
                    FFTUtils.compute(re, im)
                    val haveCol = applyEnhance && c < procGrid!!.size
                    for (i in 0 until n / 2) {
                        var mag = sqrt(re[i] * re[i] + im[i] * im[i])
                        val ph = atan2(im[i], re[i])
                        if (applyNoise) {
                            noiseFloor[i] = when {
                                noiseFloor[i] == 0f -> mag
                                mag < noiseFloor[i] -> noiseFloor[i] * (1f - noiseFallCoeff) + mag * noiseFallCoeff
                                else -> noiseFloor[i] * (1f - noiseRiseCoeff) + mag * noiseRiseCoeff
                            }
                            mag = (mag - noiseFloor[i] * noiseFilterStrength).coerceAtLeast(0f)
                        }
                        if (haveCol) {
                            val y = invRow[i]
                            val o = origGrid!![c][y]
                            val gain = if (o > 1e-9f) procGrid[c][y] / o else 1f
                            mag *= gain.coerceIn(0f, 8f)    // clamp so enhancement can't blow up
                        }
                        re[i] = mag * cos(ph); im[i] = mag * sin(ph)
                        if (i > 0) { re[n - i] = re[i]; im[n - i] = -im[i] }   // Hermitian mirror
                    }
                    re[n / 2] = 0f; im[n / 2] = 0f
                    FFTUtils.inverse(re, im)
                    for (i in 0 until n) {
                        val idx = offset + i
                        if (idx < acc.size) { acc[idx] += re[i] * win[i]; wsum[idx] += win[i] * win[i] }
                    }
                    offset += step
                    c++
                }
                for (i in out.indices) if (wsum[i] > 1e-6f) out[i] = acc[i] / wsum[i]
            }

            // 3) Peak-normalize to avoid clipping.
            var peak = 1e-6f
            for (v in out) { val a = abs(v); if (a > peak) peak = a }
            val gain = if (peak > 1f) 1f / peak else 1f
            val audioData = ShortArray(out.size) {
                ((out[it] * gain).coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
            startPlayback(audioData)
        }
    }

    private fun stopAudio() {
        CoughCaptureService.playbackActive = false   // ensure capture resumes if playback is cut short
        audioTrack?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {}
        }
        audioTrack = null

        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun toggleCoughAnalysis() {
        filePath?.let { path ->
            val intent = Intent(this, com.example.FFTT04M.cough.CoughAnalysisActivity::class.java)
            intent.putExtra("FILE_PATH", path)
            startActivity(intent)
        } ?: run {
            Toast.makeText(this, "No recording loaded", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        // Mirror this recording's current settings to its <base>.json sidecar so they survive uninstall.
        filePath?.let { GalleryTransfer.writeMetaSidecar(this, File(it).nameWithoutExtension) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
    }

    private fun barColorForSlider(slider: Slider): Int =
        if (slider.id == R.id.vSliderNoiseFilter || slider.id == R.id.vSliderNoiseRise || slider.id == R.id.vSliderNoiseFall)
            Color.CYAN else Color.GREEN

    // Unified value-bar rendering (see ViewUtils.styleValueBarSlider) — shared with Listen & Wavelet.
    private fun adjustSliderThickness(slider: Slider, label: TextView?) =
        styleValueBarSlider(slider, label, barColorForSlider(slider))

    private fun updateLabelPosition(slider: Slider, label: TextView?) =
        updateValueBarLabel(slider, label, barColorForSlider(slider))
}
