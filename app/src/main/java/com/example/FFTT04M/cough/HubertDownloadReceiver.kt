package com.example.FFTT04M.cough

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives DownloadManager's completion broadcast for the HuBERT model and verifies+installs it off the
 *  main thread (sha256 of 361 MB would ANR otherwise). Registered in the manifest (system broadcast). */
class HubertDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try { HubertModelManager.onDownloadComplete(app, id) } finally { pending.finish() }
        }.start()
    }
}
