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
  val effectiveBrightness: Int
    get() = ((brightness.coerceIn(10, 100) * globalBrightness.coerceIn(10, 100)) / 100f)
      .toInt()
      .coerceIn(1, 100)

  val hasVideo: Boolean
    get() = !videoPath.isNullOrBlank()
}
