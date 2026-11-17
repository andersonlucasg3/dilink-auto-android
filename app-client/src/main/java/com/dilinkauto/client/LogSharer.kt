package com.dilinkauto.client

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zips the client log files and hands them to the system share sheet
 * (audit R3-SRP-09: extracted from MainActivity, which was doing routing +
 * service control + permission intents + log sharing all at once).
 */
object LogSharer {

    /**
     * Build the log zip on a background thread, then share it.
     * Pass an Activity context — the chooser and the fallback toast expect a
     * foreground context (this is called straight from the UI).
     */
    fun share(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val zipFile = FileLog.zipLogs()
            withContext(Dispatchers.Main) {
                if (zipFile != null) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        zipFile
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.share_logs_title))
                    )
                } else {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.share_logs_no_logs),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
