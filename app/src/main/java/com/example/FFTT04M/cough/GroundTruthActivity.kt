package com.example.FFTT04M.cough

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.FFTT04M.GalleryTransfer
import com.example.FFTT04M.ViewerActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Segregated on-device viewing: buckets every captured clip by [GroundTruthBucket] (Cough / Breath /
 * Voice / Noise / Snore / Sneeze / Other / Unlabeled) so the RARE cough clips can be found and reviewed
 * individually, and the ABUNDANT breath/noise/voice clips — the actual data-collection bottleneck
 * (memory `cough_detection_architecture`: in-domain breath false-positives are the hardest residual, and
 * the on-device codebook has no breathing class yet) — can be browsed or bulk-reviewed per bucket instead
 * of buried in one long undifferentiated gallery list.
 *
 * Also covers the `rejected/` folder (a "Main Gallery" / "Rejected Clips" toggle up top): a confirmed
 * cough can end up auto-rejected if the review that would have protected it (writes a `.txt`, which
 * [com.example.FFTT04M.cough.AutoReject] always honors) happened AFTER the clip was already swept, or if
 * review was left partial and the decoder simply misjudged an un-reviewed clip. The rejected view shows
 * each clip's auto-interpretation ([AutoReject.diagnose] — the decoder label/confidence and, when
 * available, the trained head's P(cough), i.e. exactly the two signals that trigger a reject) alongside
 * its ground-truth status, with a per-clip Restore action plus bulk Restore All / Delete All.
 *
 * Entry point: Gallery → pink "Tools" spinner → "Ground truth review" / "Rejected clips (N)".
 */
class GroundTruthActivity : AppCompatActivity() {

    companion object { const val EX_SOURCE = "SOURCE" }

    private enum class Source { MAIN, REJECTED }
    private data class Row(val cat: GroundTruthBucket.Category, val total: Int, val confirmed: Int)

