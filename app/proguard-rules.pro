# Add project specific ProGuard rules here.

# Keep MeshCore protocol classes
-keep class com.meshcore.wearos.ble.** { *; }
-keep class com.meshcore.wearos.data.model.** { *; }

# Hilt
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Wear OS
-keep class androidx.wear.** { *; }
-keep class com.google.android.horologist.** { *; }
