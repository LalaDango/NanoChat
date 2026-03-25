# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class com.example.nanochat.data.model.** { *; }
