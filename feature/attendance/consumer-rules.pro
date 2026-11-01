# Preserve Retrofit and network DTO models from R8 / ProGuard obfuscation and field stripping
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class com.college.feature.attendance.network.** { *; }
-keep class com.college.feature.attendance.telemetry.** { *; }

# Keep CameraX analyzer interfaces and callbacks intact
-keep class androidx.camera.core.** { *; }
