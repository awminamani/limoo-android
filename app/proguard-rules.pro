# RemoteViews inflates the notification layout by reflection, so this view and its setter must survive.
-keep class app.limoo.core.DotStripView { *; }
-keep class libv2ray.** { *; }
-keep class go.** { *; }
