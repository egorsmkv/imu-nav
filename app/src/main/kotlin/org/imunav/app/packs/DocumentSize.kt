package org.imunav.app.packs

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Size in bytes of a document the user picked, or 0 when its provider does not report one (the
 * install then shows megabytes without a percentage). A binder call: run it on an I/O thread.
 */
fun ContentResolver.documentSize(uri: Uri): Long = runCatching {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
    } ?: 0L
}.getOrDefault(0L)
