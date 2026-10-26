package org.imunav.app.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.imunav.app.AppGraph
import org.imunav.app.power.PowerMode
import org.imunav.core.cells.Radio
import org.imunav.core.geo.GeoPoint
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.route.TravelMode
import org.imunav.core.sync.SyncEntry
import org.imunav.core.sync.SyncMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Boundary tests use synthetic profile values and normal setters, without network transfers. */
@RunWith(RobolectricTestRunner::class)
class PortablePreferenceTest {
    @Test
    fun everyAllowlistedPreferenceRoundTripsWithItsActualType() {
        val choices = listOf(
            PortablePreference.Language(PortableLanguage.UKRAINIAN), PortablePreference.Voice(false), PortablePreference.Haptics(true),
            PortablePreference.Travel(TravelMode.FOOT), PortablePreference.Method(NavigationMethod.entries.first()),
            PortablePreference.Estimator(NavigationEstimator.NATIVE_KALMAN), PortablePreference.Terrain(false), PortablePreference.Power(PowerMode.SAVER),
            PortablePreference.Screen(true), PortablePreference.OfflineMap(false), PortablePreference.Corridor(true), PortablePreference.OnlineRouting(false),
            PortablePreference.Towers(true), PortablePreference.Radios(setOf(Radio.NR, Radio.GSM)),
            PortablePreference.MapStart(PortableMapStart.Fixed(GeoPoint(50.0, 30.0))),
        )
        val encoded = choices.associate { it.key to PortablePreferenceCodec.encode(it) }
        assertEquals(choices, PortablePreferenceCodec.decode(encoded).map { it.preference })
        assertEquals("[\"GSM\",\"NR\"]", encoded["setting:radios"])
        assertEquals("""{"mode":"FIXED","point":{"lat":50.0,"lon":30.0}}""", encoded["setting:map_start"])
        for (point in listOf(null, GeoPoint(50.0, 30.0))) {
            val choice = PortablePreference.MapStart(PortableMapStart.Gps(point))
            assertEquals(choice, PortablePreferenceCodec.decode(mapOf(choice.key to PortablePreferenceCodec.encode(choice))).single().preference)
        }
    }

    @Test
    fun knownSettingsRejectCoercionMalformedJsonAndInvalidDomains() {
        val invalid = listOf(
            "voice" to "\"false\"", "voice" to "null", "voice" to "1", "voice" to "false,true", "voice" to "false false",
            "language" to "uk", "language" to "\"future\"", "travel_mode" to "\"FLY\"", "navigation_method" to "false",
            "navigation_estimator" to "\"unknown\"", "power_mode" to "\"unknown\"", "radios" to "[\"GSM\",1]",
            "radios" to "[\"future\"]", "radios" to "[", "map_start" to "{}",
            "map_start" to """{"mode":"FIXED","point":null}""",
            "map_start" to """{"mode":"GPS","point":{"lat":91,"lon":30}}""",
            "map_start" to """{"mode":"GPS","point":{"lat":50,"lon":"30"}}""",
            "map_start" to """{"mode":"GPS","point":{"lat":50,"lon":1e999}}""",
            "map_start" to """{"mode":"GPS","mode":"FIXED","point":null}""",
        )
        invalid.forEach { (key, text) ->
            val error = assertThrows(IllegalArgumentException::class.java) { PortablePreferenceCodec.decode(mapOf("setting:$key" to text)) }
            assertEquals("Invalid JSON payload", error.message)
            assertNull(error.cause)
        }
    }

    @Test
    fun invalidSnapshotsApplyNoSettingsAndFutureKeysAreRetained() = withSettings { graph, settings ->
        val before = settings.capture()
        val changedVoice = (!graph.voiceEnabled.value).toString()
        assertThrows(IllegalArgumentException::class.java) {
            settings.apply(settings.decode(linkedMapOf("setting:voice" to changedVoice, "setting:travel_mode" to "\"FLY\"")))
        }
        assertEquals(before, settings.capture())
        val future = mapOf("setting:future" to "{\"new\":true}")
        settings.apply(settings.decode(future))
        val captured = settings.capture(future)
        assertEquals(future.getValue("setting:future"), captured["setting:future"])
        val baseline = captured.map { (key, value) -> SyncEntry("setting", key.substringAfter(':'), 1, value) }
        assertTrue(SyncMerge.changes(settings.capture(captured), baseline, baseline, false).isEmpty())
    }

    @Test
    fun travelModeRunsLastAndUnchangedValuesDoNotRunSetters() = withSettings { graph, settings ->
        val context: Context = ApplicationProvider.getApplicationContext()
        val calls = mutableListOf<String>()
        val voice = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val travel = context.getSharedPreferences("travel", Context.MODE_PRIVATE)
        val voiceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> calls += "voice" }
        val travelListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> calls += "travel" }
        voice.registerOnSharedPreferenceChangeListener(voiceListener)
        travel.registerOnSharedPreferenceChangeListener(travelListener)
        try {
            val mode = if (graph.travelMode.value == TravelMode.CAR) TravelMode.FOOT else TravelMode.CAR
            val incoming = linkedMapOf("setting:travel_mode" to "\"${mode.name}\"", "setting:voice" to (!graph.voiceEnabled.value).toString())
            settings.apply(settings.decode(incoming))
            assertTrue(calls.isNotEmpty())
            assertEquals("voice", calls.first())
            assertEquals("travel", calls.last())
            calls.clear()
            settings.apply(settings.decode(incoming))
            assertTrue(calls.isEmpty())
        } finally {
            voice.unregisterOnSharedPreferenceChangeListener(voiceListener)
            travel.unregisterOnSharedPreferenceChangeListener(travelListener)
        }
    }

    @Test
    fun finalMergeKeepsLocalEditsMadeWhileBookmarkReplacementWasSuspended() = withSettings { graph, settings ->
        val sent = settings.capture()
        val remoteVoice = (!graph.voiceEnabled.value).toString()
        val entries = listOf(SyncEntry("setting", "voice", 1, remoteVoice), SyncEntry("setting", "language", 1, "\"ru\""))
        settings.decode(SyncMerge.apply(entries, sent, sent))
        // Represents the local edit during the bookmark replacement suspension.
        graph.setLanguage("uk")
        settings.apply(settings.decode(SyncMerge.apply(entries, sent, settings.capture())))
        assertEquals("uk", graph.language.value)
        assertEquals(remoteVoice, graph.voiceEnabled.value.toString())
    }

    private fun withSettings(test: (AppGraph, PortableSettings) -> Unit) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val graph = AppGraph(context)
        try {
            test(graph, PortableSettings(graph))
        } finally {
            graph.scope.cancel()
        }
    }
}
