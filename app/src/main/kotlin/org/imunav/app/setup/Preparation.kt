package org.imunav.app.setup

/** Explicit asset lifecycle: an idle task is not proof that offline data is usable. */
enum class Preparation { CHECKING, PREPARING, READY, OPTIONAL, UNAVAILABLE, REMOVED, FAILED }

/** Skip even asset hashing until the user opts in; preserve already installed towers without reimporting them. */
internal fun bundledCellPreparation(enabled: Boolean, installed: Boolean): Preparation = when {
    enabled -> Preparation.CHECKING
    installed -> Preparation.READY
    else -> Preparation.OPTIONAL
}

/** Require a deliberate first install while retaining a previous opt-in for interrupted work and updates. */
internal fun bundledRoutingPreparation(enabled: Boolean, loaded: Boolean, removed: Boolean): Preparation = when {
    removed -> Preparation.REMOVED
    enabled -> Preparation.CHECKING
    loaded -> Preparation.READY
    else -> Preparation.OPTIONAL
}
