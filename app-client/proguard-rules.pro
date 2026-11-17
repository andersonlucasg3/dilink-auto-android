# R8 keep rules for the phone client (audit B-04).
#
# Both release build types used to run with isMinifyEnabled=true while this
# file did not exist — AGP only warns ("Supplied proguard configuration does
# not exist") and then minifies with ZERO keep rules, which is a
# release-only ClassNotFoundException generator: every class reached only by
# name (manifest components, anything reflected into) is a stripping candidate.
#
# The app is small and the protocol classes are plain Kotlin data classes with
# direct call sites, so the safe-and-cheap rule is to keep our own namespace
# wholesale and let the dependency consumer rules (Shizuku/dadb/Compose/
# coroutines) cover theirs.

# Keep all first-party classes and their members.
-keep class com.dilinkauto.** { *; }

# Keep enum plumbing (valueOf/values are reached reflectively by the platform
# and by some Kotlin stdlib paths).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Readable crash reports: keep line numbers, hide the real source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The AccessibilityService / FileProvider / Service components are declared in
# the manifest and instantiated by the system; AGP auto-generates keeps for
# them, but be explicit for the two the app relies on.
-keep class com.dilinkauto.client.service.InputInjectionService { *; }
-keep class com.dilinkauto.client.MainActivity { *; }

# Coroutines: the service internals use Dispatchers/supervisor jobs only; the
# dependency ships its own rules, this is belt-and-braces for debug logging.
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
