# The page reaches the app through @JavascriptInterface methods, which are
# called by name from JavaScript and so are invisible to the shrinker.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.puzzleskills.panner.MainActivity$Bridge { *; }

-keepattributes JavascriptInterface
-keepattributes *Annotation*
