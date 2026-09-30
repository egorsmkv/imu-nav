package org.imunav.app.setup

import org.junit.Assert.assertEquals
import org.junit.Test

/** The optional archive must not be checked or reinstalled on the server-only path. */
class BundledCellPreparationTest {
    @Test
    fun freshInstallSkipsBundledArchive() {
        assertEquals(Preparation.OPTIONAL, bundledCellPreparation(enabled = false, installed = false))
    }

    @Test
    fun existingDatabaseIsPreservedWithoutOptIn() {
        assertEquals(Preparation.READY, bundledCellPreparation(enabled = false, installed = true))
    }

    @Test
    fun optedInArchiveIsCheckedForInstallAndUpdates() {
        assertEquals(Preparation.CHECKING, bundledCellPreparation(enabled = true, installed = false))
        assertEquals(Preparation.CHECKING, bundledCellPreparation(enabled = true, installed = true))
    }
}
