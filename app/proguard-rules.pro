# Aggressive shrinking is fine here — no reflection, no JS-interface objects,
# no third-party libraries to preserve.

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn android.webkit.**

-keep class com.example.fblite.MainActivity { *; }

-dontwarn kotlin.**
-dontwarn kotlinx.**
