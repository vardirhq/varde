# Release shrinking rules. Libraries (OkHttp, ML Kit, WorkManager, kotlinx.serialization, Compose) ship their own.

# Navigation routes are found through their serializers, including `object` routes (InboxRoute and the other tabs).
# Only routes are @Serializable, so keeping them whole costs little and avoids reflection surprises.
-keep @kotlinx.serialization.Serializable class no.vardir.varde.** { *; }
-keep,includedescriptorclasses class no.vardir.varde.**$$serializer { *; }

# Created by name by WorkManager and Android.
-keep class no.vardir.varde.InboxCheckWorker { <init>(...); }
-keep class no.vardir.varde.InstallResultReceiver { <init>(); }

# commonmark loads its extensions reflectively in places; it's small, so keep it whole.
-keep class org.commonmark.** { *; }
-dontwarn org.commonmark.**

# Optional OkHttp TLS providers that aren't on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
