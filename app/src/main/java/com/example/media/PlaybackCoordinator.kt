package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Mengoordinasikan pemakaian decoder video perangkat keras agar MainActivity dan
 * LiveWallpaperService tidak pernah berebut MediaCodec secara bersamaan, sekaligus
 * membangun ExoPlayer hemat memori dan baterai.
 */
object PlaybackCoordinator {

  private const val TAG = "PlaybackCoordinator"
  private const val STATIC_FALLBACK_FILE_NAME = "static_fallback.jpg"

  private val _isAppForeground = MutableStateFlow(false)
  val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

  private val _colorRefreshEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val colorRefreshEvents: SharedFlow<Unit> = _colorRefreshEvents.asSharedFlow()

  fun setAppForeground(foreground: Boolean) {
    _isAppForeground.value = foreground
  }

  fun requestColorRefresh() {
    _colorRefreshEvents.tryEmit(Unit)
  }

  fun getStaticFallbackFile(context: Context): File =
    File(context.applicationContext.filesDir, STATIC_FALLBACK_FILE_NAME)

  @OptIn(UnstableApi::class)
  fun buildLightweightExoPlayer(
    context: Context,
    initialVolume: Int,
  ): Pair<ExoPlayer, DefaultTrackSelector> {
    val appContext = context.applicationContext

    val trackSelector =
      DefaultTrackSelector(appContext).apply {
        parameters =
          buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, initialVolume <= 0)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
            .build()
      }

    // Buffer ringan & terbatasi ukuran memori agar video ukuran besar/4K tetap aman dan cepat mulai
    val loadControl =
      DefaultLoadControl.Builder()
        .setBufferDurationsMs(
          /* minBufferMs = */ 1_500,
          /* maxBufferMs = */ 4_000,
          /* bufferForPlaybackMs = */ 250,
          /* bufferForPlaybackAfterRebufferMs = */ 750,
        )
        .build()

    val renderersFactory =
      DefaultRenderersFactory(appContext)
        .setEnableDecoderFallback(true)

    val player =
      ExoPlayer.Builder(appContext, renderersFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .build()
        .apply {
          repeatMode = Player.REPEAT_MODE_ONE
          videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
          videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
          setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
          volume = (initialVolume / 100f).coerceIn(0f, 1f)
        }

    return player to trackSelector
  }

  @OptIn(UnstableApi::class)
  fun syncAudioTrackState(trackSelector: DefaultTrackSelector?, volume: Int) {
    val selector = trackSelector ?: return
    val shouldDisableAudio = volume <= 0
    val currentlyDisabled =
      selector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
    if (currentlyDisabled != shouldDisableAudio) {
      selector.parameters =
        selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, shouldDisableAudio).build()
    }
  }

  suspend fun cacheStaticFallbackFrame(
    context: Context,
    videoPath: String,
  ): File? =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val outFile = getStaticFallbackFile(appContext)
      val metrics = appContext.resources.displayMetrics
      val targetW = metrics.widthPixels.coerceIn(720, 1920)
      val targetH = metrics.heightPixels.coerceIn(1080, 1920)
      val retriever = MediaMetadataRetriever()
      var bitmap: Bitmap? = null
      try {
        retriever.setDataSource(videoPath)
        bitmap =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
              0L,
              MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
              targetW,
              targetH,
            )
              ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
              ?: retriever.getFrameAtTime(0L)
          } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
              ?: retriever.getFrameAtTime(0L)
          }
        if (bitmap != null) {
          val tempFile = File(appContext.filesDir, "$STATIC_FALLBACK_FILE_NAME.tmp")
          FileOutputStream(tempFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            fos.flush()
          }
          if (outFile.exists()) outFile.delete()
          if (!tempFile.renameTo(outFile)) {
            tempFile.copyTo(outFile, overwrite = true)
            tempFile.delete()
          }
          outFile
        } else {
          null
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Gagal mengekstrak frame fallback dari video", e)
        null
      } finally {
        runCatching { retriever.release() }
        bitmap?.let { if (!it.isRecycled) it.recycle() }
      }
    }

  suspend fun loadColorSamplingBitmap(
    context: Context,
    videoPath: String,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val cacheFile =
        getStaticFallbackFile(appContext).takeIf { it.exists() }
          ?: cacheStaticFallbackFrame(appContext, videoPath)
      if (cacheFile != null && cacheFile.exists()) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
      } else {
        extractFirstFrameBitmap(videoPath, 360, 640)
      }
    }

  suspend fun extractFirstFrameBitmap(
    videoPath: String,
    targetWidth: Int,
    targetHeight: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      val retriever = MediaMetadataRetriever()
      runCatching {
          retriever.setDataSource(videoPath)
          val safeW = targetWidth.coerceIn(360, 1920)
          val safeH = targetHeight.coerceIn(360, 1920)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
              0L,
              MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
              safeW,
              safeH,
            )
              ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
              ?: retriever.getFrameAtTime(0L)
          } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
              ?: retriever.getFrameAtTime(0L)
          }
        }
        .getOrNull()
        .also {
          runCatching { retriever.release() }
        }
    }
}
