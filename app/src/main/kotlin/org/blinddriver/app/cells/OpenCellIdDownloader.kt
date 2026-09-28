package org.blinddriver.app.cells

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.blinddriver.core.net.Http
import org.blinddriver.core.net.HttpException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads an OpenCellID per-country export (e.g. MCC 255 = Ukraine) with the user's own API
 * token. Data © OpenCellID contributors, CC BY-SA 4.0.
 *
 * Blocking: call from a background thread.
 */
object OpenCellIdDownloader {
    private const val BASE_URL = "https://opencellid.org/ocid/downloads"
    private const val BUFFER_BYTES = 64 * 1024
    private const val GZIP_MAGIC = 0x1f8b

    /** Exports are big and the server is slow to start sending: allow 2 minutes of silence. */
    private val http = Http.client.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()

    /**
     * Save the export for [mcc] to [target].
     * @param onBytes progress callback with the number of bytes downloaded so far
     * @throws IOException with OpenCellID's own message for bad tokens, rate limits, etc.
     */
    fun download(token: String, mcc: Int, target: File, onBytes: (Long) -> Unit = {}): File {
        // HttpUrl builds the query string and escapes the token for us.
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("token", token.trim())
            .addQueryParameter("type", "mcc")
            .addQueryParameter("file", "$mcc.csv.gz")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "OpenCellID")
            val type = response.body.contentType()?.toString().orEmpty()
            if (type.startsWith("text/") || "json" in type) {
                // Errors (bad token, rate limit) come back with HTTP 200 as JSON,
                // e.g. {"status":"error","message":"INVALID_TOKEN"}.
                val body = response.body.string()
                val message = ERROR_MESSAGE.find(body)?.groupValues?.get(1) ?: body.take(Http.ERROR_BODY_CHARS)
                throw IOException("OpenCellID: $message")
            }
            response.body.byteStream().use { input ->
                target.outputStream().use { output -> copyWithProgress(input, output, onBytes) }
            }
        }
        checkIsGzip(target)
        return target
    }

    /** Copy the stream, reporting the byte count after every chunk. */
    private fun copyWithProgress(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
            onBytes(total)
        }
    }

    /** A real export is gzip; anything else is an error page we should not import. */
    private fun checkIsGzip(file: File) {
        val magic = file.inputStream().use { (it.read() shl 8) or it.read() }
        if (magic == GZIP_MAGIC) return
        val head = file.readText().take(Http.ERROR_BODY_CHARS)
        file.delete()
        throw IOException("OpenCellID returned no data file: $head")
    }

    private val ERROR_MESSAGE = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
}
