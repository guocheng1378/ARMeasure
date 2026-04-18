# ProGuard / R8 rules for ARMeasure

# Keep measurement engine (used by reflection or JNI in future)
-keep class com.armeasure.app.MeasurementEngine { *; }

# Keep Camera2 callback classes
-keep class com.armeasure.app.CameraController$* { *; }

# Keep custom views referenced from XML
-keep class com.armeasure.app.MeasureOverlayView { *; }

# AndroidX / Material
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# Camera2 / SensorManager — prevent R8 from stripping reflection-accessed members
-keep class android.hardware.camera2.** { *; }
-keep class android.hardware.Sensor** { *; }

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
