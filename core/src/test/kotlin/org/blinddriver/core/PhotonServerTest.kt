package org.blinddriver.core

import org.blinddriver.core.search.PhotonServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhotonServerTest {
    @Test
    fun acceptsHostsAndFullEndpoints() {
        assertEquals(PhotonServer.DEFAULT_URL, PhotonServer.normalize(""))
        assertEquals(PhotonServer.DEFAULT_URL, PhotonServer.normalize("  https://photon.komoot.io/api/ "))
        assertEquals("https://photon.example.org/api", PhotonServer.normalize("photon.example.org"))
        assertEquals("http://10.0.0.5:2322/api", PhotonServer.normalize("http://10.0.0.5:2322"))
        assertEquals("https://geo.example.org/photon/api", PhotonServer.normalize("https://geo.example.org/photon/"))
    }

    @Test
    fun rejectsNonsense() {
        assertNull(PhotonServer.normalize("ftp://example.org"))
        assertNull(PhotonServer.normalize("https://"))
        assertNull(PhotonServer.normalize("https://example.org/api?q=x"))
        assertNull(PhotonServer.normalize("not a url"))
    }
}
