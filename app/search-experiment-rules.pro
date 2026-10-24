# Only opt-in benchmark builds: instrumentation references APIs that R8 otherwise removes from the app.
# Keep callable surfaces and names while allowing method-body optimization.
-keep,allowoptimization class androidx.tracing.** { *; }
-keep,allowoptimization class kotlin.** { *; }
-keep,allowoptimization class org.imunav.core.geo.** { *; }
-keep,allowoptimization class org.imunav.core.search.** { *; }
-keep,allowoptimization class org.imunav.app.search.AndroidSearchDb { *; }

-keep,allowoptimization class org.imunav.app.search.PackSearch { *; }
-keep,allowoptimization class org.imunav.app.search.PackSearch$Companion { *; }
-keep,allowoptimization class org.imunav.app.routing.OfflineRouting { *; }
-keep,allowoptimization class org.imunav.app.routing.OfflineRoutingStatus { *; }
-keep,allowoptimization class org.imunav.app.setup.Preparation { *; }
# The install test drives coroutine APIs whose shared runtime entry points R8 otherwise inlines away.
-keep,allowoptimization class kotlinx.coroutines.** { *; }
