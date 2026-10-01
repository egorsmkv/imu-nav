package org.imunav.app.setup

import org.junit.Assert.assertEquals
import org.junit.Test

/** The large routing archive must not be expanded until the user explicitly chooses it. */
class BundledRoutingPreparationTest {
    @Test
    fun freshInstallOffersArchiveWithoutOpeningIt() {
        assertEquals(Preparation.OPTIONAL, bundledRoutingPreparation(enabled = false, loaded = false, removed = false))
    }

    @Test
    fun existingPackRemainsReadyWithoutNewConsent() {
        assertEquals(Preparation.READY, bundledRoutingPreparation(enabled = false, loaded = true, removed = false))
    }

    @Test
    fun explicitOptInAllowsInstallOrUpdate() {
        assertEquals(Preparation.CHECKING, bundledRoutingPreparation(enabled = true, loaded = false, removed = false))
        assertEquals(Preparation.CHECKING, bundledRoutingPreparation(enabled = true, loaded = true, removed = false))
    }

    @Test
    fun removedArchiveStaysRemoved() {
        assertEquals(Preparation.REMOVED, bundledRoutingPreparation(enabled = false, loaded = false, removed = true))
    }
}
