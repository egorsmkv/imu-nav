package org.imunav.app.trips

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.imunav.app.R
import org.imunav.core.record.TripExport
import java.io.File

/** Prepare the attachment and provider URI off the UI thread; the caller opens Android's chooser. */
internal suspend fun tripShareIntent(context: Context, recording: File, partial: Boolean = false): Intent = withContext(Dispatchers.IO) {
    val file = TripExport.snapshot(recording, File(context.cacheDir, "trip-shares"), if (partial) context.getString(R.string.recording_partial) else null)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (partial) putExtra(Intent.EXTRA_TEXT, context.getString(R.string.recording_partial))
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
