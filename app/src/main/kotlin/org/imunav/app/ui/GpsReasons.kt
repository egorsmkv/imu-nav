package org.imunav.app.ui

import android.content.res.Resources
import org.imunav.app.R

/** Explains native and Kotlin classifier codes in the UI while recordings retain their stable codes. */
internal fun gpsReasonLabel(resources: Resources, reason: String): String = resources.getString(
    when (reason.substringBefore('=')) {
        "invalid" -> R.string.gps_reason_invalid
        "mock" -> R.string.gps_reason_mock
        "outside_area" -> R.string.gps_reason_outside_area
        "altitude", "alt" -> R.string.gps_reason_altitude
        "speed" -> R.string.gps_reason_speed
        "accuracy", "acc" -> R.string.gps_reason_accuracy
        "acc_jump" -> R.string.gps_reason_accuracy_jump
        "clock_skew" -> R.string.gps_reason_clock_skew
        "duplicate_time", "dup_time" -> R.string.gps_reason_duplicate_time
        "jump" -> R.string.gps_reason_jump
        "speed_mismatch" -> R.string.gps_reason_speed_mismatch
        "frozen" -> R.string.gps_reason_frozen
        "network_difference", "net_diff" -> R.string.gps_reason_network_difference
        "jam" -> R.string.gps_reason_jam
        "jam_weak" -> R.string.gps_reason_jam_weak
        "jam_strong" -> R.string.gps_reason_jam_strong
        "no_satellites", "no_sats" -> R.string.gps_reason_no_satellites
        "few_satellites", "sats" -> R.string.gps_reason_few_satellites
        "weak_signal", "cn0" -> R.string.gps_reason_weak_signal
        "flat_signal", "cn0_flat" -> R.string.gps_reason_flat_signal
        "heading_difference", "heading_diff" -> R.string.gps_reason_heading_difference
        else -> R.string.gps_reason_unknown
    },
)
