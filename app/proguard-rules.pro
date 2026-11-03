# Comprehensive ProGuard Rules for Ble Attendance App

# General reflection attributes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Keep generic signatures of Retrofit Call and Response
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Keep Kotlin continuation generic signatures used by suspend functions
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Keep JNI native methods (CameraX / Audio / OpenCV)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Data Transfer Objects (DTOs) & Domain Models (Gson serialization / deserialization)
-keep class com.college.core.model.** { *; }
-keep class com.college.core.network.** { *; }
-keep class com.college.feature.attendance.network.** { *; }
-keep class com.college.feature.attendance.telemetry.** { *; }
-keep class com.college.feature.library.model.** { *; }
-keep class com.college.feature.library.network.** { *; }

# Keep all serialized fields
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit service interfaces & HTTP annotations
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}
-keep interface com.college.core.network.service.AuthApiService { *; }
-keep interface com.college.feature.attendance.network.AttendanceApiService { *; }
-keep interface com.college.feature.library.network.LibraryApiService { *; }

# Google Tink / AndroidX Security Crypto
-dontwarn com.google.errorprone.annotations.**

# CameraX Core & Lifecycle
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }

# Feature Dashboard Cards & Registry
-keep class com.college.core.ui.DashboardCard { *; }
-keep class com.college.core.ui.DashboardRegistry { *; }
-keep class com.college.feature.attendance.AttendanceDashboardCard { *; }
-keep class com.college.feature.library.LibraryDashboardCard { *; }
