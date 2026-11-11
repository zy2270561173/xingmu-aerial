# Activities are referenced from the manifest; AGP keeps them.

# ijkplayer: the native side looks classes up by name through J4A, so R8 must not rename or strip
# them. Without this the player dies with NoClassDefFoundError: IMediaDataSource (seen on the 4.4 TV).
-keep class tv.danmaku.ijk.media.** { *; }
-keep class tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.media.**