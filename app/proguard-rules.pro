# Keep Kotlinx Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers class * {
    *** Companion;
}

-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,allowobfuscation,allowshrinking class * {
    <fields>;
}

# Keep Data Models
-keep class org.kazusa.matter.data.model.** { *; }
-keep class org.kazusa.matter.core.datastore.** { *; }
