# MapLibre rules
-keep class org.maplibre.** { *; }
-keep interface org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Keep ViewModel constructors for ViewModelProvider reflection
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(android.app.Application);
    public <init>();
}

# Keep JavascriptInterface methods for WebViews
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
