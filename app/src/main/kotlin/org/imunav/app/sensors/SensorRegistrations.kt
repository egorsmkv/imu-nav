package org.imunav.app.sensors

/** Worker-confined registrations: only successful Android calls become cached configuration. */
internal class SensorRegistrations<T>(private val register: (T, Int) -> Boolean, private val unregister: (T) -> Unit, private val failed: (T) -> Unit) {
    private val periods = mutableMapOf<T, Int>()

    /** Preserve successful listeners while retrying failed ones on the next configuration request. */
    fun reconcile(wanted: Map<T, Int>): Boolean {
        var changed = false
        for (sensor in periods.keys.toList()) {
            if (wanted[sensor] != periods[sensor]) {
                unregister(sensor)
                periods.remove(sensor)
                changed = true
            }
        }
        for ((sensor, period) in wanted) {
            if (sensor in periods) continue
            if (register(sensor, period)) {
                periods[sensor] = period
                changed = true
            } else {
                failed(sensor)
            }
        }
        return changed
    }

    fun contains(sensor: T): Boolean = sensor in periods

    /** Add a fallback without retrying failed sibling sensors twice in the same control pass. */
    fun ensure(sensor: T, period: Int): Boolean = reconcile(periods + (sensor to period))
}
