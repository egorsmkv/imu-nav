package org.imunav.app.setup

/** Explicit asset lifecycle: an idle task is not proof that offline data is usable. */
enum class Preparation { CHECKING, PREPARING, READY, OPTIONAL, UNAVAILABLE, REMOVED, FAILED }

/** Skip even asset hashing until the user opts in; preserve already installed towers without reimporting them. */
internal fun bundledCellPreparation(enabled: Boolean, installed: Boolean): Preparation = when {
    enabled -> Preparation.CHECKING
    installed -> Preparation.READY
    else -> Preparation.OPTIONAL
}
