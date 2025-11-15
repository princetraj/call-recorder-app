# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep broadcast receivers
-keep class com.hairocraft.dialer.BootReceiver { *; }
-keep class com.hairocraft.dialer.RestartReceiver { *; }

# Keep service
-keep class com.hairocraft.dialer.PersistentService { *; }
