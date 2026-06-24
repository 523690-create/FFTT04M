package com.example.FFTT04M

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.Socket
import kotlin.concurrent.thread
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.FFTT04M.cough.PhonemeDecoder
import java.io.File

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewToggle: ImageButton
    private var isGridView = false
    private var files = mutableListOf<File>()

    private val galleryPrefs by lazy { getSharedPreferences("app_settings", MODE_PRIVATE) }
    // #4b gallery memory: the recording the user last opened (highlighted on return), the newest
    // recording we've seen (to detect a fresh acquisition), and a one-shot "flash this item" name.
    private var lastSelectedName: String? = null
    private var lastAcquiredName: String? = null
    private var flashName: String? = null
    private val DEFAULT_ITEM_BG = 0xFF111111.toInt()   // matches gallery_item.xml root background
    private val HIGHLIGHT_BG = 0xFF7A5200.toInt()       // amber tint for the last-opened recording

    // Scanner result (receiver side): a Wi-Fi (FFTT1) or Bluetooth (FFTTBT) handshake.
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScanned(it) }
    }

    // Runtime BLUETOOTH_CONNECT request (API 31+); runs the stashed action once granted.
    private var pendingBtAction: (() -> Unit)? = null
    private val btPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val a = pendingBtAction; pendingBtAction = null
        if (granted) a?.invoke() else Toast.makeText(this, "Bluetooth permission is needed", Toast.LENGTH_LONG).show()
    }

    private fun ensureBtPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingBtAction = action
            btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else action()
    }

    private fun handleScanned(s: String) {
        android.util.Log.i("FFTTxfer", "scanned prefix=${s.substringBefore(':')}")
        when {
            s.startsWith("${GalleryTransfer.HANDSHAKE_PREFIX}:") -> receiveBundleFrom(s)           // Wi-Fi
            s.startsWith("${GalleryTransfer.BT_PREFIX}:") -> {
                val parts = s.split(":", limit = 3)                                                // FFTTBT:token:name
                if (parts.size < 3) { Toast.makeText(this, "Bad Bluetooth code", Toast.LENGTH_SHORT).show() }
                else ensureBtPermission { receiveViaBluetooth(parts[1], parts[2]) }
            }
            else -> Toast.makeText(this, "Not an FFTT transfer code", Toast.LENGTH_SHORT).show()
        }
    }

    /** Connect to the already-paired sender (matched by Bluetooth name) and pull the missing files. */
    private fun receiveViaBluetooth(token: String, name: String) {
        val adapter = (getSystemService(BluetoothManager::class.java))?.adapter
        if (adapter == null) { Toast.makeText(this, "No Bluetooth on this device", Toast.LENGTH_LONG).show(); return }
        if (!adapter.isEnabled) { Toast.makeText(this, "Turn Bluetooth on, then try again", Toast.LENGTH_LONG).show(); return }
        val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null } ?: emptySet()
        val matches = bonded.filter { (runCatching { it.name }.getOrNull() ?: "") == name }
        android.util.Log.i("FFTTxfer", "bt receive: looking for '$name', bonded matches=${matches.size} of ${bonded.size}")
        when {
            matches.isEmpty() -> Toast.makeText(this,
                "Pair \"$name\" in Bluetooth settings first, then scan again", Toast.LENGTH_LONG).show()
            matches.size == 1 -> connectBluetooth(adapter, matches[0], token)
            else -> AlertDialog.Builder(this)
                .setTitle("Choose device")
                .setItems(matches.map { it.name }.toTypedArray()) { _, i -> connectBluetooth(adapter, matches[i], token) }
                .show()
        }
    }

    private fun connectBluetooth(adapter: BluetoothAdapter, device: BluetoothDevice, token: String) {
        val addr = runCatching { device.address }.getOrNull()
        if (addr == null) { Toast.makeText(this, "Couldn't read device address", Toast.LENGTH_LONG).show(); return }
        startReceiveService {
            putExtra(TransferService.EX_TRANSPORT, "bt")
            putExtra(TransferService.EX_TOKEN, token)
            putExtra(TransferService.EX_BT_ADDR, addr)
        }
    }

    // Best-effort POST_NOTIFICATIONS (API 33+) so transfer notifications show; runs the action either way.
    private var pendingNotifAction: (() -> Unit)? = null
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        val a = pendingNotifAction; pendingNotifAction = null; a?.invoke()
    }

    private fun startReceiveService(extras: Intent.() -> Unit) {
        val launch = {
            android.util.Log.i("FFTTxfer", "startReceiveService")
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java).apply(extras))
            Toast.makeText(this, "Receiving in the background — watch your notifications", Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotifAction = launch
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launch()
    }

    // Import a gallery bundle file picked from anywhere (Files, Drive, a download, etc.).
    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFromUri(it) }
    }

    /** Import a bundle from any content/file URI (the file picker AND the VIEW intent both use this).
     *  Validates: a zip with no FFTT recordings is politely rejected rather than silently doing nothing. */
    private fun importFromUri(uri: Uri) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val res = contentResolver.openInputStream(uri)?.use {
                    GalleryTransfer.importBundle(this, it)
                } ?: GalleryTransfer.ImportResult(0, 0)
                runOnUiThread { reportImport(res) }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Toast the import outcome and refresh the grid when anything new arrived. */
    private fun reportImport(res: GalleryTransfer.ImportResult) {
        val msg = when {
            res.imported > 0 && res.skipped > 0 -> "Imported ${res.imported}, skipped ${res.skipped} already present"
            res.imported > 0 -> "Imported ${res.imported} recording(s)"
            res.skipped > 0 -> "All ${res.skipped} already present"
            else -> "No FFTT recordings found in that file"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (res.imported > 0) loadFiles()
    }

    /** Handle a shared bundle opened from outside the app (Quick Share / Files → "open with FFTT"). */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { importFromUri(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        // Roundel only in LANDSCAPE here — in narrow portrait it pushes "GALLERY" onto two lines on
        // small devices (Pixel 3a, J7). Listen keeps it in both orientations.
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            findViewById<android.widget.TextView>(R.id.titleHeading)?.setVersionRoundelStart()

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        isGridView = prefs.getBoolean("gallery_is_grid", false)
        lastSelectedName = prefs.getString("gallery_last_selected", null)

        recyclerView = findViewById(R.id.recyclerView)
        btnViewToggle = findViewById(R.id.btnViewToggle)

        updateLayoutManager()

        loadFiles()
        handleViewIntent(intent)   // a shared bundle may have launched us

        // "Dig deeper into clip storage": backfill missing FFT icons + match comments for clips that
        // were stored without them. Background; refreshes the grid (throttled) as artifacts appear.
        thread {
            var lastRefresh = 0L
            ClipBackfill.run(this) {
                val now = System.currentTimeMillis()
                if (now - lastRefresh > 1500L) {
                    lastRefresh = now
                    runOnUiThread { if (!isFinishing && !isDestroyed) loadFiles() }
                }
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) loadFiles() }   // final refresh
        }

        // One-shot on-device HuBERT latency probe (only if the model has been pushed to the device).
        // Logs to:  adb logcat -s HubertProbe
        if (com.example.FFTT04M.cough.HubertProbe.available(this)) thread {
            try {
                val dir = GalleryTransfer.recordingsDir(this) ?: filesDir
                val wav = dir.listFiles { f -> f.extension.equals("wav", true) }?.firstOrNull()
                if (wav != null) { val d = WavReader.read(wav); com.example.FFTT04M.cough.HubertProbe.probeOnce(this, d.samples, d.sampleRate) }
            } catch (e: Exception) { android.util.Log.w("HubertProbe", "trigger failed: ${e.message}") }
        }

        btnViewToggle.setOnClickListener {
            isGridView = !isGridView
            prefs.edit().putBoolean("gallery_is_grid", isGridView).apply()
            updateLayoutManager()
            recyclerView.adapter?.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
        findViewById<Button>(R.id.btnManual).setOnClickListener {
            startActivity(android.content.Intent(this, ManualActivity::class.java))
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { showShareDialog() }
        findViewById<Button>(R.id.btnCloud).setOnClickListener {
            startActivity(Intent(this, com.example.FFTT04M.cough.ClipCloudActivity::class.java))
        }
    }

    /** SHARE → Bluetooth (paired, no network), Wi-Fi QR (same LAN), or file (any network). */
    private fun showShareDialog() {
        val options = arrayOf(
            "Pair Bluetooth Device",
            "Send via Bluetooth",
            "Receive (scan QR)",
            "Send via Wi-Fi QR",
            "Export / share to file…",
            "Import from file…",
            "Offer recordings to desktop (USB)"
        )
        AlertDialog.Builder(this)
            .setTitle("Share gallery")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, BluetoothPairingActivity::class.java))
                    1 -> if (GalleryTransfer.recordingWavs(this).isEmpty())
                        Toast.makeText(this, "No recordings to send", Toast.LENGTH_SHORT).show()
                    else startActivity(Intent(this, BtSendActivity::class.java))
                    2 -> scanLauncher.launch(ScanOptions().apply {        // Wi-Fi or Bluetooth QR
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Scan the QR shown on the SENDING device")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    })
                    3 -> if (GalleryTransfer.recordingWavs(this).isEmpty())
                        Toast.makeText(this, "No recordings to send", Toast.LENGTH_SHORT).show()
                    else startActivity(Intent(this, ExportActivity::class.java))
                    4 -> exportToFile()
                    5 -> importFileLauncher.launch("*/*")
                    6 -> offerToDesktop()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Cooperative USB handshake: stage recordings + metadata, publish an "offer" manifest the
     *  desktop reads, then wait for the desktop's "ack" (written back via adb) and confirm receipt.
     *  Bulk data flows phone→desktop (adb is host-initiated); the manifests carry the signalling. */
    private fun offerToDesktop() {
        val wavs = GalleryTransfer.recordingWavs(this)
        if (wavs.isEmpty()) {
            Toast.makeText(this, "No recordings to offer", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Preparing ${wavs.size} recordings…", Toast.LENGTH_SHORT).show()
        thread {
            // Move into public storage when permitted, and refresh each <base>.json metadata sidecar.
            GalleryTransfer.migrateToPublicIfNeeded(this)
            wavs.forEach { GalleryTransfer.writeMetaSidecar(this, it.nameWithoutExtension) }
            val dir = GalleryTransfer.recordingsDir(this)
            val ackFile = dir?.let { java.io.File(it, "fftt_usb_ack.json") }
            ackFile?.delete()   // clear any stale ack from a previous transfer

            // Publish the offer manifest the desktop's "Request from USB Device" reads.
            val manifest = org.json.JSONObject().apply {
                put("type", "fftt_usb_offer"); put("status", "offering")
                put("device_model", android.os.Build.MODEL); put("app", packageName)
                put("count", wavs.size); put("created_ms", System.currentTimeMillis())
                put("recordings", org.json.JSONArray().apply { wavs.forEach { put(it.nameWithoutExtension) } })
            }
            dir?.let { runCatching { java.io.File(it, "fftt_usb_offer.json").writeText(manifest.toString()) } }

            val path = dir?.absolutePath ?: "(unavailable)"
            val isPublic = GalleryTransfer.hasPublicStorageAccess(this)
            android.util.Log.i("FFTT04M", "USB offer published: ${wavs.size} clip(s) in $path (public=$isPublic)")
            runOnUiThread {
                // The desktop pushes the ack beside the offer it read — that's recordingsDir, but it may
                // also be public storage. Watch BOTH so a legacy/app-private vs public mismatch can't hide it.
                val publicAck = java.io.File(GalleryTransfer.publicDir(), "fftt_usb_ack.json")
                val ackCandidates = listOfNotNull(ackFile, publicAck)
                val started = System.currentTimeMillis()
                var statusLine = "⏳ Waiting for the desktop to receive…"
                val msgView = android.widget.TextView(this).apply { setPadding(56, 36, 56, 8); textSize = 14f }
                fun render() {
                    val elapsed = (System.currentTimeMillis() - started) / 1000
                    msgView.text = buildString {
                        append("Offering ${wavs.size} recording(s) + metadata to the desktop.\n\n")
                        append("On the computer: open the FFTT04D Cough Analyzer → \"Request from USB Device\".\n\n")
                        append("Folder: $path\n")
                        append("Storage: ${if (isPublic) "public ✓ (All files access)" else "app-private — grant \"All files access\" for reliable USB reads"}\n")
                        append("Handshake file: fftt_usb_ack.json\n\n")
                        append(statusLine)
                        append("   (${elapsed}s elapsed)")
                    }
                }
                render()
                val dialog = AlertDialog.Builder(this).setTitle("USB Offer").setView(msgView)
                    .setPositiveButton("Done", null)
                    // The phone decides deletion — but NEVER delete unsent clips. If the transfer isn't
                    // confirmed (no ack yet), an affirmative delete is ARMED and only runs once the ack
                    // arrives; if it never arrives, nothing is deleted.
                    .setNeutralButton("Desktop got them → Delete") { _, _ ->
                        val confirmed = ackCandidates.any { it.isFile }
                        android.util.Log.i("FFTT04M", "USB offer: manual delete tapped (transferConfirmed=$confirmed)")
                        promptDeleteAfterTransfer(wavs, wavs.size, confirmed)
                    }
                    .show()

                // Live elapsed-time ticker (uses the view's own handler — no extra imports).
                val ticker = object : Runnable {
                    override fun run() {
                        if (!dialog.isShowing || isFinishing) return
                        render()
                        msgView.postDelayed(this, 1000)
                    }
                }
                msgView.postDelayed(ticker, 1000)

                // Poll for the desktop's ack for up to 20 min (human-paced handshake + slow USB pulls).
                // Poll independently of the dialog: tapping a dialog button dismisses it, and we must keep
                // watching so the auto delete-prompt — or a deferred armed delete — still fires on the ack.
                thread {
                    val deadline = System.currentTimeMillis() + 1_200_000
                    while (System.currentTimeMillis() < deadline && !isFinishing) {
                        val hit = ackCandidates.firstOrNull { it.isFile }
                        if (hit != null) {
                            val n = runCatching {
                                org.json.JSONObject(hit.readText()).optInt("received_count", wavs.size)
                            }.getOrDefault(wavs.size)
                            android.util.Log.i("FFTT04M", "USB ack seen at ${hit.absolutePath}: received_count=$n")
                            runOnUiThread {
                                if (!isFinishing) {
                                    msgView.removeCallbacks(ticker)
                                    if (dialog.isShowing) dialog.dismiss()
                                    if (pendingDeleteOnAck) {          // user pre-affirmed; now it's safe
                                        pendingDeleteOnAck = false
                                        performDeletion(pendingTransferList, n)
                                    } else {
                                        promptDeleteAfterTransfer(wavs, n, transferConfirmed = true)
                                    }
                                }
                            }
                            return@thread
                        }
                        try { Thread.sleep(1500) } catch (e: InterruptedException) { return@thread }
                    }
                    android.util.Log.i("FFTT04M", "USB ack not seen before timeout/dismiss (pendingDelete=$pendingDeleteOnAck)")
                    if (pendingDeleteOnAck) {   // armed but never confirmed → keep the files, say so
                        pendingDeleteOnAck = false
                        runOnUiThread {
                            if (!isFinishing) Toast.makeText(this@GalleryActivity,
                                "Transfer never confirmed — nothing was deleted.", Toast.LENGTH_LONG).show()
                        }
                    }
                    runOnUiThread {
                        if (!isFinishing && dialog.isShowing) {
                            statusLine = "⚠ No desktop confirmation after 20 min. If the desktop shows it " +
                                "received them, tap \"Desktop got them → Delete\"."
                            render()
                        }
                    }
                }
            }
        }
    }

    // A delete the user affirmed BEFORE the transfer was confirmed: held until the ack proves the
    // desktop actually received the files, so unsent recordings are never deleted.
    private var pendingDeleteOnAck = false
    private var pendingTransferList: List<File> = emptyList()

    /** Confirm dialog for the phone-decided post-transfer delete. When [transferConfirmed] is false
     *  (no ack yet) an affirmative answer ARMS the delete instead of running it — nothing is removed
     *  until the ack confirms receipt; if confirmation never comes, nothing is deleted. */
    private fun promptDeleteAfterTransfer(transferred: List<File>, ackedCount: Int, transferConfirmed: Boolean) {
        val msg = if (transferConfirmed)
            "The desktop confirmed receipt of $ackedCount recording(s).\n\n" +
            "Delete the ${transferred.size} recording(s) you offered from this phone now? " +
            "Their spectrogram thumbnails and metadata go too. This can't be undone."
        else
            "⚠ The desktop has NOT confirmed receipt yet.\n\n" +
            "If you choose Delete, nothing is removed now — the app WAITS and deletes the " +
            "${transferred.size} recording(s) only once the transfer is confirmed complete. " +
            "If it's never confirmed, nothing is deleted."
        AlertDialog.Builder(this)
            .setTitle("Delete transferred recordings?")
            .setMessage(msg)
            .setPositiveButton(if (transferConfirmed) "Delete" else "Delete when confirmed") { _, _ ->
                if (transferConfirmed) {
                    performDeletion(transferred, ackedCount)
                } else {
                    pendingDeleteOnAck = true
                    pendingTransferList = transferred
                    Toast.makeText(this, "Delete armed — runs only after the desktop confirms receipt.",
                        Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    /** Actually remove the transferred clips + sidecars. ONLY call once the transfer is confirmed.
     *  Runs on a background thread: deleting many clips (5 file ops each) on the main thread froze the
     *  UI and tripped an ANR when a large batch was offered. */
    private fun performDeletion(transferred: List<File>, ackedCount: Int) {
        val toDelete = transferred.toList()   // snapshot
        Toast.makeText(this, "Deleting ${toDelete.size} transferred recording(s)…", Toast.LENGTH_SHORT).show()
        thread {
            var deleted = 0
            for (wav in toDelete) {
                val base = wav.nameWithoutExtension
                val parent = wav.parentFile
                if (wav.exists() && wav.delete()) deleted++
                File(parent, "$base.png").delete()
                File(parent, "$base.txt").delete()
                File(parent, "$base.phon").delete()
                File(parent, "$base.json").delete()
            }
            android.util.Log.i("FFTT04M",
                "USB: deleted $deleted/${toDelete.size} transferred recording(s) (desktop ack reported $ackedCount)")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, "Deleted $deleted transferred recording(s)", Toast.LENGTH_LONG).show()
                loadFiles()
            }
        }
    }

    /** Build a .fftt bundle and hand it to the system share sheet (Quick Share / Bluetooth / email /
     *  Drive / …) — works across any network, unlike the same-Wi-Fi QR path. */
    private fun exportToFile() {
        val wavs = GalleryTransfer.recordingWavs(this)
        if (wavs.isEmpty()) {
            Toast.makeText(this, "No recordings to send", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Building bundle…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }   // keep only the latest
                // .zip extension: receivers recognise it (no "open with Wallet"), and FFTT
                // registers as a handler so tapping the received file imports it.
                val f = File(dir, "FFTT_gallery_${System.currentTimeMillis()}.zip")
                f.outputStream().use { GalleryTransfer.buildBundle(this, wavs, it) }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                runOnUiThread {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Share gallery (${wavs.size} recordings)"))
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Receiver: parse the donor's handshake (FFTT1:ip:port:token), connect, send the token, and
     *  pull the gallery bundle. */
    private fun receiveBundleFrom(handshake: String) {
        val parts = handshake.split(":")
        if (parts.size != 4 || parts[0] != GalleryTransfer.HANDSHAKE_PREFIX) {
            Toast.makeText(this, "Not an FFTT transfer code", Toast.LENGTH_SHORT).show()
            return
        }
        val ip = parts[1]
        val port = parts[2].toIntOrNull() ?: return
        val token = parts[3]
        startReceiveService {
            putExtra(TransferService.EX_TRANSPORT, "wifi")
            putExtra(TransferService.EX_TOKEN, token)
            putExtra(TransferService.EX_IP, ip)
            putExtra(TransferService.EX_PORT, port)
        }
    }

    override fun onResume() {
        super.onResume()
        // A comment edit in the Viewer can change the sidecar .txt and the .png thumbnail; reload so
        // those changes (and any newly recorded files) show without leaving the gallery.
        loadFiles()
    }

    // Live-refresh the grid as a background transfer imports each clip (TransferService broadcasts).
    private val importProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { loadFiles() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, importProgressReceiver, IntentFilter(TransferService.ACTION_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(importProgressReceiver) }
        ClipBackfill.cancel()   // stop heavy backfill on leave so it can't pile onto Listen → LMK kill
    }

    private fun loadFiles() {
        val filesDir = GalleryTransfer.recordingsDir(this)
        // Match audio recordings: WAV is the current format; FLAC is kept for backward compat
        // with files saved by older builds.
        files = filesDir?.listFiles { file -> file.extension == "wav" || file.extension == "flac" }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList() ?: mutableListOf()

        // #4b: detect a freshly acquired recording (newest filename changed since last load) so we can
        // flash it — but only the first time we see a non-empty list, never on the very first load.
        val newest = files.firstOrNull()?.name
        if (newest != null && lastAcquiredName != null && newest != lastAcquiredName) flashName = newest
        lastAcquiredName = newest

        // Preserve scroll position across the adapter swap (don't jump to top on live refresh/onResume,
        // which also means we never scroll away from a highlighted selection the user is looking at).
        val lmState = recyclerView.layoutManager?.onSaveInstanceState()
        recyclerView.adapter = GalleryAdapter(files)
        if (lmState != null) recyclerView.layoutManager?.onRestoreInstanceState(lmState)

        // Flash the new arrival only if it actually sits in the currently visible range.
        flashName?.let { name ->
            recyclerView.post {
                val pos = files.indexOfFirst { it.name == name }
                val lm = recyclerView.layoutManager as? LinearLayoutManager
                if (pos >= 0 && lm != null &&
                    pos in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()) {
                    recyclerView.findViewHolderForAdapterPosition(pos)?.itemView?.let { flashView(it) }
                }
                flashName = null
            }
        }
    }

    /** Brief alpha blink to draw the eye to a just-arrived recording. */
    private fun flashView(v: View) {
        android.animation.ObjectAnimator
            .ofFloat(v, "alpha", 1f, 0.2f, 1f, 0.2f, 1f)
            .setDuration(900)
            .start()
    }

    private fun updateLayoutManager() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isGridView || isLandscape) {
            recyclerView.layoutManager = GridLayoutManager(this, 3)
            btnViewToggle.setImageResource(R.drawable.ic_view_list)
        } else {
            recyclerView.layoutManager = LinearLayoutManager(this)
            btnViewToggle.setImageResource(R.drawable.ic_view_module)
        }
    }

    private fun showDeleteDialog(file: File, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("DELETE?")
            .setMessage("Remove ${file.name}?")
            .setPositiveButton("DELETE") { _, _ ->
                val iconFile = File(file.parent, file.nameWithoutExtension + ".png")
                val commentFile = File(file.parent, file.nameWithoutExtension + ".txt")
                val phonFile = File(file.parent, file.nameWithoutExtension + ".phon")
                if (file.exists()) file.delete()
                if (iconFile.exists()) iconFile.delete()
                if (commentFile.exists()) commentFile.delete()
                if (phonFile.exists()) phonFile.delete()
                files.removeAt(position)
                recyclerView.adapter?.notifyItemRemoved(position)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    inner class GalleryAdapter(private val files: List<File>) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.gallery_item, parent, false)
            applyAutoSizeText(view)
            // The match comment is multi-line (top-3) — keep it at a fixed legible size instead of
            // letting applyAutoSizeText shrink it toward 6sp to fit (which made it illegibly tiny).
            view.findViewById<TextView>(R.id.itemComment)?.let {
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(
                    it, androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
                it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            val isLandscape = holder.itemView.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            
            val boxParams = holder.textBox.layoutParams as LinearLayout.LayoutParams
            if (isGridView || isLandscape) {
                holder.root.orientation = LinearLayout.VERTICAL
                boxParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                boxParams.weight = 0f
                boxParams.marginStart = 0
                holder.textBox.setPadding(0, 4, 0, 0)
                holder.textView.gravity = android.view.Gravity.CENTER
                holder.textView.maxLines = 1
                holder.textView.ellipsize = android.text.TextUtils.TruncateAt.END
                holder.commentView.gravity = android.view.Gravity.START   // multi-line top-3 reads left-aligned
                holder.commentView.maxLines = Int.MAX_VALUE               // show the full comment; never truncate
                holder.commentView.ellipsize = null
            } else {
                holder.root.orientation = LinearLayout.HORIZONTAL
                boxParams.width = 0
                boxParams.weight = 1f
                boxParams.marginStart = 8
                holder.textBox.setPadding(0, 0, 0, 0)
                holder.textView.gravity = android.view.Gravity.START
                holder.textView.maxLines = Int.MAX_VALUE
                holder.commentView.gravity = android.view.Gravity.START
                holder.commentView.maxLines = 5   // header + up to 3 matches show fully in the list
                holder.commentView.ellipsize = android.text.TextUtils.TruncateAt.END
            }

            holder.textView.text = file.name

            // Comment cell = the user's manual comment (the .txt, but NOT the old auto-generated
            // "auto-match (top-3)" dumps) + the on-device phoneme decode (≈ class: word, from .phon).
            val base = file.nameWithoutExtension
            val commentFile = File(file.parent, "$base.txt")
            val manual = if (commentFile.exists())
                (commentFile.readText().trim().takeUnless { it.startsWith("auto-match") } ?: "") else ""
            val decoded = file.parentFile?.let { PhonemeDecoder.read(it, base) }
            val sb = StringBuilder()
            if (manual.isNotEmpty()) sb.append(manual)
            if (decoded != null && decoded.letter != "?") {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("≈ ").append(decoded.label).append(" (").append(decoded.letter).append("): ")
                    .append(decoded.word.joinToString(" "))
            }
            if (sb.isEmpty()) {
                holder.commentView.visibility = View.GONE
            } else {
                holder.commentView.visibility = View.VISIBLE
                holder.commentView.text = sb.toString()
            }

            val iconFile = File(file.parent, file.nameWithoutExtension + ".png")
            if (iconFile.exists()) {
                // Downsample to ~half (256² → 128², the icon is shown at 64dp) to cut bitmap memory.
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath, opts)
                holder.imageView.setImageBitmap(bitmap)
            } else {
                holder.imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            }

            // #4b: highlight the recording the user most recently opened so it's easy to find on return.
            holder.root.setBackgroundColor(
                if (file.name == lastSelectedName) HIGHLIGHT_BG else DEFAULT_ITEM_BG
            )

            holder.itemView.setOnClickListener {
                lastSelectedName = file.name
                galleryPrefs.edit().putString("gallery_last_selected", file.name).apply()
                val intent = Intent(this@GalleryActivity, ViewerActivity::class.java).apply {
                    putExtra("FILE_PATH", file.absolutePath)
                }
                startActivity(intent)
            }

            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@GalleryActivity)
                    .setTitle(file.nameWithoutExtension)
                    .setItems(arrayOf("Cough Analysis", "Delete")) { _, which ->
                        when (which) {
                            0 -> startActivity(Intent(this@GalleryActivity,
                                com.example.FFTT04M.cough.CoughAnalysisActivity::class.java)
                                .putExtra("FILE_PATH", file.absolutePath))
                            1 -> showDeleteDialog(file, position)
                        }
                    }
                    .show()
                true
            }
        }

        override fun getItemCount(): Int = files.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: LinearLayout = view.findViewById(R.id.galleryItemRoot)
            val imageView: ImageView = view.findViewById(R.id.itemIcon)
            val textBox: LinearLayout = view.findViewById(R.id.itemTextBox)
            val textView: TextView = view.findViewById(R.id.itemText)
            val commentView: TextView = view.findViewById(R.id.itemComment)
        }
    }
}
