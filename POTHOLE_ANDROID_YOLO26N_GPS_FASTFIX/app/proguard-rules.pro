# Release rules. Keep TensorFlow Lite JNI-facing classes and app model classes intact.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class com.pothole.v3.** { *; }
