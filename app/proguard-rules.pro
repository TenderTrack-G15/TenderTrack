-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class za.ac.tendertrack.data.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
