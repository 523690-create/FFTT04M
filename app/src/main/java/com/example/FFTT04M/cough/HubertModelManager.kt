package com.example.FFTT04M.cough

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.FFTT04M.BuildConfig
import com.example.FFTT04M.DeviceCaps
import java.io.File
import java.security.MessageDigest

/**
 * Wi-Fi-only downloader + gate for the PUBLIC HuBERT model (Meta's hubert-base, exported to ONNX). The
 * 361 MB model is NOT bundled — only OUR proprietary codebooks ship in the APK. HuBERT features stay
 * disabled (PhonemeDecoder falls back to the DSP codebook) until the model is downloaded, verified against
 * its exact size + sha256, and installed. Host is [BuildConfig.HUBERT_MODEL_URL] (set with -PhubertUrl).
 *
 * Uses Android [DownloadManager] → Wi-Fi only, background, survives app death, free progress notification.
 * The downloaded file is verified in [onDownloadComplete] (via [HubertDownloadReceiver]) before it's
 * renamed into place, so a partial / wrong / corrupt file never reaches ORT.
 */
object HubertModelManager {
    private const val TAG = "HubertModel"
    const val FILE = "hubert_base.onnx"
    private const val PART = "hubert_base.onnx.part"
    private const val PREFS = "hubert_model"
    private const val KEY_ID = "download_id"

    private fun dir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    /** Final on-device model path (external app-private; no permission needed, cleared on uninstall). */
    fun modelFile(ctx: Context): File = File(dir(ctx), FILE)
    private fun partFile(ctx: Context): File = File(dir(ctx), PART)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPresent(ctx: Context): Boolean =
        modelFile(ctx).let { it.isFile && it.length() == BuildConfig.HUBERT_MODEL_BYTES }

    sealed class State {
        object Present : State()
        data class Downloading(val pct: Int) : State()
        object Absent : State()
        data class Failed(val reason: String) : State()
    }

    fun state(ctx: Context): State {
        if (isPresent(ctx)) return State.Present
        val id = prefs(ctx).getLong(KEY_ID, -1L)
        if (id < 0) return State.Absent
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return State.Absent
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> State.Downloading(100)  // downloaded; verifying/finalising
                DownloadManager.STATUS_FAILED -> State.Failed("download failed")
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> {
                    val so = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val tot = BuildConfig.HUBERT_MODEL_BYTES
                    State.Downloading(if (tot > 0) (so * 100 / tot).toInt().coerceIn(0, 99) else 0)
                }
                else -> State.Absent
            }
        }
        return State.Absent
    }

    fun isDownloading(ctx: Context): Boolean = state(ctx) is State.Downloading

    /** Enqueue the Wi-Fi-only download. No-op if already present or in flight. */
    fun startDownload(ctx: Context): Boolean {
        if (isPresent(ctx) || isDownloading(ctx)) return true
        val url = BuildConfig.HUBERT_MODEL_URL
        if (url.isBlank()) { Log.e(TAG, "no HUBERT_MODEL_URL configured"); return false }
        return try {
            partFile(ctx).delete()
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("Cough gold-standard model")
                .setDescription("HuBERT (361 MB) — Wi-Fi only")
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, null, PART)
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            prefs(ctx).edit().putLong(KEY_ID, id).apply()
            Log.i(TAG, "enqueued HuBERT download id=$id from $url")
            true
        } catch (t: Throwable) { Log.e(TAG, "startDownload failed: ${t.message}", t); false }
    }

    /** Verify (size + sha256) then install. Called off the main thread by [HubertDownloadReceiver]. */
    fun onDownloadComplete(ctx: Context, completedId: Long) {
        val id = prefs(ctx).getLong(KEY_ID, -1L)
        if (id < 0 || id != completedId) return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val ok = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        } ?: false
        val part = partFile(ctx)
        fun fail(why: String) { Log.e(TAG, "install rejected: $why"); part.delete(); prefs(ctx).edit().remove(KEY_ID).apply() }
        if (!ok || !part.isFile) return fail("download not successful")
        if (part.length() != BuildConfig.HUBERT_MODEL_BYTES) return fail("size ${part.length()} != ${BuildConfig.HUBERT_MODEL_BYTES}")
        val sha = sha256(part)
        if (!sha.equals(BuildConfig.HUBERT_MODEL_SHA256, ignoreCase = true)) return fail("sha256 $sha mismatch")
        val dest = modelFile(ctx); dest.delete()
        Log.i(TAG, if (part.renameTo(dest)) "HuBERT model verified + installed" else "rename to $dest failed")
        prefs(ctx).edit().remove(KEY_ID).apply()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(1 shl 20); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** UI entry (menu item + feature-tap prompt): reflect state, and offer to download over Wi-Fi. */
    fun promptDownload(activity: Activity) {
        when (val st = state(activity)) {
            is State.Present -> Toast.makeText(activity, "Gold-standard model already installed", Toast.LENGTH_SHORT).show()
            is State.Downloading -> Toast.makeText(activity, "Downloading model… ${st.pct}%", Toast.LENGTH_SHORT).show()
            else -> {
                if (!DeviceCaps.hubertAllowed(activity)) {
                    Toast.makeText(activity, "This device runs the DSP tier (needs ~3 GB RAM for HuBERT)", Toast.LENGTH_LONG).show()
                    return
                }
                AlertDialog.Builder(activity)
                    .setTitle("Download gold-standard model")
                    .setMessage("Downloads the 361 MB HuBERT model over Wi-Fi to enable the high-accuracy analysis tier. " +
                        "Until then the app uses the built-in DSP tier. Download now?")
                    .setPositiveButton("Download (Wi-Fi)") { _, _ ->
                        val msg = if (startDownload(activity)) "Download queued — runs on Wi-Fi (see the notification)"
                                  else "Couldn't start — no model URL configured yet"
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }
    }
}
