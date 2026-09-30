package org.imunav.core.obd

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Talks to an ELM327-compatible OBD-II adapter (the common cheap Bluetooth dongles) over its
 * serial link and reads the car's speed.
 *
 * The protocol is text: send a command ending in `\r`, the adapter answers with one or more lines
 * and then a `>` prompt. `AT…` commands configure the adapter; `010D` asks the engine computer for
 * "vehicle speed" (OBD-II mode 01, PID 0D) and the answer `41 0D 3C` means 0x3C = 60 km/h.
 *
 * Platform-independent: the Android side opens the Bluetooth socket and passes its streams.
 * Blocking; run it on its own thread. Close the streams from another thread to abort a stuck read.
 */
class Elm327(private val input: InputStream, private val output: OutputStream) {
    /** The adapter's identification, e.g. "ELM327 v1.5", after [initialize]. */
    var version: String = ""
        private set

    /**
     * Reset and configure the adapter, then let it find the car's protocol. Throws [ObdException]
     * when the adapter does not answer or no car computer responds (ignition off).
     */
    fun initialize() {
        version = command("ATZ").lines().lastOrNull { it.contains("ELM", ignoreCase = true) }.orEmpty().trim()
        for (setup in SETUP) {
            val answer = command(setup)
            if (!answer.contains("OK", ignoreCase = true)) throw ObdException("adapter rejected $setup: ${answer.oneLine()}")
        }
        // The first real request makes the adapter search for the car's bus protocol (can take seconds).
        val probe = command("0100")
        if (!probe.replace(" ", "").contains("4100")) throw ObdException("no answer from the car: ${probe.oneLine()}")
    }

    /** One speed reading in km/h, or null if the car did not answer this time ("NO DATA"). */
    fun readSpeedKmh(): Int? = parseSpeed(command(SPEED))

    /** Send [cmd] and return everything the adapter printed before its `>` prompt. */
    fun command(cmd: String): String {
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val answer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("adapter closed the connection")
            val c = b.toChar()
            if (c == '>') break
            answer.append(c)
            if (answer.length > MAX_ANSWER) throw ObdException("adapter answer too long")
        }
        // Some adapters echo the command even with ATE0 right after a reset; drop it.
        return answer.toString().replace("\r", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() && it != cmd }.joinToString("\n")
    }

    companion object {
        /** Echo off, line feeds off, spaces off, headers off, automatic protocol. */
        val SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
        const val SPEED = "010D"
        private const val MAX_ANSWER = 4096

        private val SPEED_ANSWER = Regex("41\\s*0D\\s*([0-9A-F]{2})", RegexOption.IGNORE_CASE)

        /**
         * Speed in km/h from a `010D` answer. Several engine computers may answer (one line each);
         * the first valid one wins. Null for NO DATA, errors or garbage.
         */
        fun parseSpeed(answer: String): Int? = SPEED_ANSWER.find(answer)?.groupValues?.get(1)?.toInt(16)

        private fun String.oneLine() = replace('\n', ' ').take(60)
    }
}

/** The adapter or the car did not respond as expected. */
class ObdException(message: String) : IOException(message)
