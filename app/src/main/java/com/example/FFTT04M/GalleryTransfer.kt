package com.example.FFTT04M

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Device-to-device gallery transfer. A "bundle" is a ZIP containing, per recording, the
 * `.wav` (+ optional `.png` thumbnail, `.txt` comment) and a `<base>.json` of its per-recording
 * SharedPreferences (all analysis/colour state). Plus a `manifest.json`.
 *
 * Transport is a plain TCP socket on the local Wi-Fi; a QR code carries only the handshake
 * (receiver IP:port:token). The bundle streams over the socket — nothing is buffered whole in
 * memory, so it is safe for large galleries on low-RAM devices.
 */
object GalleryTransfer {

    const val HANDSHAKE_PREFIX = "FFTT1"   // Wi-Fi QR payload: FFTT1:<ip>:<port>:<token>
    const val BT_PREFIX = "FFTTBT"         // Bluetooth QR payload: FFTTBT:<token>:<senderBtName>
    // Fixed RFCOMM service UUID for the app (any random constant works as long as both ends agree).
    val BT_UUID: java.util.UUID = java.util.UUID.fromString("5f9b34fb-ffa1-4f2e-9c3a-0f4a04040401")

    // Per-recording pref keys are mostly Int; these are the exceptions. Used on import so JSON
    // number ambiguity (44100.0 -> "44100") can't write a Float key as an Int (which would later
    // throw ClassCastException on getFloat). New Float/Bool/String keys must be added here.
    private val FLOAT_KEYS = setOf(
        "freq", "threshold", "view_off_x", "view_off_y", "view_zoom_x", "view_zoom_y",
        "noise_filter_strength", "noise_filter_rise_ms", "noise_filter_fall_ms"
    )
    private val BOOL_KEYS = setOf("soft_thresh", "log_scale", "local_norm", "gallery_is_grid")
    private val STRING_KEYS = setOf("mic_device")

    /** Mirrors ViewerActivity/WaveletActivity: per-recording prefs namespace from the file name. */
    fun prefsNameForFile(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        return "rec_" + buildString { for (c in base) append(if (c.isLetterOrDigit()) c else '_') }
    }

    // ---- Storage location -----------------------------------------------------------------------
    // Recordings live in PUBLIC storage (/sdcard/Documents/FFTT04M) so they survive an app
    // uninstall/reinstall — the old app-private getExternalFilesDir(null) is wiped on uninstall.
    // Access needs WRITE_EXTERNAL_STORAGE (API ≤29) or MANAGE_EXTERNAL_STORAGE / "All files access"
    // (API 30+). Until that's granted we fall back to the app-private dir so the app still works.

    const val PUBLIC_SUBDIR = "FFTT04M"
    private const val MIGRATED_FLAG = "migrated_public_v1"

