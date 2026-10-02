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

# SMBJ's mbassador event bus constructs handler invocations from
# SubscriptionContext via reflection. Keep these small runtime entry points.
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**

# Vosk exposes its native API through LibVosk/JNA reflection. These classes are
# referenced by name from native code and must retain their members in Release.
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
# libvlc calls these from native code. R8 never sees a Java call site.
-keep class com.framenest.data.thumbnail.ThumbnailVlcCover { *; }
-keep class com.framenest.data.thumbnail.ThumbnailVlcCover$* { *; }
-keep class com.framenest.feature.player.ScrubPreviewVlc { *; }
-keep class com.framenest.feature.player.ScrubPreviewVlc$* { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }

# sherpa-onnx (FN-51): JNI entry points (native methods + config getters invoked
# from C++ by name/signature) must survive R8, or recognizer creation fails
# with UnsatisfiedLinkError on Release only.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ML Kit's RemoteModelManager builds Firebase JSON encoders at runtime. R8 class
# merging can corrupt the anonymous encoder implementations and crash app startup.
-keep class com.google.firebase.encoders.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Optional annotations / APIs referenced by Tink & mbassador (compile-only)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.el.**

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
