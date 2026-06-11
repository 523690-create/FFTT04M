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
import java.io.File

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnViewToggle: ImageButton
    private var isGridView = false
    private var files = mutableListOf<File>()

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

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        isGridView = prefs.getBoolean("gallery_is_grid", false)

        recyclerView = findViewById(R.id.recyclerView)
        btnViewToggle = findViewById(R.id.btnViewToggle)

        updateLayoutManager()

        loadFiles()
        handleViewIntent(intent)   // a shared bundle may have launched us

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
            runOnUiThread {
                val msgView = android.widget.TextView(this).apply {
                    setPadding(56, 36, 56, 8); textSize = 14f
                    text = buildString {
                        append("Offering ${wavs.size} recordings + metadata to the desktop.\n\n")
                        append("On the computer, open the FFTT04D Cough Analyzer and click \"Request from USB Device\".\n\n")
                        append("Folder: $path")
                        if (!isPublic) append("\n\nTip: grant \"All files access\" for reliable USB reads.")
                        append("\n\n⏳ Waiting for the desktop to receive…")
                    }
                }
                AlertDialog.Builder(this).setTitle("USB Offer").setView(msgView)
                    .setPositiveButton("Done", null).show()

                // Poll for the desktop's ack (written beside the offer via adb push) for up to 2 min.
                thread {
                    val deadline = System.currentTimeMillis() + 120_000
                    while (System.currentTimeMillis() < deadline && !isFinishing) {
                        if (ackFile?.isFile == true) {
                            val n = runCatching {
                                org.json.JSONObject(ackFile.readText()).optInt("received_count", wavs.size)
                            }.getOrDefault(wavs.size)
                            runOnUiThread {
                                if (!isFinishing) msgView.text =
                                    "✓ Desktop received $n recording(s).\n\nFolder: $path"
                            }
                            break
                        }
                        try { Thread.sleep(1500) } catch (e: InterruptedException) { break }
                    }
                }
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
    }

    private fun loadFiles() {
        val filesDir = GalleryTransfer.recordingsDir(this)
        // Match audio recordings: WAV is the current format; FLAC is kept for backward compat
        // with files saved by older builds.
        files = filesDir?.listFiles { file -> file.extension == "wav" || file.extension == "flac" }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList() ?: mutableListOf()
        recyclerView.adapter = GalleryAdapter(files)
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
                if (file.exists()) file.delete()
                if (iconFile.exists()) iconFile.delete()
                if (commentFile.exists()) commentFile.delete()
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
                holder.commentView.gravity = android.view.Gravity.CENTER
                holder.commentView.maxLines = 1
                holder.commentView.ellipsize = android.text.TextUtils.TruncateAt.END
            } else {
                holder.root.orientation = LinearLayout.HORIZONTAL
                boxParams.width = 0
                boxParams.weight = 1f
                boxParams.marginStart = 8
                holder.textBox.setPadding(0, 0, 0, 0)
                holder.textView.gravity = android.view.Gravity.START
                holder.textView.maxLines = Int.MAX_VALUE
                holder.commentView.gravity = android.view.Gravity.START
                holder.commentView.maxLines = 3
                holder.commentView.ellipsize = android.text.TextUtils.TruncateAt.END
            }

            holder.textView.text = file.name

            val commentFile = File(file.parent, file.nameWithoutExtension + ".txt")
            val comment = if (commentFile.exists()) commentFile.readText().trim() else ""
            if (comment.isEmpty()) {
                holder.commentView.visibility = View.GONE
            } else {
                holder.commentView.visibility = View.VISIBLE
                holder.commentView.text = comment
            }

            val iconFile = File(file.parent, file.nameWithoutExtension + ".png")
            if (iconFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                holder.imageView.setImageBitmap(bitmap)
            } else {
                holder.imageView.setImageResource(android.R.drawable.ic_menu_report_image)
            }

            holder.itemView.setOnClickListener {
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