    /** The intended public recordings folder (may not yet be writable if permission isn't granted). */
    fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), PUBLIC_SUBDIR)

    /** True once the app may read/write arbitrary files in public storage. */
    fun hasPublicStorageAccess(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Where recordings actually live right now: the public folder when accessible (survives
     *  uninstall), otherwise the legacy app-private dir as a graceful fallback. */
    fun recordingsDir(ctx: Context): File? {
        if (hasPublicStorageAccess(ctx)) {
            val pub = publicDir()
            if (pub.exists() || pub.mkdirs()) return pub
        }
        return ctx.getExternalFilesDir(null)
    }

    /** One-time copy of existing recordings (and their .png/.txt/.json sidecars) from the old
     *  app-private dir into public storage, generating a settings sidecar for each so their analysis
     *  state survives future uninstalls too. No-op until public access is granted; idempotent. */
    fun migrateToPublicIfNeeded(ctx: Context) {
        if (!hasPublicStorageAccess(ctx)) return
        val sp = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (sp.getBoolean(MIGRATED_FLAG, false)) return
        val src = ctx.getExternalFilesDir(null) ?: return
        val dst = publicDir()
        if (!dst.exists() && !dst.mkdirs()) return
        val files = src.listFiles() ?: emptyArray()
        for (f in files) {
            if (!f.isFile) continue
            val target = File(dst, f.name)
            if (!target.exists()) runCatching { f.copyTo(target, overwrite = false) }
        }
        // Settings sidecars for the migrated recordings (their rec_<base> prefs still exist now).
        files.filter { it.extension == "wav" || it.extension == "flac" }
            .map { it.nameWithoutExtension }.toSet()
            .forEach { base ->
                val side = File(dst, "$base.json")
                if (!side.exists()) runCatching { side.writeText(metaJsonFor(ctx, base)) }
            }
        sp.edit { putBoolean(MIGRATED_FLAG, true) }
    }

    // ---- Per-recording settings sidecar (<base>.json next to the audio) -------------------------

    /** Serialize a recording's per-recording SharedPreferences to the same tagged-JSON the transfer
     *  bundle uses ("f:1.0", "i:3", "b:true", "s:…", "l:…"). */
    fun metaJsonFor(ctx: Context, base: String): String {
        val prefs = ctx.getSharedPreferences(prefsNameForFile("$base.wav"), Context.MODE_PRIVATE)
        val meta = JSONObject()
        for ((k, v) in prefs.all) {
            if (v == null) continue
            meta.put(k, when (v) {
                is Boolean -> "b:$v"; is Int -> "i:$v"; is Long -> "l:$v"; is Float -> "f:$v"; else -> "s:$v"
            })
        }
        return meta.toString()
    }

    /** Write/refresh the <base>.json settings sidecar so the recording's analysis state survives an
     *  uninstall (the rec_<base> prefs themselves live in app-private storage that gets wiped). */
    fun writeMetaSidecar(ctx: Context, base: String) {
        val dir = recordingsDir(ctx) ?: return
        if (!File(dir, "$base.wav").exists() && !File(dir, "$base.flac").exists()) return
        runCatching { File(dir, "$base.json").writeText(metaJsonFor(ctx, base)) }
    }

    /** After a reinstall the app-private rec_<base> prefs are gone; if a sidecar survived in public
     *  storage, restore them. Tier-gated: legacy/low-RAM devices skip the restore entirely so they
     *  never try to apply heavy settings (mirrors the import path). */
    fun restoreMetaSidecarIfNeeded(ctx: Context, base: String) {
        if (DeviceCaps.tier(ctx) < 1) return
        val prefs = ctx.getSharedPreferences(prefsNameForFile("$base.wav"), Context.MODE_PRIVATE)
        if (prefs.all.isNotEmpty()) return
        val dir = recordingsDir(ctx) ?: return
        val f = File(dir, "$base.json")
        if (f.exists()) runCatching { restoreMeta(ctx, base, f.readText()) }
    }

    fun recordingWavs(ctx: Context): List<File> =
        recordingsDir(ctx)?.listFiles { f -> f.extension == "wav" || f.extension == "flac" }
            ?.sortedBy { it.name } ?: emptyList()

    /** Base names of the recordings already present (for transfer negotiation). */
    fun existingBaseNames(ctx: Context): Set<String> =
        recordingWavs(ctx).map { it.nameWithoutExtension }.toSet()

    // ---- Negotiated transfer (transport-agnostic: Bluetooth RFCOMM or a TCP socket) -------------
    // Wire protocol once a stream pair is connected:
    //   receiver → sender:  "<token>\n"  then  "<csv of existing base names>\n"
    //   sender   → receiver: a ZIP bundle — full package for recordings the receiver lacks, plus the
    //                        comment (.txt) ONLY for recordings it already has (so comments sync), EOF.

    /** Live per-file progress reported by the sender. [sentBytes]/[totalBytes] track THIS recording's
     *  audio; [bytesPerSec] is its running throughput; [fileIndex]/[fileTotal] count NEW recordings. */
    data class SendProgress(
        val base: String,
        val sentBytes: Long,
        val totalBytes: Long,
        val bytesPerSec: Long,
        val fileIndex: Int,
        val fileTotal: Int
    )

    /** Sender side: verify the token, read the receiver's existing names, stream the bundle.
     *  Returns how many NEW recordings were sent (or -1 on token mismatch).
     *  [onNegotiated] fires once with the base names the receiver ALREADY has (so the UI can mark
     *  them "already on receiver"). [onProgress] fires repeatedly per NEW recording as it streams. */
    fun sendNegotiated(ctx: Context, inp: InputStream, out: OutputStream, expectedToken: String,
                       onNegotiated: ((alreadyHave: Set<String>) -> Unit)? = null,
                       onProgress: ((SendProgress) -> Unit)? = null): Int {
        if (readLine(inp) != expectedToken) return -1
        val have = readLine(inp).split(",").filter { it.isNotEmpty() }.toHashSet()
        onNegotiated?.invoke(have)
        val all = recordingWavs(ctx)
        val totalNew = all.count { it.nameWithoutExtension !in have }
        buildBundle(ctx, all, out, commentOnlyFor = have, onProgress = onProgress, totalNew = totalNew)
        // The receiver may close as soon as it has read all entry data (ZipInputStream stops at the
        // central-directory marker), so a broken-pipe here is benign — the payload already landed.
        try { out.flush() } catch (_: Throwable) {}
        return totalNew
    }

    /** Receiver side: send the token + our existing names, then import the streamed bundle. */
    fun receiveNegotiated(ctx: Context, inp: InputStream, out: OutputStream, token: String,
                          onImported: ((Int, String) -> Unit)? = null): ImportResult {
        val names = existingBaseNames(ctx).joinToString(",") { it.replace(",", "_") }
        out.write("$token\n$names\n".toByteArray())
        out.flush()
        return importBundle(ctx, inp, onImported)
    }

    // ---- Export ---------------------------------------------------------------------------------

    /** Build a transfer bundle. For recordings whose base name is in [commentOnlyFor] (the receiver
     *  already has them) only the comment .txt is included, so comments sync without re-sending audio.
     *  [onProgress] fires repeatedly per NEW recording (throttled to ~10 Hz) as its audio streams. */
    fun buildBundle(ctx: Context, wavs: List<File>, out: OutputStream, commentOnlyFor: Set<String> = emptySet(),
                    onProgress: ((SendProgress) -> Unit)? = null, totalNew: Int = 0) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            val manifest = JSONObject()
                .put("app", "FFTT04M").put("schema", 1).put("count", wavs.size)
            writeEntry(zip, "manifest.json", manifest.toString().toByteArray())

            var idx = 0
            for (wav in wavs) {
                val base = wav.nameWithoutExtension
                if (base in commentOnlyFor) {
                    // Receiver already has this recording — send only the comment so edits propagate.
                    File(wav.parentFile, "$base.txt").takeIf { it.exists() }?.let { addFile(zip, it) }
                    continue
                }
                idx++
                val total = wav.length()
                val startNs = System.nanoTime()
                var sent = 0L
                var lastEmitNs = 0L
                // Emit a 0% marker so the UI flips this row to "active" the instant it starts.
                onProgress?.invoke(SendProgress(base, 0L, total, 0L, idx, totalNew))
                addFile(zip, wav) { n ->
                    sent += n
                    val nowNs = System.nanoTime()
                    if (nowNs - lastEmitNs >= 100_000_000L || sent >= total) {   // throttle to ~10 Hz
                        lastEmitNs = nowNs
                        val elapsed = (nowNs - startNs) / 1_000_000_000.0
                        val bps = if (elapsed > 0.0) (sent / elapsed).toLong() else 0L
                        onProgress?.invoke(SendProgress(base, sent, total, bps, idx, totalNew))
                    }
                }
                File(wav.parentFile, "$base.png").takeIf { it.exists() }?.let { addFile(zip, it) }
                File(wav.parentFile, "$base.txt").takeIf { it.exists() }?.let { addFile(zip, it) }

                val prefs = ctx.getSharedPreferences(prefsNameForFile(wav.name), Context.MODE_PRIVATE)
                val meta = JSONObject()
                // Type-tag every value ("f:1.0", "i:3", "b:true", "s:…") so the receiver restores
                // the exact SharedPreferences type — JSON alone can't tell Float 0.0 from Int 0,
                // which previously wrote Float keys (e.g. eq_gain_*) as Int and crashed on read.
                for ((k, v) in prefs.all) {
                    if (v == null) continue
                    val tagged = when (v) {
                        is Boolean -> "b:$v"
                        is Int -> "i:$v"
                        is Long -> "l:$v"
                        is Float -> "f:$v"
                        else -> "s:$v"
                    }
                    meta.put(k, tagged)
                }
                writeEntry(zip, "$base.json", meta.toString().toByteArray())
            }
        }
        out.flush()
    }

    private fun addFile(zip: ZipOutputStream, f: File, onBytes: ((Int) -> Unit)? = null) {
        zip.putNextEntry(ZipEntry(f.name))
        f.inputStream().use { input ->
            if (onBytes == null) {
                input.copyTo(zip)
            } else {
                // 256 KB chunks: fewer write calls for better Bluetooth throughput. Note the per-clip
                // bar can still jump, because recordings are small (≤~260 KB) and the OS socket
                // send-buffer absorbs several whole files before the air link catches up — so the
                // sender's byte count leads actual transmission. The speed readout next to ETA reflects
                // real throughput. Truly tracking transmission would need receiver→sender ACKs.
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    zip.write(buf, 0, n)
                    onBytes(n)
                }
            }
        }
        zip.closeEntry()
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    // ---- Import ---------------------------------------------------------------------------------

    /** Reads a bundle stream into the recordings dir, restoring metadata. Recordings already present
     *  (same filename) are skipped. [onImported] fires (runningCount, baseName) per new recording —
     *  used to post a per-file notification. Returns counts of imported vs skipped. */
    fun importBundle(ctx: Context, inp: InputStream, onImported: ((Int, String) -> Unit)? = null): ImportResult {
        val dir = recordingsDir(ctx) ?: return ImportResult(0, 0)
        // Keep original filenames. A recording already present (same base name) is skipped wholesale
        // — files and metadata left untouched — so re-sharing the same gallery is idempotent.
        val isDup = HashMap<String, Boolean>()
        fun duplicate(base: String): Boolean = isDup.getOrPut(base) {
            File(dir, "$base.wav").exists() || File(dir, "$base.flac").exists()
        }

        // Legacy/low-RAM devices skip the analytic-settings JSON (it's platform-finicky and can ask
        // a weak device to do something heavy). They still get the audio, thumbnail, and COMMENT —
        // the only critical metadata. Such recordings just open with this device's default settings.
        val applyMeta = DeviceCaps.tier(ctx) >= 1

        val pendingMeta = ArrayList<Pair<String, String>>()
        var imported = 0
        var skipped = 0

        ZipInputStream(BufferedInputStream(inp)).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                val nm = e.name
                if (nm != "manifest.json" && !e.isDirectory && nm.contains('.')) {
                    val ext = nm.substringAfterLast('.')
                    val base = nm.substringBeforeLast('.')
                    val dup = duplicate(base)
                    when {
                        ext == "wav" || ext == "flac" -> {
                            if (dup) skipped++
                            else {
                                File(dir, "$base.$ext").outputStream().use { zip.copyTo(it) }
                                imported++
                                onImported?.invoke(imported, base)
                            }
                        }
                        // Always sync critical metadata (.txt comments and .png thumbnails), even if
                        // the receiver already has the recording (dup == true).
                        ext == "txt" -> File(dir, "$base.txt").outputStream().use { zip.copyTo(it) }
                        ext == "png" -> File(dir, "$base.png").outputStream().use { zip.copyTo(it) }
                        
                        dup -> { }                                  // already have the recording: skip json
                        ext == "json" -> if (applyMeta) pendingMeta.add(base to zip.readBytes().toString(Charsets.UTF_8))
                        else -> { } // Unknown file types are ignored
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        for ((b, json) in pendingMeta) restoreMeta(ctx, b, json)
        return ImportResult(imported, skipped)
    }

    /** Outcome of [importBundle]: how many recordings were added vs skipped as already-present. */
    data class ImportResult(val imported: Int, val skipped: Int)

    fun restoreMeta(ctx: Context, base: String, jsonText: String) {
        val o = try { JSONObject(jsonText) } catch (_: Exception) { return }
        val ed = ctx.getSharedPreferences(prefsNameForFile("$base.wav"), Context.MODE_PRIVATE).edit()
        for (k in o.keys()) {
            val raw = o.get(k)
            if (raw is String && raw.length >= 2 && raw[1] == ':') {
                val rest = raw.substring(2)
                when (raw[0]) {
                    'b' -> ed.putBoolean(k, rest.toBoolean())
                    'i' -> ed.putInt(k, rest.toIntOrNull() ?: 0)
                    'l' -> ed.putLong(k, rest.toLongOrNull() ?: 0L)
                    'f' -> ed.putFloat(k, rest.toFloatOrNull() ?: 0f)
                    's' -> ed.putString(k, rest)
                }
            } else {
                // Legacy untagged bundle: fall back to key-set heuristics.
                when {
                    k in BOOL_KEYS -> ed.putBoolean(k, o.optBoolean(k))
                    k in STRING_KEYS -> ed.putString(k, o.optString(k))
                    k in FLOAT_KEYS -> ed.putFloat(k, o.optDouble(k).toFloat())
                    else -> ed.putInt(k, o.optInt(k))
                }
            }
        }
        ed.apply()
    }

    // ---- Network helpers ------------------------------------------------------------------------

    /** First site-local IPv4 address (the Wi-Fi LAN address), or null if not on a network. */
    fun localIpv4(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Read a single '\n'-terminated line directly off the raw stream (so the rest stays intact
     *  for the ZIP reader — a BufferedReader would swallow bytes). */
    fun readLine(ins: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}
