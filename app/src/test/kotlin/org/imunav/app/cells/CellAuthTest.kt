package org.imunav.app.cells

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.imunav.app.FakeAndroidKeyStore
import org.imunav.core.net.HttpException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Sign-in keeps only an encrypted refresh token on disk and renews access tokens from it. */
@RunWith(RobolectricTestRunner::class)
class CellAuthTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val paths = ArrayList<String>()
    private var refreshFails = false

    private fun session(token: String, expiresInS: Long = 3600) = JSONObject()
        .put("access_token", token).put("refresh_token", "refresh-$token").put("expires_in", expiresInS)
        .put("sync_identity", "identity-1")
        .put("account", JSONObject().put("id", 7).put("email", "driver@example.com").put("email_verified", true).put("sharing_enabled", true))
        .toString()

    @Before
    fun start() {
        FakeAndroidKeyStore.install()
        context.getSharedPreferences("cell_auth", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target
                paths += path
                return when (path) {
                    "/v1/auth/login", "/v1/auth/register" -> MockResponse.Builder().body(session("first", expiresInS = 1)).build()
                    "/v1/auth/refresh" -> if (refreshFails) MockResponse.Builder().code(401).body("expired").build() else MockResponse.Builder().body(session("second")).build()
                    "/v1/auth/logout", "/v1/auth/password-reset/request" -> MockResponse.Builder().body("{}").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private val url get() = server.url("/").toString().trimEnd('/')

    @Test
    fun signInRefreshesExpiredTokensAndSignsOut() {
        val auth = CellAuth(context)
        var identityChanges = 0
        auth.onDiagnosticIdentityChanged = { identityChanges++ }
        auth.authenticate(url, "driver@example.com", "secret", register = false)
        assertEquals("driver@example.com", auth.email)
        assertEquals(7L, auth.accountId)
        assertEquals("identity-1", auth.syncIdentity)
        assertEquals(url, auth.serverUrl)
        assertTrue(identityChanges > 0)
        val owner = checkNotNull(auth.diagnosticOwner())

        // The first token expires within the 30 s safety margin, so it is renewed with the stored refresh token.
        assertEquals("second", auth.accessToken(url))
        assertEquals("second", auth.diagnosticAccessToken(owner))
        assertEquals(owner, auth.ownerForToken(url, "second"))
        assertNull("another server never gets this account's token", auth.accessToken("https://other.example"))

        auth.markUploadBlocked("EMAIL_UNVERIFIED SHARING_DISABLED")
        assertFalse(auth.emailVerified)
        assertFalse(auth.sharingEnabled)

        auth.requestReset(url, "driver@example.com")
        auth.signOut(url)
        assertNull(auth.serverUrl)
        assertTrue(paths.containsAll(listOf("/v1/auth/login", "/v1/auth/refresh", "/v1/auth/password-reset/request", "/v1/auth/logout")))
    }

    @Test
    fun rejectedRefreshEndsTheSessionAndPlainHttpIsRefused() {
        val auth = CellAuth(context)
        auth.authenticate(url, "driver@example.com", "secret", register = true)
        refreshFails = true
        assertThrows(HttpException::class.java) { auth.accessToken(url) }
        assertNull(auth.serverUrl)
        assertThrows(IllegalArgumentException::class.java) { auth.authenticate("http://example.com", "a@b.c", "p", register = false) }
    }
}
