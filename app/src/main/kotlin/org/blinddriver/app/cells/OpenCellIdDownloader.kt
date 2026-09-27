package org.blinddriver.app.cells

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Downloads an OpenCellID per-country export (e.g. MCC 255 = Ukraine) with the user's own API
 * token. Data © OpenCellID contributors, CC BY-SA 4.0.
 */
object OpenCellIdDownloader {
    fun download(token: String, mcc: Int, target: File, onBytes: (Long) -> Unit = {}): File {
        val url = "https://opencellid.org/ocid/downloads?token=${URLEncoder.encode(token.trim(), "UTF-8")}&type=mcc&file=$mcc.csv.gz"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "blind-driver-opensource/0.1")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("OpenCellID HTTP $code")
            val type = conn.contentType.orEmpty()
            if (type.startsWith("text/")) {
                // Errors (bad token, rate limit) come back as a short text/HTML page.
                val msg = conn.inputStream.bufferedReader().use { it.readText() }.take(200)
                throw IOException("OpenCellID: $msg")
            }
            var total = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }
}
