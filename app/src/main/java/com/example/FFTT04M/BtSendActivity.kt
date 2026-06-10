package com.example.FFTT04M

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Bluetooth donor: opens an RFCOMM server and shows a QR (FFTTBT:token:btName). The receiver scans
 * it, connects to this (already-paired) device by name, negotiates which recordings it's missing,
 * and we stream only those. No Wi-Fi / network needed — sidesteps AP isolation.
 *
 * Once the receiver connects, the QR is replaced by a live per-recording list: each NEW clip shows
 * its own green progress bar + throughput; clips the receiver already has are marked and skipped.
 * The Activity declares configChanges in the manifest, so rotating the device does NOT recreate it
 * (and therefore can't drop the in-flight transfer).
 */
class BtSendActivity : AppCompatActivity() {

    private enum class Cat { PENDING, ACTIVE, DONE, ALREADY }

    private inner class Row(val base: String, val sizeBytes: Long) {
        var cat = Cat.PENDING
        var sent = 0L
        var total = sizeBytes
        var bps = 0L
    }

    private var server: BluetoothServerSocket? = null
    @Volatile private var transferring = false
    @Volatile private var allSent = false
    @Volatile private var finished = false

    // Overall progress / ETA, in bytes (set once the receiver tells us what it already has).
    @Volatile private var totalNew = 0
    @Volatile private var totalNewBytes = 0L
    @Volatile private var completedBytes = 0L
    @Volatile private var startTimeMs = 0L
    private var lastActiveBase: String? = null

    // Smooth-progress extrapolation: real updates arrive in bursts (the OS Bluetooth socket buffer
    // swallows several small clips at once), so between updates we glide the bars forward from the
    // measured average throughput, then SNAP to the real value at each packet boundary. Prediction is
    // capped to one clip ahead of confirmed progress so a burst-inflated rate can't overshoot.
    private val tickHandler = Handler(Looper.getMainLooper())
    @Volatile private var anchorDone = 0L       // real cumulative bytes at the last update
    @Volatile private var anchorMs = 0L
    @Volatile private var avgBpsPerSec = 0L     // overall average throughput (stable vs per-burst spikes)
    @Volatile private var shownDone = 0L        // monotonic displayed cumulative bytes
    @Volatile private var activeAnchorSent = 0L
    @Volatile private var activeTotal = 0L
    private val ticker = object : Runnable {
        override fun run() { extrapolate(); if (transferring) tickHandler.postDelayed(this, 50) }
    }

    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var overall: ProgressBar
    private lateinit var fileList: RecyclerView

    private val rows = ArrayList<Row>()
    private val rowIndex = HashMap<String, Int>()
    private var adapter: RowAdapter? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startServer() else { toast("Bluetooth permission is needed to send"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Send via Bluetooth"
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 0, 0, pad)
        }
        qr = ImageView(this)
        overall = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 1000
            progressTintList = ColorStateList.valueOf(GREEN)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (10 * d).toInt())
        }
        fileList = RecyclerView(this).apply {
            visibility = View.GONE
            layoutManager = LinearLayoutManager(this@BtSendActivity)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // Seed the row list from this device's recordings (order matches the bundle stream).
        for (w in GalleryTransfer.recordingWavs(this)) {
            rowIndex[w.nameWithoutExtension] = rows.size
            rows.add(Row(w.nameWithoutExtension, w.length()))
        }
        adapter = RowAdapter()
        fileList.adapter = adapter

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK); setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(status)
            addView(qr, LinearLayout.LayoutParams((260 * d).toInt(), (260 * d).toInt()))
            addView(overall)
            addView(fileList)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            startServer()
        }
    }

    private fun startServer() {
        val btAdapter = (getSystemService(BluetoothManager::class.java))?.adapter
        if (btAdapter == null) { toast("No Bluetooth on this device"); finish(); return }
        if (!btAdapter.isEnabled) { toast("Turn Bluetooth on, then try again"); finish(); return }
        if (rows.isEmpty()) { toast("No recordings to send"); finish(); return }

        val token = UUID.randomUUID().toString().take(8)
        val btName = try { btAdapter.name ?: "this device" } catch (e: SecurityException) { "this device" }

        val srv = try {
            btAdapter.listenUsingRfcommWithServiceRecord("FFTTShare", GalleryTransfer.BT_UUID)
        } catch (e: Throwable) {
            toast("Bluetooth error: ${e.message}"); finish(); return
        }
        server = srv

        val payload = "${GalleryTransfer.BT_PREFIX}:$token:$btName"
        try {
            val sz = (260 * resources.displayMetrics.density).toInt().coerceAtLeast(256)
            qr.setImageBitmap(BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, sz, sz))
        } catch (_: Throwable) {}
        status.text = "On the receiving device:\nGallery → SHARE → Receive (scan QR), then scan this code.\n\n" +
            "Bluetooth name: $btName"

        android.util.Log.i("FFTTxfer", "BT serving as '$btName' token=$token, waiting for connection")
        thread {
            try {
                srv.accept().use { sock ->
                    android.util.Log.i("FFTTxfer", "BT accepted connection")
                    transferring = true
                    startTimeMs = System.currentTimeMillis()
                    runOnUiThread { onTransferStarted() }
                    val sent = GalleryTransfer.sendNegotiated(
                        this, sock.inputStream, sock.outputStream, token,
                        onNegotiated = { have -> runOnUiThread { onNegotiated(have) } },
                        onProgress = { p -> runOnUiThread { onProgress(p) } }
                    )
                    android.util.Log.i("FFTTxfer", "BT sendNegotiated returned $sent")
                    transferring = false
                    allSent = true
                    runOnUiThread {
                        if (sent < 0) failOnce("Handshake mismatch") else finishWithSuccess(sent)
                    }
                }
            } catch (e: Throwable) {
                transferring = false
                android.util.Log.w("FFTTxfer", "send thread ended: ${e.message}")
                runOnUiThread {
                    // If every new clip's bytes already went out, a broken pipe during socket teardown
                    // is benign — report success rather than a scary error.
                    if (allSent) finishWithSuccess(totalNew)
                    else failOnce("Send cancelled/failed: ${e.message}")
                }
            }
        }
    }

    private fun onTransferStarted() {
        qr.visibility = View.GONE
        overall.visibility = View.VISIBLE
        fileList.visibility = View.VISIBLE
        status.text = "Receiver connected — preparing…"
        tickHandler.removeCallbacks(ticker)
        tickHandler.post(ticker)
    }

    /** Glide the overall + active-clip bars forward from average throughput between real updates. */
    private fun extrapolate() {
        if (totalNewBytes <= 0 || avgBpsPerSec <= 0L) return
        val ahead = avgBpsPerSec * (System.currentTimeMillis() - anchorMs) / 1000
        val oneClip = if (totalNew > 0) totalNewBytes / totalNew else totalNewBytes
        val cap = minOf((totalNewBytes * 0.997).toLong(), anchorDone + oneClip)   // ≤ 1 clip ahead of real
        val pred = (anchorDone + ahead).coerceIn(anchorDone, cap)
        if (pred > shownDone) {
            shownDone = pred
            overall.progress = ((shownDone.toDouble() / totalNewBytes) * 1000).toInt()
        }
        val base = lastActiveBase ?: return
        if (activeTotal <= 0) return
        val pos = rowIndex[base] ?: return
        val vh = fileList.findViewHolderForLayoutPosition(pos) as? RowAdapter.VH ?: return
        val predSent = (activeAnchorSent + ahead).coerceAtMost((activeTotal * 0.97).toLong())
        vh.bar.progress = ((predSent.toDouble() / activeTotal) * 1000).toInt()
    }

    /** Receiver told us which base names it already has: categorize rows + compute the byte budget. */
    private fun onNegotiated(have: Set<String>) {
        var newCount = 0
        var newBytes = 0L
        for (r in rows) {
            if (r.base in have) {
                r.cat = Cat.ALREADY
            } else {
                r.cat = Cat.PENDING
                newCount++
                newBytes += r.total
            }
        }
        totalNew = newCount
        totalNewBytes = newBytes
        allSent = newCount == 0           // comment-only sync still "succeeds"
        adapter?.notifyDataSetChanged()
        val already = rows.size - newCount
        status.text = if (newCount == 0)
            "Nothing new to send — syncing comments for $already clip(s)…"
        else
            "Sending $newCount new clip(s)" + if (already > 0) "  •  $already already on receiver" else ""
    }

    private fun onProgress(p: GalleryTransfer.SendProgress) {
        // A new clip started → finalize the previous active row to DONE (full bar).
        val prev = lastActiveBase
        if (prev != null && prev != p.base) {
            rowIndex[prev]?.let { pi ->
                val r = rows[pi]
                if (r.cat != Cat.DONE) {
                    r.sent = r.total
                    r.cat = Cat.DONE
                    adapter?.notifyItemChanged(pi)
                }
            }
        }
        lastActiveBase = p.base

        val i = rowIndex[p.base] ?: return
        val r = rows[i]
        r.total = p.totalBytes
        r.sent = p.sentBytes
        r.bps = p.bytesPerSec
        r.cat = if (p.totalBytes > 0 && p.sentBytes >= p.totalBytes) Cat.DONE else Cat.ACTIVE
        adapter?.notifyItemChanged(i)

        if (p.fileIndex >= p.fileTotal && p.totalBytes > 0 && p.sentBytes >= p.totalBytes) allSent = true

        // Real (packet-boundary) update: compute doneSoFar directly from row states, not a fragile counter.
        // Sum all DONE rows' full sizes + the current ACTIVE row's sentBytes.
        var doneSoFar = 0L
        for (j in rows.indices) {
            val rr = rows[j]
            doneSoFar += when (rr.cat) {
                Cat.DONE -> rr.total
                Cat.ACTIVE -> rr.sent
                else -> 0L
            }
        }
        anchorDone = doneSoFar
        anchorMs = System.currentTimeMillis()
        activeAnchorSent = p.sentBytes
        activeTotal = p.totalBytes
        // Overall average throughput — stable across the bursty per-clip rate; drives glide + ETA + readout.
        val totalElapsed = (anchorMs - startTimeMs).coerceAtLeast(1L)
        avgBpsPerSec = doneSoFar * 1000 / totalElapsed
        shownDone = maxOf(shownDone, doneSoFar)
        if (totalNewBytes > 0)
            overall.progress = ((shownDone.toDouble() / totalNewBytes).coerceIn(0.0, 1.0) * 1000).toInt()
        val remainingBytes = (totalNewBytes - doneSoFar).coerceAtLeast(0L)
        val etaSec = if (avgBpsPerSec > 0) (remainingBytes + avgBpsPerSec - 1) / avgBpsPerSec else 0L
        status.text = "Sending ${p.fileIndex} / ${p.fileTotal}  •  ETA ${etaSec}s  •  ${kbPerSec(avgBpsPerSec)}"
    }

    private fun finishWithSuccess(n: Int) {
        if (finished || isFinishing) return
        finished = true
        // Flip any still-active row to done so the list reads cleanly on the way out.
        var changed = false
        for (idx in rows.indices) {
            val r = rows[idx]
            if (r.cat == Cat.ACTIVE || (r.cat == Cat.PENDING && totalNew > 0)) { r.sent = r.total; r.cat = Cat.DONE; changed = true }
        }
        if (changed) adapter?.notifyDataSetChanged()
        toast(if (n <= 0) "Comments synced" else "Sent $n recording(s)")
        finish()
    }

    private fun failOnce(msg: String) {
        if (finished || isFinishing) return
        finished = true
        toast(msg)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (transferring && !allSent) {
            val remaining = totalNew - (rows.count { it.cat == Cat.DONE })
            AlertDialog.Builder(this)
                .setTitle("Abort transfer?")
                .setMessage("Leaving this screen will abort the transfer in progress.\n\n" +
                    "$remaining of $totalNew file(s) remaining.\n" +
                    "Proceed?")
                .setPositiveButton("Yes, abort") { _, _ ->
                    finished = true
                    try { server?.close() } catch (_: Throwable) {}
                    finish()
                }
                .setNegativeButton("No, keep going", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(ticker)
        try { server?.close() } catch (_: Throwable) {}
    }

    // ---- row list ------------------------------------------------------------------------------

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(android.R.id.text1)
            val bar: ProgressBar = itemView.findViewById(android.R.id.progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val d = resources.displayMetrics.density
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            }
            container.addView(TextView(parent.context).apply {
                id = android.R.id.text1
                setTextColor(Color.WHITE); textSize = 13f
            })
            container.addView(ProgressBar(parent.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                id = android.R.id.progress
                max = 1000
                progressTintList = ColorStateList.valueOf(GREEN)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * d).toInt()).apply {
                    topMargin = (4 * d).toInt()
                }
            })
            return VH(container)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = rows[position]
            when (r.cat) {
                Cat.ALREADY -> {
                    holder.name.setTextColor(GRAY)
                    holder.name.text = "${r.base}   •  Already on receiver"
                    holder.bar.visibility = View.GONE
                }
                Cat.PENDING -> {
                    holder.name.setTextColor(Color.WHITE)
                    holder.name.text = "${r.base}   ·  waiting…"
                    holder.bar.visibility = View.VISIBLE
                    holder.bar.progress = 0
                }
                Cat.ACTIVE -> {
                    holder.name.setTextColor(Color.WHITE)
                    holder.name.text = r.base   // throughput now shown next to ETA in the status line
                    holder.bar.visibility = View.VISIBLE
                    holder.bar.progress = if (r.total > 0) ((r.sent * 1000) / r.total).toInt() else 0
                }
                Cat.DONE -> {
                    holder.name.setTextColor(GREEN)
                    holder.name.text = "${r.base}   ✓"
                    holder.bar.visibility = View.VISIBLE
                    holder.bar.progress = 1000
                }
            }
        }

        override fun getItemCount() = rows.size
    }

    private fun kbPerSec(bps: Long): String {
        val kb = bps / 1024.0
        return if (kb >= 1024) String.format(java.util.Locale.US, "%.1f MB/s", kb / 1024.0)
               else String.format(java.util.Locale.US, "%.0f KB/s", kb)
    }

    companion object {
        private val GREEN = Color.parseColor("#4CAF50")
        private val GRAY = Color.parseColor("#888888")
    }
}
