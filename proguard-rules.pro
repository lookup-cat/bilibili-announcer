-dontwarn ch.qos.logback.**
-keep class ch.qos.logback.** {*;}
-keep class moe.sdl.yabapi.** {*;}
-keep class io.ktor.serialization.kotlinx.** {*;}
-keep class com.lookupcat.bilibiliannouncer.ColorLog {*;}

-dontwarn org.tritonus.midi.device.java.SunMiscPerfClock
-keep class * extends javax.sound.sampled.spi.AudioFileReader {*;}
-keep class * extends javax.sound.sampled.spi.FormatConversionProvider {*;}

-keep class io.ktor.client.engine.cio.** {*;}

# Kotlin serialization looks up the generated serializer classes through a function on companion
# objects. The companions are looked up reflectively so we need to explicitly keep these functions.
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
## If a companion has the serializer function, keep the companion field on the original type so that
## the reflective lookup succeeds.
-if class **.*$Companion {
  kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class <1>.<2> {
  <1>.<2>$Companion Companion;
}