package org.imunav.core

import org.imunav.core.obd.Elm327
import org.imunav.core.obd.ObdException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A pretend ELM327 adapter: answers each command (ended by `\r`) and prints the `>` prompt. */
private class FakeAdapter(private val answer: (String) -> String) {
    private val pending = ArrayDeque<Int>()
    private val line = StringBuilder()
    val received = ArrayList<String>()

    val output = object : OutputStream() {
        override fun write(b: Int) {
            if (b.toChar() == '\r') {
                val cmd = line.toString()
                line.clear()
                received += cmd
                "${answer(cmd)}\r\r>".forEach { pending.addLast(it.code) }
            } else {
                line.append(b.toChar())
            }
        }
    }

    val input = object : InputStream() {
        override fun read(): Int = pending.removeFirstOrNull() ?: -1
    }
}

class Elm327Test {
    private fun car(speedKmh: () -> String) = FakeAdapter { cmd ->
        when (cmd) {
            "ATZ" -> "ATZ\r\rELM327 v1.5"
            "0100" -> "SEARCHING...\r4100BE3FA813"
            "010D" -> speedKmh()
            else -> "OK"
        }
    }

    @Test
    fun initializesAndReadsSpeed() {
        var answer = "410D3C"
        val adapter = car { answer }
        val elm = Elm327(adapter.input, adapter.output)
        elm.initialize()
        assertEquals("ELM327 v1.5", elm.version)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100"), adapter.received)
        assertEquals(60, elm.readSpeedKmh())
        answer = "NO DATA"
        assertNull(elm.readSpeedKmh())
        answer = "410D00"
        assertEquals(0, elm.readSpeedKmh())
    }

    @Test
    fun parsesSpacedAndMultiEcuAnswers() {
        assertEquals(100, Elm327.parseSpeed("41 0D 64"))
        // Two engine computers answering the same request.
        assertEquals(88, Elm327.parseSpeed("410D58\n410D58"))
        assertNull(Elm327.parseSpeed("CAN ERROR"))
        assertNull(Elm327.parseSpeed("?"))
    }

    @Test
    fun ignitionOffIsReported() {
        val adapter = FakeAdapter { cmd -> if (cmd == "0100") "SEARCHING...\rUNABLE TO CONNECT" else "OK" }
        assertFailsWith<ObdException> { Elm327(adapter.input, adapter.output).initialize() }
    }
}
