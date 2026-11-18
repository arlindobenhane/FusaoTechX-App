# Manter componentes referenciados pelo sistema Android (Manifest, reflexão interna)
-keep class com.fusaotech.studio.MainActivity { *; }
-keep class com.fusaotech.studio.DecoyActivity { *; }
-keep class com.fusaotech.studio.CameraActivity { *; }
-keep class com.fusaotech.studio.CaptureService { *; }
-keep class com.fusaotech.studio.ControlService { *; }
-keep class com.fusaotech.studio.NotifService { *; }
-keep class com.fusaotech.studio.AdminReceiver { *; }
-keep class com.fusaotech.studio.BootReceiver { *; }
-keep class com.fusaotech.studio.WatchdogReceiver { *; }
-keep class com.fusaotech.studio.SecretCodeReceiver { *; }

# ZXing (QR codes)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Kotlin stdlib
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
