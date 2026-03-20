# PalmGrade RNS — ProGuard rules

# Keep Room entity classes
-keep class com.palmgrade.rns.grading.BunchRecord { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }

# Keep LXMF packet builder (reflection-sensitive byte ops)
-keep class com.palmgrade.rns.rns.RnsIdentity { *; }
-keep class com.palmgrade.rns.rns.RnsService  { *; }

# Keep Bluetooth manager (referenced by service)
-keep class com.palmgrade.rns.bluetooth.RNodeBluetoothManager { *; }
-keep class com.palmgrade.rns.bluetooth.RNodeBluetoothManager$* { *; }

# Keep ExifInterface fields
-keep class androidx.exifinterface.media.ExifInterface { *; }

# Standard Android / Kotlin keeps
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn kotlin.**
