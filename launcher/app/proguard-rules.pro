-keep class dev.solon.bucklerhome.** { *; }

# Tink / EncryptedSharedPreferences (security-crypto)
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }
