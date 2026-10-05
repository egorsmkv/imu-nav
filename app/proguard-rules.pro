# MapLibre ships its own consumer rules; keep native-bound classes defensively.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# GraphHopper (offline routing): encoded values and storage are (de)serialized reflectively via Jackson.
-keep class com.graphhopper.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.carrotsearch.hppc.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.graphhopper.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.codehaus.janino.**
-dontwarn org.codehaus.commons.compiler.**
-dontwarn org.locationtech.jts.**
-dontwarn org.openstreetmap.osmosis.**
-dontwarn com.google.protobuf.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn de.westnordost.**
-dontwarn org.xmlpull.**

# Referenced only by GraphHopper OSM import (desktop builder), never on the phone.
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
# JNI resolves these methods by their Java names. Keep the bridge stable in R8 release builds.
-keep class org.imunav.app.nativecore.NativeLogging {
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
