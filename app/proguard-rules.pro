# Polar BLE SDK is a JitPack-published AAR. Keep its public types from being
# stripped or renamed by R8.
-keep class com.polar.** { *; }
-keep class fi.polar.** { *; }
-dontwarn com.polar.**

# RxJava + protobuf transitive deps need their reflection-friendly classes.
-keep class io.reactivex.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn io.reactivex.**
-dontwarn com.google.protobuf.**
