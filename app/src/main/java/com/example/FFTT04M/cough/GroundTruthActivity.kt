package com.example.FFTT04M.cough

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
 * Entry point: Gallery → "Gallery tools" menu → "Ground truth review".
 */
class GroundTruthActivity : AppCompatActivity() {

    private data class Row(val cat: GroundTruthBucket.Category, val total: Int, val confirmed: Int)

    private lateinit var container: FrameLayout
    private val dateFmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ground Truth Review"
        container = FrameLayout(this).apply { setBackgroundColor(0xFF111111.toInt()) }
        setContentView(container)
        showSummary()
    }

    // ---- summary screen: counts per bucket -----------------------------------------------------

    private fun showSummary() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val header = TextView(this).apply {
            text = "Buckets clips by ground truth. Coughs are rare — review each. Breath/noise/voice are " +
                "abundant — the codebook has no breathing class yet, so please correct any misfires."
            setTextColor(0xFF999999.toInt()); textSize = 13f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        root.addView(header)
        val status = TextView(this).apply { text = "Scanning recordings…"; setTextColor(0xFFEEEEEE.toInt()); textSize = 14f }
        root.addView(status)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        container.removeAllViews(); container.addView(scroll)

        thread {
            val dir = GalleryTransfer.recordingsDir(this) ?: filesDir
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
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
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

        thread {
            val items = allWavs.filter { GroundTruthBucket.bucketOf(it).category == cat }
                .sortedByDescending { it.lastModified() }
            runOnUiThread { recycler.adapter = ClipListAdapter(items) }
        }
    }

    private inner class ClipListAdapter(private val items: List<File>) : RecyclerView.Adapter<ClipListAdapter.VH>() {
        inner class VH(val root: LinearLayout, val icon: ImageView, val text: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val density = resources.displayMetrics.density
            val icon = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams((56 * density).toInt(), (56 * density).toInt())
            }
            val text = TextView(parent.context).apply {
                setTextColor(0xFFEEEEEE.toInt()); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((12 * density).toInt(), 0, 0, 0)
                maxLines = 3
            }
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(icon); addView(text)
            }
            return VH(row, icon, text)
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
            holder.text.text = "${dateFmt.format(Date(f.lastModified()))}\n${GroundTruthBucket.statusLine(bucket)}\n$raw"
            holder.root.setOnClickListener {
                startActivity(Intent(this@GroundTruthActivity, ViewerActivity::class.java).apply {
                    putExtra("FILE_PATH", f.absolutePath)
                })
            }
        }

        override fun getItemCount() = items.size
    }
}
