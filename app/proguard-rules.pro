# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
