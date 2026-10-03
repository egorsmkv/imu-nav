package org.imunav.core.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StartupTransactionTest {
    @Test
    fun everyAcquisitionFailureRollsBackPartialComponentAndEarlierComponents() {
        for (failedAt in 0..5) {
            val released = mutableListOf<Int>()
            assertFailsWith<IllegalStateException> {
                StartupTransaction { throw AssertionError(it) }.use { transaction ->
                    for (index in 0..5) {
                        transaction.acquire({ released.add(index) }) {
                            if (index == failedAt) error("injected startup failure")
                        }
                    }
                    transaction.commit()
                }
            }
            assertEquals((failedAt downTo 0).toList(), released)
        }
    }

    @Test
    fun cleanupFailureDoesNotSkipOtherComponentsAndCloseIsIdempotent() {
        val released = mutableListOf<Int>()
        val failures = mutableListOf<Exception>()
        val transaction = StartupTransaction(failures::add)
        transaction.acquire({ released.add(1) }) {}
        transaction.acquire({ error("close failed") }) {}
        transaction.close()
        transaction.close()
        assertEquals(listOf(1), released)
        assertEquals(1, failures.size)
    }

    @Test
    fun evenFailureReportingHappensAfterEveryCleanup() {
        var released = false
        val transaction = StartupTransaction { error("logger failed") }
        transaction.acquire({ released = true }) {}
        transaction.acquire({ error("release failed") }) {}
        assertFailsWith<IllegalStateException> { transaction.close() }
        assertEquals(true, released)
    }

    @Test
    fun committedStartupRetainsItsResources() {
        var released = false
        StartupTransaction { throw AssertionError(it) }.use {
            it.acquire({ released = true }) {}
            it.commit()
        }
        assertEquals(false, released)
    }
}
