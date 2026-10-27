# The phone and watch exchange kotlinx.serialization JSON. R8 must keep the
# generated serializers and the wire model classes they describe.
-keepattributes *Annotation*, InnerClasses, Signature

-keep class com.openbubbles.wear.protocol.** { *; }
-keepclasseswithmembers class com.openbubbles.wear.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn kotlinx.serialization.**
