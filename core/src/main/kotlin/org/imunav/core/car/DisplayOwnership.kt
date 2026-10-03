package org.imunav.core.car

/** Phone and car lifecycles are independent; neither may stop the other's positioning. */
class DisplayOwnership {
    var phoneVisible = false
        private set
    private val cars = mutableMapOf<String, Boolean>()
    val hasConsumer: Boolean get() = phoneVisible || cars.isNotEmpty()
    val visible: Boolean get() = phoneVisible || cars.values.any { it }

    fun phone(visible: Boolean) {
        phoneVisible = visible
    }
    fun car(id: String, visible: Boolean) {
        cars[id] = visible
    }
    fun disconnect(id: String) {
        cars.remove(id)
    }
    fun needsSensing(navigating: Boolean): Boolean = navigating || visible
}
