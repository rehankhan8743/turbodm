# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keepclasseswithmembers class * { @dagger.hilt.android.AndroidEntryPoint <init>(...); }

# Room
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Kotlinx coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**

# Domain models — keep names so Room's reflective mapping survives R8.
-keep class com.turbodm.app.domain.model.** { *; }
-keep class com.turbodm.app.data.local.** { *; }
