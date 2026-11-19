package com.example.service

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.data.DataStoreRepository
import com.example.data.WallpaperPreferences
import com.example.media.PlaybackCoordinator
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LiveWallpaperService : WallpaperService() {

  private val activeEngines = mutableSetOf<VideoWallpaperEngine>()

  override fun onCreateEngine(): Engine =
    VideoWallpaperEngine().also { activeEngines.add(it) }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= TRIM_MEMORY_RUNNING_LOW) {
      activeEngines.forEach { it.trimCachedMemory() }
    }
  }

  private fun releaseOtherEngineDecoders(requester: VideoWallpaperEngine) {
    activeEngines.forEach { engine ->
      if (engine !== requester) {
        engine.releaseDecodersForHandoff()
      }
    }
  }

  @OptIn(UnstableApi::class)
  inner class VideoWallpaperEngine : Engine() {

    private val repository by lazy { DataStoreRepository(applicationContext) }
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var glRenderer: WallpaperGlRenderer? = null
    private var attachedPlayerSurface: Surface? = null
    private var usingGlRenderer = false
    private var forceDirectSurface = false

    private var currentPrefs = WallpaperPreferences()
    private var loadedVideoPath: String? = null
    private var loadedVideoVersion: Long = -1L
    private var isSurfaceReady = false
    private var isEngineVisible = false
    private var isAppForeground = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var cachedStillBitmap: Bitmap? = null
    private var cachedStillVersion: Long = -1L
    private var cachedWallpaperColors: WallpaperColors? = null
    private var stillLoadJob: Job? = null
    private var colorComputeJob: Job? = null
    private var decoderReleaseJob: Job? = null
    private var errorRecoveryJob: Job? = null
    private var playbackErrorAttempts = 0

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val playerListener =
      object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
          if (videoSize.width > 0 && videoSize.height > 0) {
            glRenderer?.updateVideoSize(
              width = videoSize.width,
              height = videoSize.height,
              unappliedRotationDegrees = videoSize.unappliedRotationDegrees,
              pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
            )
          }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_READY) {
            playbackErrorAttempts = 0
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          val wasUsingGl = usingGlRenderer
          releaseExoPlayer()
          releaseGlRenderer()
          // Jika decoder/SurfaceTexture GL menolak video resolusi tinggi, otomatis fallback ke SurfaceHolder langsung
          if (wasUsingGl) {
            forceDirectSurface = true
          }
          if (
            playbackErrorAttempts < 2 &&
              isSurfaceReady &&
              (isVisible || isEngineVisible) &&
              !currentPrefs.isStopped
          ) {
            playbackErrorAttempts += 1
            val retryDelayMs = if (playbackErrorAttempts == 1) 250L else 600L
            errorRecoveryJob?.cancel()
            errorRecoveryJob =
              engineScope.launch {
                delay(retryDelayMs)
                if (isSurfaceReady && (isVisible || isEngineVisible) && !currentPrefs.isStopped) {
                  syncEngineState()
                }
              }
          } else {
            // Hindari crash jika codec perangkat gagal permanen; tampilkan frame diam sebagai fallback aman
            drawStaticFrameIfPossible()
          }
        }
      }

    override fun onCreate(surfaceHolder: SurfaceHolder) {
      super.onCreate(surfaceHolder)
      surfaceHolder.setFormat(PixelFormat.RGBA_8888)

      engineScope.launch {
        combine(
            repository.preferencesFlow,
            PlaybackCoordinator.isAppForeground,
          ) { prefs, appForeground ->
            prefs to appForeground
          }
          .collect { (prefs, appForeground) ->
            isAppForeground = appForeground
            applyPreferences(prefs)
          }
      }

      engineScope.launch {
        PlaybackCoordinator.colorRefreshEvents.collect {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && isSurfaceReady) {
            if (cachedWallpaperColors != null) {
              notifyColorsChanged()
            } else {
              refreshWallpaperColors(currentPrefs.videoPath)
            }
          }
        }
      }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    override fun onComputeColors(): WallpaperColors? {
      return cachedWallpaperColors ?: super.onComputeColors()
    }

    override fun onSurfaceCreated(holder: SurfaceHolder) {
      super.onSurfaceCreated(holder)
      isSurfaceReady = true
      isEngineVisible = isVisible
      playbackErrorAttempts = 0
      val frame = holder.surfaceFrame
      if (frame.width() > 1 && frame.height() > 1) {
        surfaceWidth = frame.width()
        surfaceHeight = frame.height()
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && cachedWallpaperColors != null) {
        notifyColorsChanged()
      }
      if (currentPrefs.isStopped || !currentPrefs.hasVideo) {
        drawStaticFrameIfPossible()
      } else {
        syncEngineState()
      }
    }

    override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
      super.onSurfaceChanged(holder, format, width, height)
      surfaceWidth = width
      surfaceHeight = height

      if (width <= 1 || height <= 1) return

      if (currentPrefs.isStopped || !currentPrefs.hasVideo) {
        drawStaticFrameIfPossible()
      } else if (usingGlRenderer) {
        glRenderer?.updateSurfaceSize(holder.surface, width, height)
      } else {
        syncEngineState()
      }
    }

    override fun onVisibilityChanged(visible: Boolean) {
      super.onVisibilityChanged(visible)
      isEngineVisible = visible
      if (visible) {
        decoderReleaseJob?.cancel()
        decoderReleaseJob = null
        syncEngineState()
      } else {
        // Hanya jeda saat onVisibilityChanged(false) benar-benar terpanggil (bukan saat folder launcher dibuka)
        exoPlayer?.pause()
        // Lepas decoder MediaCodec jika tidak terlihat > 1.5 detik (layar mati / app penuh terbuka)
        decoderReleaseJob?.cancel()
        decoderReleaseJob =
          engineScope.launch {
            delay(1_500L)
            if (!isEngineVisible && !isVisible) {
              releaseExoPlayer()
              releaseGlRenderer()
            }
          }
      }
    }

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {
      isSurfaceReady = false
      decoderReleaseJob?.cancel()
      releaseExoPlayer()
      releaseGlRenderer()
      super.onSurfaceDestroyed(holder)
    }

    override fun onDestroy() {
      activeEngines.remove(this)
      engineScope.cancel()
      decoderReleaseJob?.cancel()
      errorRecoveryJob?.cancel()
      stillLoadJob?.cancel()
      colorComputeJob?.cancel()
      releaseExoPlayer()
      releaseGlRenderer()
      cachedStillBitmap?.recycle()
      cachedStillBitmap = null
      super.onDestroy()
    }

    fun releaseDecodersForHandoff() {
      decoderReleaseJob?.cancel()
      errorRecoveryJob?.cancel()
      releaseExoPlayer()
      releaseGlRenderer()
    }

    fun trimCachedMemory() {
      if (!isEngineVisible && !isVisible) {
        cachedStillBitmap?.recycle()
        cachedStillBitmap = null
        cachedStillVersion = -1L
      }
    }

    private fun applyPreferences(prefs: WallpaperPreferences) {
      val videoChanged =
        prefs.videoPath != loadedVideoPath || prefs.videoVersion != loadedVideoVersion

      currentPrefs = prefs

      if (videoChanged) {
        loadedVideoPath = prefs.videoPath
        loadedVideoVersion = prefs.videoVersion
        playbackErrorAttempts = 0
        forceDirectSurface = false
        errorRecoveryJob?.cancel()
        cachedStillBitmap?.recycle()
        cachedStillBitmap = null
        cachedStillVersion = -1L
        refreshWallpaperColors(prefs.videoPath)

        // Lepas player & renderer lama agar codec video baru terinisialisasi bersih tanpa state lama
        releaseExoPlayer()
        releaseGlRenderer()
      }

      exoPlayer?.let { player ->
        player.volume = (prefs.volume / 100f).coerceIn(0f, 1f)
        PlaybackCoordinator.syncAudioTrackState(trackSelector, prefs.volume)
      }

      if (usingGlRenderer) {
        glRenderer?.setBrightness(prefs.effectiveBrightness / 100f)
      }

      syncEngineState()
    }

    private fun refreshWallpaperColors(videoPath: String?) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
      colorComputeJob?.cancel()
      if (videoPath.isNullOrBlank() || !File(videoPath).exists()) {
        cachedWallpaperColors = null
        if (isSurfaceReady) notifyColorsChanged()
        return
      }

      colorComputeJob =
        engineScope.launch {
          val sampleBitmap =
            PlaybackCoordinator.loadColorSamplingBitmap(applicationContext, videoPath)
          if (sampleBitmap != null) {
            try {
              cachedWallpaperColors = WallpaperColors.fromBitmap(sampleBitmap)
              if (isSurfaceReady) {
                notifyColorsChanged()
              }
            } finally {
              if (!sampleBitmap.isRecycled) sampleBitmap.recycle()
            }
          }
        }
    }

    private fun syncEngineState() {
      if (!isSurfaceReady || surfaceWidth <= 1 || surfaceHeight <= 1) return

      val videoPath = currentPrefs.videoPath
      val videoFile = videoPath?.let { File(it) }?.takeIf { it.exists() }
      if (videoFile == null) {
        releaseExoPlayer()
        releaseGlRenderer()
        drawBlankDominantSurface()
        return
      }

      val engineVisible = isVisible || isEngineVisible
      // Jika MainActivity tampil penuh dan wallpaper tertutup, lepas decoder wallpaper segera
      val yieldToForegroundApp = isAppForeground && !isPreview && !engineVisible
      if (yieldToForegroundApp) {
        decoderReleaseJob?.cancel()
        releaseExoPlayer()
        releaseGlRenderer()
        return
      }
      if (!engineVisible) {
        exoPlayer?.pause()
        return
      }

      // Mode Berhenti: 0% ExoPlayer / 0% MediaCodec — cukup render frame diam ke Canvas
      if (currentPrefs.isStopped) {
        releaseExoPlayer()
        releaseGlRenderer()
        drawStaticFrameIfPossible()
        return
      }

      // Mode Berjalan: lepas decoder engine lain dulu agar video resolusi besar tidak berebut MediaCodec
      releaseOtherEngineDecoders(this)
      val player = ensureExoPlayer(videoFile)
      player.volume = (currentPrefs.volume / 100f).coerceIn(0f, 1f)
      PlaybackCoordinator.syncAudioTrackState(trackSelector, currentPrefs.volume)

      // Pada kecerahan 100%, render langsung ke SurfaceHolder tanpa lapisan GL agar video 4K/besar zero-copy
      if (forceDirectSurface || currentPrefs.effectiveBrightness >= 100) {
        attachDirectSurfaceAndPlay(player, videoFile)
      } else {
        ensureGlRendererAndAttach(player, videoFile)
      }
    }

    private fun ensureExoPlayer(videoFile: File): ExoPlayer {
      val existing = exoPlayer
      if (existing != null) {
        if (existing.currentMediaItem == null || existing.playbackState == Player.STATE_IDLE) {
          existing.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
          existing.prepare()
        }
        return existing
      }

      val (newPlayer, newSelector) =
        PlaybackCoordinator.buildLightweightExoPlayer(
          context = applicationContext,
          initialVolume = currentPrefs.volume,
        )
      newPlayer.addListener(playerListener)
      newPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
      newPlayer.prepare()
      exoPlayer = newPlayer
      trackSelector = newSelector
      return newPlayer
    }

    private fun attachDirectSurfaceAndPlay(player: ExoPlayer, videoFile: File) {
      releaseGlRenderer()
      if (!isSurfaceReady) return
      if (player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
        player.prepare()
      }
      player.setVideoSurfaceHolder(surfaceHolder)
      if ((isVisible || isEngineVisible) && !currentPrefs.isStopped) {
        player.play()
      }
    }

    private fun ensureGlRendererAndAttach(player: ExoPlayer, videoFile: File) {
      if (glRenderer != null && usingGlRenderer) {
        attachedPlayerSurface?.takeIf { it.isValid }?.let { player.setVideoSurface(it) }
        if (player.playbackState == Player.STATE_IDLE) {
          player.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
          player.prepare()
        }
        if ((isVisible || isEngineVisible) && !currentPrefs.isStopped && !player.isPlaying) {
          player.play()
        }
        return
      }

      releaseGlRenderer()
      player.clearVideoSurface()
      val holderSurface = surfaceHolder.surface
      if (holderSurface == null || !holderSurface.isValid) return

      usingGlRenderer = true
      val renderer = WallpaperGlRenderer()
      glRenderer = renderer
      renderer.initialize(
        windowSurface = holderSurface,
        width = surfaceWidth,
        height = surfaceHeight,
        initialBrightness = currentPrefs.effectiveBrightness / 100f,
        onPlayerSurfaceReady = { playerSurface ->
          if (isSurfaceReady && usingGlRenderer) {
            attachedPlayerSurface = playerSurface
            exoPlayer?.setVideoSurface(playerSurface)
            if ((isVisible || isEngineVisible) && !currentPrefs.isStopped) {
              exoPlayer?.play()
            }
          }
        },
        onFallbackRequired = {
          forceDirectSurface = true
          usingGlRenderer = false
          attachedPlayerSurface = null
          releaseGlRenderer()
          if (isSurfaceReady && (isVisible || isEngineVisible) && !currentPrefs.isStopped) {
            exoPlayer?.setVideoSurfaceHolder(surfaceHolder)
            exoPlayer?.play()
          }
        },
      )
    }

    private fun releaseExoPlayer() {
      val player = exoPlayer ?: return
      exoPlayer = null
      trackSelector = null
      runCatching {
        player.removeListener(playerListener)
        player.clearVideoSurface()
        player.stop()
        player.release()
      }
    }

    private fun releaseGlRenderer() {
      usingGlRenderer = false
      attachedPlayerSurface = null
      glRenderer?.release()
      glRenderer = null
    }

    private fun drawStaticFrameIfPossible() {
      val videoPath = currentPrefs.videoPath ?: return
      val file = File(videoPath)
      if (!file.exists()) {
        drawBlankDominantSurface()
        return
      }

      val existingBitmap = cachedStillBitmap
      if (existingBitmap != null && !existingBitmap.isRecycled && cachedStillVersion == currentPrefs.videoVersion) {
        renderBitmapOnCanvas(existingBitmap, currentPrefs.effectiveBrightness)
        return
      }

      stillLoadJob?.cancel()
      stillLoadJob =
        engineScope.launch {
          val w = surfaceWidth.coerceAtLeast(720)
          val h = surfaceHeight.coerceAtLeast(1280)
          val frame = PlaybackCoordinator.extractFirstFrameBitmap(videoPath, w, h)
          if (frame != null && isSurfaceReady) {
            cachedStillBitmap?.recycle()
            cachedStillBitmap = frame
            cachedStillVersion = currentPrefs.videoVersion
            if (currentPrefs.isStopped || exoPlayer == null) {
              renderBitmapOnCanvas(frame, currentPrefs.effectiveBrightness)
            }
          }
        }
    }

    private fun renderBitmapOnCanvas(bitmap: Bitmap, brightnessPercent: Int) {
      if (!isSurfaceReady || surfaceWidth <= 1 || surfaceHeight <= 1) return
      val holder = surfaceHolder
      var canvas: Canvas? = null
      try {
        canvas = holder.lockCanvas()
        if (canvas != null) {
          canvas.drawColor(Color.BLACK)
          val matrix = computeCenterCropMatrix(bitmap.width, bitmap.height, canvas.width, canvas.height)
          canvas.drawBitmap(bitmap, matrix, bitmapPaint)

          val dimAlpha = ((1f - (brightnessPercent.coerceIn(1, 100) / 100f)) * 255).toInt()
          if (dimAlpha > 0) {
            canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
          }
        }
      } catch (_: Throwable) {
        // Abaikan jika Surface sudah terlepas
      } finally {
        if (canvas != null) {
          runCatching { holder.unlockCanvasAndPost(canvas) }
        }
      }
    }

    private fun drawBlankDominantSurface() {
      if (!isSurfaceReady || surfaceWidth <= 1 || surfaceHeight <= 1) return
      var canvas: Canvas? = null
      try {
        canvas = surfaceHolder.lockCanvas()
        canvas?.drawColor(0xFFF7F7F5.toInt())
      } catch (_: Throwable) {
      } finally {
        if (canvas != null) {
          runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
        }
      }
    }

    private fun computeCenterCropMatrix(
      srcW: Int,
      srcH: Int,
      dstW: Int,
      dstH: Int,
    ): Matrix {
      val matrix = Matrix()
      if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return matrix
      val scale = maxOf(dstW.toFloat() / srcW.toFloat(), dstH.toFloat() / srcH.toFloat())
      val dx = (dstW - srcW * scale) * 0.5f
      val dy = (dstH - srcH * scale) * 0.5f
      matrix.setScale(scale, scale)
      matrix.postTranslate(dx, dy)
      return matrix
    }
  }
}
