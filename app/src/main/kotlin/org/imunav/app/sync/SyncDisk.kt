package org.imunav.app.sync

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One atomic journal preserves settings, consent choices and acknowledged revisions across kills. */
internal class SyncDisk(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "account-sync.json"))
    fun read(): JSONObject = if (file.baseFile.exists()) file.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } else JSONObject()

    // AtomicFile must roll back even when JSON serialization fails rather than leaving partial data.
    @Suppress("TooGenericExceptionCaught")
    fun write(value: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
    companion object {
        fun profile(server: String, account: Long, identity: String = ""): String =
            MessageDigest.getInstance("SHA-256").digest("$server\n$account\n$identity".toByteArray()).joinToString("") { "%02x".format(it) }
        fun values(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.getString(it) }
    }
}
