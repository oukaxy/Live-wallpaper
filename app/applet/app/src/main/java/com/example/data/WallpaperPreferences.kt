package com.example.data

data class WallpaperPreferences(
  val videoPath: String? = null,
  val videoFileName: String = "video_wallpaper.mp4",
  val videoVersion: Long = 0L,
  val volume: Int = 70,
  val brightness: Int = 100,
  val globalBrightness: Int = 100,
  val isStopped: Boolean = false,
) {
  val hasVideo: Boolean
    get() = !videoPath.isNullOrBlank()

  val effectiveBrightness: Int
    get() = ((brightness / 100f) * (globalBrightness / 100f) * 100f).toInt().coerceIn(1, 100)
}
