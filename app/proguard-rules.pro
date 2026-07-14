# FrameNest release shrink/obfuscation rules.

# Keep Compose / ViewModel reflective entry points.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# libVLC native + reflection
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# SMBJ / ASN.1 / BouncyCastle used transitively
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.ietf.jgss.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Optional annotations / APIs referenced by Tink & mbassador (compile-only)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.el.**
-dontwarn net.engio.mbassy.**

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
