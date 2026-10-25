# MapLibre ships its own consumer rules; keep native-bound classes defensively.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Jackson streaming JSON is used by native request/response adapters.
-keep class com.fasterxml.jackson.core.** { *; }
-keep class org.imunav.routing.RoutingNative { *; }
# JNI resolves these methods by their Java names. Keep the bridge stable in R8 release builds.
-keep class org.imunav.app.nativecore.NativeLogging {
    native <methods>;
}
-keep class org.imunav.app.nativecore.NativeProfiler {
    native <methods>;
}
-keep class org.imunav.app.nativecore.NativeRouteFilter {
    native <methods>;
}
-keep class org.imunav.app.nativecore.NativeRouteGeometry {
    native <methods>;
}
-keep class org.imunav.app.nativecore.NativeNetworkTracker {
    native <methods>;
}
-keep class org.imunav.app.nativecore.NativeSpeedFusion {
    native <methods>;
}
-keep class org.imunav.app.nativecore.NativeNavigationEstimator {
    native <methods>;
}
-keep class org.imunav.app.nativecore.NativeTrustEvaluator {
    native <methods>;
}

# JNI resolves these names from the shared Kotlin search adapter.
-keep class org.imunav.core.search.TantivyNative { *; }
