package org.imunav.app.setup

/** Explicit asset lifecycle: an idle task is not proof that offline data is usable. */
enum class Preparation { CHECKING, PREPARING, READY, UNAVAILABLE, REMOVED, FAILED }
