# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep broadcast receivers
-keep class com.office.app.BootReceiver { *; }
-keep class com.office.app.RestartReceiver { *; }

# Keep service
-keep class com.office.app.PersistentService { *; }
