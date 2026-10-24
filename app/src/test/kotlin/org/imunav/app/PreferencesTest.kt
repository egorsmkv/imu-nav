package org.imunav.app

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.imunav.app.maps.MapDisplayPreferences
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.ServiceArea
import org.imunav.core.nav.NavigationEstimator
import org.imunav.core.nav.NavigationMethod
import org.imunav.core.route.TravelMode
import org.imunav.core.speed.SpeedProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** User choices survive a restart (a fresh object reads them back) and fall back to safe defaults. */
@RunWith(RobolectricTestRunner::class)
class PreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        listOf("theme", "language", "travel", "speed_profile", "map_display", "map_start").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun themeChoiceOverridesTheNightModeOnlyWhenForced() {
        assertEquals(AppTheme.SYSTEM, AppTheme.get(context))
        assertSame("following the phone keeps the context", context, AppTheme.wrap(context))

        AppTheme.set(context, AppTheme.DARK)
        assertEquals(AppTheme.DARK, AppTheme.get(context))
        assertTrue(AppTheme.isDark(context))
        assertEquals(Configuration.UI_MODE_NIGHT_YES, AppTheme.wrap(context).resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)

        AppTheme.set(context, AppTheme.LIGHT)
        assertFalse(AppTheme.isDark(context))
        assertEquals(Configuration.UI_MODE_NIGHT_NO, AppTheme.wrap(context).resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)

        // A value written by a newer or broken version must not crash the activity.
        context.getSharedPreferences("theme", Context.MODE_PRIVATE).edit().putString("mode", "sepia").commit()
        assertEquals(AppTheme.SYSTEM, AppTheme.get(context))
    }

    @Test
    fun languageChoiceSwitchesResourcesAndVoiceLocale() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.get(context))
        assertEquals("uk", AppLanguage.locale(AppLanguage.UKRAINIAN).language)
        assertEquals("ru", AppLanguage.locale(AppLanguage.RUSSIAN).language)
        assertEquals(Locale.ENGLISH, AppLanguage.locale(AppLanguage.ENGLISH))

        AppLanguage.set(context, AppLanguage.UKRAINIAN)
        assertEquals(AppLanguage.UKRAINIAN, AppLanguage.get(context))
        val wrapped = AppLanguage.wrap(context)
        assertEquals("uk", wrapped.resources.configuration.locales[0].language)
        assertEquals("Тема", wrapped.getString(R.string.theme_title))

        AppLanguage.set(context, AppLanguage.SYSTEM)
        assertSame(context, AppLanguage.wrap(context))
    }

    @Test
    fun navigationChoicesAreRestoredWithDefaultsForUnknownValues() {
        val first = NavigationPreferences(context)
        assertEquals(TravelMode.CAR, first.travelMode.value)
        assertEquals(NavigationMethod.HYBRID, first.navigationMethod.value)
        assertEquals(NavigationEstimator.KOTLIN, first.navigationEstimator.value)
        assertFalse(first.inertialExperiment.value)
        assertTrue(first.tuning.value.terrainMatch)

        first.setTravelMode(TravelMode.FOOT)
        first.setNavigationMethod(NavigationMethod.CELL_TOWERS)
        first.setNavigationEstimator(NavigationEstimator.NATIVE_KALMAN)
        first.setInertialExperiment(true)
        first.setTerrainMatch(false)

        val restored = NavigationPreferences(context)
        assertEquals(TravelMode.FOOT, restored.travelMode.value)
        assertEquals(NavigationMethod.CELL_TOWERS, restored.navigationMethod.value)
        assertEquals(NavigationEstimator.NATIVE_KALMAN, restored.navigationEstimator.value)
        assertTrue(restored.inertialExperiment.value)
        assertFalse(restored.tuning.value.terrainMatch)

        context.getSharedPreferences("travel", Context.MODE_PRIVATE).edit().putString("mode", "BOAT").commit()
        assertEquals(TravelMode.CAR, NavigationPreferences(context).travelMode.value)
    }

    @Test
    fun learnedSpeedProfileIsStoredBetweenTrips() {
        val store = PrefsSpeedProfileStore(context)
        val fresh = store.load()
        assertEquals(0.8, fresh.cityRatio, 1e-6)
        assertEquals(0.8, fresh.highwayRatio, 1e-6)
        assertEquals(0, fresh.cityN + fresh.highwayN)
        store.save(SpeedProfile.State(cityRatio = 0.65, highwayRatio = 0.9, cityN = 12, highwayN = 30))
        val restored = PrefsSpeedProfileStore(context).load()
        assertEquals(0.65, restored.cityRatio, 1e-6)
        assertEquals(0.9, restored.highwayRatio, 1e-6)
        assertEquals(12, restored.cityN)
        assertEquals(30, restored.highwayN)
    }

    @Test
    fun mapDisplaySwitchesAreIndependentAndPersistent() {
        val preferences = MapDisplayPreferences(context)
        assertTrue(preferences.options.value.buildings3d)
        preferences.update { it.copy(buildings3d = false) }
        preferences.update { it.copy(hideLocationOffWarning = true, hideZoomButtons = true) }
        val restored = MapDisplayPreferences(context).options.value
        assertFalse(restored.buildings3d)
        assertTrue(restored.hideLocationOffWarning)
        assertTrue(restored.hideZoomButtons)
        assertFalse(restored.hideTowerButton)
    }

    @Test
    fun mapOpensAtFixedPlaceThenLastTrustedPositionThenWorld() {
        val start = MapStartPrefs(context, ServiceArea.EVERYWHERE)
        assertEquals(MapStartView(MapStartPrefs.WORLD, MapStartPrefs.OVERVIEW_ZOOM, isPosition = false), start.initialView())

        start.rememberTrusted(GeoPoint(50.45, 30.52))
        assertEquals(MapStartView(GeoPoint(50.45, 30.52), MapStartPrefs.POSITION_ZOOM, isPosition = true), start.initialView())
        // A move of a few metres is not worth a disk write.
        start.rememberTrusted(GeoPoint(50.4501, 30.5201))
        assertEquals(GeoPoint(50.45, 30.52), start.lastTrusted)

        start.mode = MapStartMode.FIXED
        start.fixed = GeoPoint(49.84, 24.03)
        assertEquals(MapStartView(GeoPoint(49.84, 24.03), MapStartPrefs.FIXED_ZOOM, isPosition = false), start.initialView())
        start.fixed = null
        assertNull(start.fixed)
        assertEquals(MapStartMode.FIXED, MapStartPrefs(context, ServiceArea.EVERYWHERE).mode)
    }

    @Test
    @Config(sdk = [34])
    fun androidsCachedGpsFixIsUsedUnlessItIsMockOrOutsideTheArea() {
        val application = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        application.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = shadowOf(context.getSystemService(LocationManager::class.java))
        fun cached(lat: Double, lon: Double, mock: Boolean = false) = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            time = System.currentTimeMillis()
            isMock = mock
        }

        locationManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, cached(48.92, 24.71))
        val everywhere = MapStartPrefs(context, ServiceArea.EVERYWHERE)
        assertEquals(MapStartView(GeoPoint(48.92, 24.71), MapStartPrefs.POSITION_ZOOM, isPosition = true), everywhere.initialView())

        // Spoofed positions are exactly what an attacker would plant in Android's cache.
        locationManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, cached(48.92, 24.71, mock = true))
        assertEquals(MapStartPrefs.WORLD, everywhere.initialView().point)

        locationManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, cached(40.7, -74.0))
        assertEquals(MapStartPrefs.WORLD, MapStartPrefs(context, ServiceArea.UKRAINE_COARSE).initialView().point)
    }
}
