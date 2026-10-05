package org.imunav.app.trips

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.imunav.core.record.TripExport
import java.io.File

/** Prepare the attachment and provider URI off the UI thread; the caller opens Android's chooser. */
internal suspend fun tripShareIntent(context: Context, recording: File): Intent = withContext(Dispatchers.IO) {
    val file = TripExport.snapshot(recording, File(context.cacheDir, "trip-shares"))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
