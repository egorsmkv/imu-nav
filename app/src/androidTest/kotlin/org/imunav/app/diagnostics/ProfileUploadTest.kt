package org.imunav.app.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.imunav.core.net.HttpException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real HTTP and Android ZIPs verify the streamed request, retained local file and cancellation. */
@RunWith(AndroidJUnit4::class)
class ProfileUploadTest {
    @Test
    fun uploadStreamsTheOriginalZipAndKeepsItAfterSuccessOrRejection() = runBlocking {
        val archive = archive()
        try {
            for (status in listOf(200, 413)) {
                val worker = Executors.newSingleThreadExecutor()
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    val received = worker.submit<ByteArray> {
                        server.accept().use { socket ->
                            val body = readUpload(socket)
                            val response = if (status == 200) "{\"id\":\"synthetic-profile\"}" else "{}"
                            socket.getOutputStream().write("HTTP/1.1 $status Result\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response".toByteArray())
                            body
                        }
                    }
                    try {
                        val client = ProfileUploadClient("http://127.0.0.1:${server.localPort}", "synthetic-token")
                        if (status == 200) {
                            assertEquals("synthetic-profile", client.upload(archive, archive.length()))
                        } else {
                            try {
                                client.upload(archive, archive.length())
                                error("Expected quota rejection")
                            } catch (error: HttpException) {
                                assertEquals(413, error.code)
                            }
                        }
                        assertArrayEquals(archive.readBytes(), received.get(10, TimeUnit.SECONDS))
                        assertTrue(archive.exists())
                    } finally {
                        worker.shutdownNow()
                    }
                }
            }
        } finally {
            archive.delete()
        }
    }

    @Test
    fun cancellingUploadClosesTheNetworkCallAndKeepsTheZip() = runBlocking {
        val archive = archive()
        val worker = Executors.newSingleThreadExecutor()
        val accepted = CompletableDeferred<Unit>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val closed = worker.submit<Boolean> {
                server.accept().use { socket ->
                    readUpload(socket)
                    accepted.complete(Unit)
                    socket.getInputStream().read() == -1
                }
            }
            val upload = launch { ProfileUploadClient("http://127.0.0.1:${server.localPort}", "synthetic-token").upload(archive, archive.length()) }
            try {
                withTimeout(10_000) {
                    accepted.await()
                    upload.cancelAndJoin()
                }
                assertTrue(closed.get(10, TimeUnit.SECONDS))
                assertTrue(archive.exists())
            } finally {
                upload.cancelAndJoin()
                worker.shutdownNow()
                archive.delete()
            }
        }
    }

    /** Read exactly the request bytes so cancellation can subsequently observe a closed socket. */
    private fun readUpload(socket: Socket): ByteArray {
        socket.soTimeout = 10_000
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0 && header.length < 8192)
            header.append(byte.toChar())
        }
        assertTrue(header.startsWith("POST /v1/debug/profiles HTTP/1.1"))
        assertTrue(header.contains("Authorization: Bearer synthetic-token"))
        assertTrue(header.contains("Content-Type: application/zip"))
        val size = header.lines().first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
        return ByteArray(size).also { bytes ->
            var offset = 0
            while (offset < size) {
                val read = input.read(bytes, offset, size - offset)
                check(read > 0)
                offset += read
            }
        }
    }

    private fun archive(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File.createTempFile("synthetic-profile-", ".zip", context.cacheDir).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{\"schema\":2,\"interrupted\":false}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("memory.csv"))
                zip.write("elapsed_ms\n1\n".toByteArray())
                zip.closeEntry()
            }
        }
    }
}
