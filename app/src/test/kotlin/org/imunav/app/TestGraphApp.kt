package org.imunav.app

import android.app.Application

/**
 * A host-test Application that owns a real [AppGraph], for components that reach it through
 * `context.graph` (services, receivers). Unlike [ImuNavApp] it does not start MapLibre, whose
 * native renderer is not built for the host.
 */
class TestGraphApp :
    Application(),
    GraphOwner {
    override val graph: AppGraph by lazy { AppGraph(this) }
}
