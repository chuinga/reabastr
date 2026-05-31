# Reabastr ProGuard Rules

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class com.reabastr.app.data.remote.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Hilt
-keep class dagger.hilt.** { *; }
