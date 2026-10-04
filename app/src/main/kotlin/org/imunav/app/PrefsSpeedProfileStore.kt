package org.imunav.app

import android.content.Context
import androidx.core.content.edit
import org.imunav.core.speed.SpeedProfile
import org.imunav.core.speed.SpeedProfileStore

/** Keeps the learned driving-speed profile in SharedPreferences. */
internal class PrefsSpeedProfileStore(context: Context) : SpeedProfileStore {
    private val prefs = context.getSharedPreferences("speed_profile", Context.MODE_PRIVATE)

    override fun load() = SpeedProfile.State(
        cityRatio = prefs.getFloat("city_ratio", 0.8f).toDouble(),
        highwayRatio = prefs.getFloat("hwy_ratio", 0.8f).toDouble(),
        cityN = prefs.getInt("city_n", 0),
        highwayN = prefs.getInt("hwy_n", 0),
    )

    override fun save(state: SpeedProfile.State) {
        prefs.edit {
            putFloat("city_ratio", state.cityRatio.toFloat())
            putFloat("hwy_ratio", state.highwayRatio.toFloat())
            putInt("city_n", state.cityN)
            putInt("hwy_n", state.highwayN)
        }
    }
}