    private lateinit var container: FrameLayout
    private val dateFmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private var source = Source.MAIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ground Truth Review"
        source = runCatching { Source.valueOf(intent.getStringExtra(EX_SOURCE) ?: "MAIN") }.getOrDefault(Source.MAIN)
        container = FrameLayout(this).apply { setBackgroundColor(0xFF111111.toInt()) }
        setContentView(container)
        showSummary()
    }

    private fun mainDir(): File = GalleryTransfer.recordingsDir(this) ?: filesDir
    private fun sourceDir(): File = if (source == Source.MAIN) mainDir() else AutoReject.rejectedDir(mainDir())

    // ---- summary screen: Main/Rejected toggle + counts per bucket --------------------------------

    private fun showSummary() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val toggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, (8 * density).toInt()) }
        fun toggleButton(label: String, s: Source): Button = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (source == s) 0xFFFF69B4.toInt() else 0xFF444444.toInt())
            setTextColor(if (source == s) 0xFF000000.toInt() else 0xFFEEEEEE.toInt())
            setOnClickListener { source = s; showSummary() }
        }
        toggle.addView(toggleButton("Main Gallery", Source.MAIN))
        toggle.addView(toggleButton("Rejected Clips", Source.REJECTED))
        root.addView(toggle)

        if (source == Source.REJECTED) {
            val bulk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, (8 * density).toInt()) }
            bulk.addView(Button(this).apply {
                text = "Restore All"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    AlertDialog.Builder(this@GroundTruthActivity)
                        .setTitle("Restore all rejected clips?")
                        .setMessage("Moves every clip in rejected/ back to the main gallery.")
                        .setPositiveButton("Restore all") { _, _ ->
                            thread {
                                val n = AutoReject.restoreAll(mainDir())
                                runOnUiThread { Toast.makeText(this@GroundTruthActivity, "Restored $n clip(s)", Toast.LENGTH_LONG).show(); showSummary() }
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            bulk.addView(Button(this).apply {
                text = "Delete All"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    AlertDialog.Builder(this@GroundTruthActivity)
                        .setTitle("Permanently delete all rejected clips?")
                        .setMessage("This cannot be undone.")
                        .setPositiveButton("Delete all") { _, _ ->
                            thread {
                                val n = AutoReject.deleteAll(mainDir())
                                runOnUiThread { Toast.makeText(this@GroundTruthActivity, "Deleted $n clip(s)", Toast.LENGTH_LONG).show(); showSummary() }
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            root.addView(bulk)
        }

        val header = TextView(this).apply {
            text = if (source == Source.MAIN)
                "Buckets clips by ground truth. Coughs are rare — review each. Breath/noise/voice are " +
                    "abundant — the codebook has no breathing class yet, so please correct any misfires."
            else
                "Clips auto-rejected as noise/voice. Each shows WHY it was flagged (decoder guess + the " +
                    "trained head's P(cough)) — check for any confirmed cough that got swept in by mistake."
            setTextColor(0xFF999999.toInt()); textSize = 13f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        root.addView(header)
        val status = TextView(this).apply { text = "Scanning recordings…"; setTextColor(0xFFEEEEEE.toInt()); textSize = 14f }
        root.addView(status)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        container.removeAllViews(); container.addView(scroll)

        thread {
            val dir = sourceDir()
            val wavs = dir.listFiles { f -> f.isFile && (f.extension.equals("wav", true) || f.extension.equals("flac", true)) }
                ?.toList() ?: emptyList()
            val byCat = wavs.groupBy { GroundTruthBucket.bucketOf(it) }
            val rows = GroundTruthBucket.Category.values().map { cat ->
                val bucketed = byCat.filterKeys { it.category == cat }
                val total = bucketed.values.sumOf { it.size }
                val confirmed = bucketed.entries.filter { it.key.confirmed }.sumOf { it.value.size }
                Row(cat, total, confirmed)
            }
            runOnUiThread {
                status.visibility = View.GONE
                for (r in rows) root.addView(buildSummaryRow(r, wavs))
            }
        }
    }

    private fun buildSummaryRow(r: Row, allWavs: List<File>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, (10 * resources.displayMetrics.density).toInt())
        }
        val label = TextView(this).apply {
            text = "${GroundTruthBucket.displayName(r.cat)}: ${r.total} (${r.confirmed} confirmed, ${r.total - r.confirmed} unconfirmed)"
            setTextColor(if (r.total - r.confirmed > 0) 0xFFFFD54F.toInt() else 0xFFEEEEEE.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label)
        row.addView(Button(this).apply {
            text = "View"
            isEnabled = r.total > 0
            setOnClickListener { showList(r.cat, allWavs) }
        })
        row.addView(Button(this).apply {
            text = "Review"
            isEnabled = r.total - r.confirmed > 0
            setOnClickListener {
                startActivity(Intent(this@GroundTruthActivity, ReviewActivity::class.java).apply {
                    putExtra(ReviewActivity.EX_CATEGORY, r.cat.name)
                    putExtra(ReviewActivity.EX_ONLY_UNCONFIRMED, true)
                    putExtra(ReviewActivity.EX_SOURCE, source.name)
                })
            }
        })
        return row
    }

    // ---- list screen: every clip in one bucket, tap to open in the Viewer -----------------------

    private fun showList(cat: GroundTruthBucket.Category, allWavs: List<File>) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, 0)
        }
        topBar.addView(Button(this).apply { text = "← Back"; setOnClickListener { showSummary() } })
        topBar.addView(TextView(this).apply {
            text = "  ${GroundTruthBucket.displayName(cat)}"
            setTextColor(0xFFEEEEEE.toInt()); textSize = 16f; gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(topBar)

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@GroundTruthActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(recycler)
        container.removeAllViews(); container.addView(root)

        val rejectedMode = source == Source.REJECTED
        thread {
            val items = allWavs.filter { GroundTruthBucket.bucketOf(it).category == cat }
                .sortedByDescending { it.lastModified() }
            // Diagnose (decoder label/confidence + head P(cough)) only makes sense for rejected clips —
            // it's cheap when a .phon is already cached, but may decode fresh for older sweep-rejected
            // clips that predate the annotate() fix, so it's computed off the UI thread.
            val diag = if (rejectedMode) items.associateWith { AutoReject.diagnose(this, it) } else emptyMap()
            runOnUiThread { recycler.adapter = ClipListAdapter(items.toMutableList(), diag) }
        }
    }

    private inner class ClipListAdapter(
        private val items: MutableList<File>,
        private val diag: Map<File, String>,
    ) : RecyclerView.Adapter<ClipListAdapter.VH>() {
        inner class VH(val root: LinearLayout, val icon: ImageView, val text: TextView, val restore: Button) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val density = resources.displayMetrics.density
            val icon = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams((56 * density).toInt(), (56 * density).toInt())
            }
            val textView = TextView(parent.context).apply {
                setTextColor(0xFFEEEEEE.toInt()); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((12 * density).toInt(), 0, 0, 0)
                maxLines = 4
            }
            val restore = Button(parent.context).apply {
                text = "Restore"
                visibility = if (source == Source.REJECTED) View.VISIBLE else View.GONE
            }
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(icon); addView(textView); addView(restore)
            }
            return VH(row, icon, textView, restore)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            val png = File(f.parentFile, "${f.nameWithoutExtension}.png")
            if (png.isFile) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                holder.icon.setImageBitmap(BitmapFactory.decodeFile(png.absolutePath, opts))
            } else holder.icon.setImageResource(android.R.drawable.ic_menu_report_image)
            val bucket = GroundTruthBucket.bucketOf(f)
            val raw = if (bucket.confirmed) "confirmed: ${bucket.sourceText}" else "predicted: ${bucket.sourceText.ifBlank { "?" }}"
            val diagLine = diag[f]?.let { "\nauto: $it" } ?: ""
            holder.text.text = "${dateFmt.format(Date(f.lastModified()))}\n${GroundTruthBucket.statusLine(bucket)}\n$raw$diagLine"
            holder.root.setOnClickListener {
                startActivity(Intent(this@GroundTruthActivity, ViewerActivity::class.java).apply {
                    putExtra("FILE_PATH", f.absolutePath)
                })
            }
            holder.restore.setOnClickListener {
                thread {
                    val ok = AutoReject.restoreOne(mainDir(), f)
                    runOnUiThread {
                        if (ok) {
                            val pos = items.indexOf(f)
                            if (pos >= 0) { items.removeAt(pos); notifyItemRemoved(pos) }
                            Toast.makeText(this@GroundTruthActivity, "Restored ${f.name}", Toast.LENGTH_SHORT).show()
                        } else Toast.makeText(this@GroundTruthActivity, "Restore failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
