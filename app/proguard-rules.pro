# TileService and GlanceAppWidgetReceiver are referenced from the manifest and
# kept by the default AAPT/AGP consumer rules.

# Glance depends on WorkManager, whose Room database implementation is looked
# up reflectively (Class.forName("...WorkDatabase_Impl")). R8 full mode strips
# it without this rule, crashing the release build on first launch.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager instantiates input mergers reflectively by class name
# (Class.newInstance on OverwritingInputMerger by default). R8 full mode keeps
# the class but strips its zero-argument constructor, so every Glance widget
# update worker dies with InstantiationException and the launcher shows
# "Can't load widget".
-keep class * extends androidx.work.InputMerger { <init>(); }
