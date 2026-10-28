-keep class dev.solon.bucklerhome.** { *; }

# ONNX Runtime reaches its Java classes from native code.
-keep class ai.onnxruntime.** { *; }

# Tink / EncryptedSharedPreferences (security-crypto)
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
