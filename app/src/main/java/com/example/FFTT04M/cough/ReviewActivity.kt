package com.example.FFTT04M.cough

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.FFTT04M.GalleryTransfer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Rapid on-device ground-truth confirmation: the ACCELERATOR for the data-collection gap the desktop
 * investigation surfaced (2026-07-10) — very few true coughs vs. very many breath/noise/voice captures,
 * and the on-device codebook has no "breathing" class yet, so those clips need manual correction to
 * become usable labelled data. Shows one clip at a time from the chosen bucket ([GroundTruthBucket]),
 * plays it on tap, and offers big one-tap buttons to set ground truth — auto-advancing so a large
 * homogeneous batch (e.g. a long stretch of breathing captures) can be worked through fast. A bulk
 * "confirm the rest as X" action is also offered for a single-category queue once the pattern is clear.
 *
 * Intent extras: CATEGORY (name of a [GroundTruthBucket.Category], or "ALL"); ONLY_UNCONFIRMED (boolean,
 * default true — review clips that don't yet have a manual comment; set false to re-review everything).
 */
class ReviewActivity : AppCompatActivity() {

    companion object {
        const val EX_CATEGORY = "CATEGORY"
        const val EX_ONLY_UNCONFIRMED = "ONLY_UNCONFIRMED"
        const val CATEGORY_ALL = "ALL"
    }

    private val dateFmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private var queue: List<File> = emptyList()
    private var pos = 0
    private var reviewedThisSession = 0
    private var player: MediaPlayer? = null
    private var filterCategory: GroundTruthBucket.Category? = null

    // Views
    private lateinit var root: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var filenameText: TextView
    private lateinit var hintText: TextView
    private lateinit var icon: ImageView
    private lateinit var bulkButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catName = intent.getStringExtra(EX_CATEGORY) ?: CATEGORY_ALL
        filterCategory = runCatching { GroundTruthBucket.Category.valueOf(catName) }.getOrNull()
        val onlyUnconfirmed = intent.getBooleanExtra(EX_ONLY_UNCONFIRMED, true)
        title = "Review: ${filterCategory?.let { GroundTruthBucket.displayName(it) } ?: "All"}"

        buildLayout()
        setContentView(root)
        progressText.text = "Scanning recordings…"

        thread {
            val dir = GalleryTransfer.recordingsDir(this) ?: filesDir
            val wavs = dir.listFiles { f -> f.isFile && (f.extension.equals("wav", true) || f.extension.equals("flac", true)) }
                ?.toList() ?: emptyList()
            val filtered = wavs.filter { wav ->
                val b = GroundTruthBucket.bucketOf(wav)
                (filterCategory == null || b.category == filterCategory) && (!onlyUnconfirmed || !b.confirmed)
            }.sortedBy { it.lastModified() }   // oldest backlog first
            runOnUiThread {
                queue = filtered
                pos = 0
                if (queue.isEmpty()) {
                    progressText.text = "Nothing to review in this bucket."
                    hintText.text = ""
                } else {
                    showCurrent()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
    }

    private fun buildLayout() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            setPadding(pad, pad, pad, pad)
        }

        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        progressText = TextView(this).apply {
            setTextColor(0xFFEEEEEE.toInt()); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val doneBtn = Button(this).apply { text = "Done"; setOnClickListener { finish() } }
        topBar.addView(progressText); topBar.addView(doneBtn)
        root.addView(topBar)

        icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((160 * density).toInt(), (160 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = (12 * density).toInt(); bottomMargin = (12 * density).toInt()
            }
            adjustViewBounds = true
        }
        root.addView(icon)

        filenameText = TextView(this).apply {
            setTextColor(0xFFEEEEEE.toInt()); textSize = 13f; gravity = Gravity.CENTER
        }
        root.addView(filenameText)

        hintText = TextView(this).apply {
            setTextColor(0xFF999999.toInt()); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
        }
        root.addView(hintText)

        val playBtn = Button(this).apply {
            text = "▶ Play"
            setOnClickListener { playCurrent() }
        }
        root.addView(playBtn)

        val grid = GridLayout(this).apply {
            columnCount = 2
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        for ((cat, label) in GroundTruthBucket.QUICK_LABELS) {
            grid.addView(Button(this).apply {
                text = GroundTruthBucket.displayName(cat)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                }
                setOnClickListener { confirmCurrent(label) }
            })
        }
        root.addView(grid)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * density).toInt(), 0, 0) }
        row2.addView(Button(this).apply {
            text = "Skip"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { advance() }
        })
        row2.addView(Button(this).apply {
            text = "Custom…"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showCustomLabelDialog() }
        })
        root.addView(row2)

        bulkButton = Button(this).apply {
            visibility = View.GONE
            setPadding(0, (16 * density).toInt(), 0, 0)
            setOnClickListener { confirmBulkRemaining() }
        }
        root.addView(bulkButton)
    }

    private fun currentFile(): File? = queue.getOrNull(pos)

    private fun showCurrent() {
        val f = currentFile()
        if (f == null) {
            progressText.text = "All done — reviewed $reviewedThisSession this session."
            filenameText.text = ""; hintText.text = ""; icon.setImageDrawable(null)
            bulkButton.visibility = View.GONE
            return
        }
        progressText.text = "${pos + 1} of ${queue.size} (reviewed $reviewedThisSession)"
        filenameText.text = dateFmt.format(Date(f.lastModified()))
        val bucket = GroundTruthBucket.bucketOf(f)
        hintText.text = if (bucket.sourceText.isNotBlank())
            "on-device guess: ${bucket.sourceText}" else "no on-device guess yet"
        val png = File(f.parentFile, "${f.nameWithoutExtension}.png")
        if (png.isFile) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            icon.setImageBitmap(BitmapFactory.decodeFile(png.absolutePath, opts))
        } else icon.setImageResource(android.R.drawable.ic_menu_report_image)

        val cat = filterCategory
        if (cat != null && queue.size - pos > 1) {
            bulkButton.visibility = View.VISIBLE
            bulkButton.text = "Confirm remaining ${queue.size - pos} as ${GroundTruthBucket.displayName(cat)}"
        } else bulkButton.visibility = View.GONE
    }

    private fun playCurrent() {
        val f = currentFile() ?: return
        player?.release(); player = null
        try {
            player = MediaPlayer.create(this, Uri.fromFile(f))?.apply {
                setOnCompletionListener { it.release(); if (player === it) player = null }
                start()
            }
            if (player == null) Toast.makeText(this, "Couldn't play this clip", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Playback failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmCurrent(label: String) {
        val f = currentFile() ?: return
        GroundTruthBucket.setGroundTruth(f, label)
        reviewedThisSession++
        advance()
    }

    private fun advance() {
        player?.release(); player = null
        pos++
        showCurrent()
    }

    private fun showCustomLabelDialog() {
        val f = currentFile() ?: return
        val input = EditText(this).apply { hint = "Custom label for ${f.name}" }
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = ScrollView(this).apply {
            addView(LinearLayout(this@ReviewActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Custom label")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) confirmCurrent(text) else advance()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmBulkRemaining() {
        val cat = filterCategory ?: return
        val remaining = queue.subList(pos, queue.size)
        val label = GroundTruthBucket.QUICK_LABELS.firstOrNull { it.first == cat }?.second
            ?: GroundTruthBucket.displayName(cat).lowercase()
        AlertDialog.Builder(this)
            .setTitle("Bulk-confirm ${remaining.size} clip(s)?")
            .setMessage("Sets ALL ${remaining.size} remaining clip(s) in this bucket to \"$label\". " +
                "Only do this once you've spot-checked that this batch really is uniform — each clip's " +
                "label stays editable afterward from its own comment dialog.")
            .setPositiveButton("Confirm all") { _, _ ->
                thread {
                    for (f in remaining) GroundTruthBucket.setGroundTruth(f, label)
                    runOnUiThread {
                        reviewedThisSession += remaining.size
                        pos = queue.size
                        showCurrent()
                        Toast.makeText(this, "Confirmed ${remaining.size} clip(s) as $label", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
