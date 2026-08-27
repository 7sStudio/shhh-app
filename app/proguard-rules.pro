# TileService and GlanceAppWidgetReceiver are referenced from the manifest and
# kept by the default AAPT/AGP consumer rules.

# Glance depends on WorkManager, whose Room database implementation is looked
# up reflectively (Class.forName("...WorkDatabase_Impl")). R8 full mode strips
# it without this rule, crashing the release build on first launch.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
