# ==============================================================================
# SAFE-HAVEN SECURITY & OBFUSCATION RULES
# ==============================================================================

# Repack classes to a compact namespace for release builds
-repackageclasses 'a'
-allowaccessmodification

# Strip only debug chatter. Info, warning and error lines (tag "MonkMind") are kept,
# so "adb logcat -s MonkMind" can show what Monk Mind did during the test restart.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Hide source file name attributes in release builds
-renamesourcefileattribute SourceFile
-keepattributes SourceFile

# Prevent R8 from warning about internal Android APIs used by the app
-dontwarn android.app.ActivityThread
-dontwarn android.app.ContextImpl
-dontwarn android.app.LoadedApk