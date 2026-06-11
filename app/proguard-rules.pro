# kotlinx.serialization — mantém serializers gerados
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class app.vendanozap.printagent.** {
    *** Companion;
}
-keepclasseswithmembers class app.vendanozap.printagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
