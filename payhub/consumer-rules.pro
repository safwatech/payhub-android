# kotlinx.serialization runtime rules — keep generated $serializer companions.
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer {
    static **$$serializer INSTANCE;
}
