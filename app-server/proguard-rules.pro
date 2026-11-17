# R8 keep rules for the car receiver app (audit B-04).
#
# Same story as the phone client: isMinifyEnabled=true was running with no
# keep rules at all because this file was missing (AGP warns and continues).
# The car app instantiates the HomeScreen/CarShell Compose tree plus the
# manifest-declared service, so keep the namespace wholesale.

-keep class com.dilinkauto.** { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.dilinkauto.server.MainActivity { *; }
-keep class com.dilinkauto.server.service.CarConnectionService { *; }

-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
