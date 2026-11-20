package com.example.service

import android.app.WallpaperColors
import android.graphics.Bitmap
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
    private var attachedDirectSurface: Surface? = null
    private var usingGlRenderer = false
    private var forceDirectSurface = false

    private var currentPrefs = WallpaperPreferences()
    private var hasLoadedPreferences = false
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
          // Jika decoder/SurfaceTexture GL menolak video resolusi tinggi, otomatis fallback ke Surface langsung
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
            // Hindari crash jika codec perangkat gagal permanen; tampilkan frame diam via GL sebagai fallback aman
            drawStaticFrameIfPossible()
          }
        }
      }

    override fun onCreate(surfaceHolder: SurfaceHolder) {
      super.onCreate(surfaceHolder)
      surfaceHolder.setFormat(PixelFormat.RGBA_8888)

      engineScope.launch {
        repository.ensureEmbeddedVideoLoaded()
        combine(
            repository.preferencesFlow,
            PlaybackCoordinator.isAppForeground,
          ) { prefs, appForeground ->
            prefs to appForeground
          }
          .collect { (prefs, appForeground) ->
            hasLoadedPreferences = true
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
      if (!hasLoadedPreferences) return
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

      if (width <= 1 || height <= 1 || !hasLoadedPreferences) return

      if (currentPrefs.isStopped || !currentPrefs.hasVideo) {
        if (glRenderer != null) {
          glRenderer?.updateSurfaceSize(holder.surface, width, height)
        } else {
          drawStaticFrameIfPossible()
        }
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
              if (usingGlRenderer) {
                releaseGlRenderer()
              }
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
        stillLoadJob?.cancel()
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

      glRenderer?.setBrightness(prefs.effectiveBrightness / 100f)

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
      if (!hasLoadedPreferences || !isSurfaceReady || surfaceWidth <= 1 || surfaceHeight <= 1) {
        return
      }

      val videoPath = currentPrefs.videoPath
      val videoFile = videoPath?.let { File(it) }?.takeIf { it.exists() }
      if (videoFile == null) {
        releaseExoPlayer()
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

      // Mode Berhenti: 0% ExoPlayer / 0% MediaCodec — render frame diam via EGL agar Surface tetap bersih
      if (currentPrefs.isStopped) {
        releaseExoPlayer()
        drawStaticFrameIfPossible()
        return
      }

      // Mode Berjalan: lepas decoder engine lain dulu agar video resolusi besar tidak berebut MediaCodec
      releaseOtherEngineDecoders(this)
      val player = ensureExoPlayer()
      player.volume = (currentPrefs.volume / 100f).coerceIn(0f, 1f)
      PlaybackCoordinator.syncAudioTrackState(trackSelector, currentPrefs.volume)

      // Pada kecerahan 100%, render langsung ke Surface tanpa lapisan GL agar video 4K/besar zero-copy
      if (forceDirectSurface || currentPrefs.effectiveBrightness >= 100) {
        attachDirectSurfaceAndPlay(player, videoFile)
      } else {
        ensureGlRendererAndAttach(player, videoFile)
      }
    }

    private fun ensureExoPlayer(): ExoPlayer {
      val existing = exoPlayer
      if (existing != null) {
        return existing
      }

      val (newPlayer, newSelector) =
        PlaybackCoordinator.buildLightweightExoPlayer(
          context = applicationContext,
          initialVolume = currentPrefs.volume,
        )
      newPlayer.addListener(playerListener)
      exoPlayer = newPlayer
      trackSelector = newSelector
      return newPlayer
    }

    private fun attachDirectSurfaceAndPlay(player: ExoPlayer, videoFile: File) {
      releaseGlRenderer()
      if (!isSurfaceReady) return
      val windowSurface = surfaceHolder.surface
      if (windowSurface == null || !windowSurface.isValid) return

      if (attachedDirectSurface !== windowSurface) {
        player.setVideoSurface(windowSurface)
        attachedDirectSurface = windowSurface
      }
      if (player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
        player.prepare()
      }
      if ((isVisible || isEngineVisible) && !currentPrefs.isStopped) {
        player.play()
      }
    }

    private fun ensureGlRendererAndAttach(player: ExoPlayer, videoFile: File) {
      if (glRenderer != null && usingGlRenderer) {
        attachedPlayerSurface?.takeIf { it.isValid }?.let { playerSurface ->
          player.setVideoSurface(playerSurface)
          if (player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            player.prepare()
          }
          if ((isVisible || isEngineVisible) && !currentPrefs.isStopped && !player.isPlaying) {
            player.play()
          }
        }
        return
      }

      releaseGlRenderer()
      attachedDirectSurface = null
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
          if (isSurfaceReady && usingGlRenderer && playerSurface.isValid) {
            attachedPlayerSurface = playerSurface
            val activePlayer = exoPlayer ?: return@initialize
            activePlayer.setVideoSurface(playerSurface)
            if (activePlayer.currentMediaItem == null || activePlayer.playbackState == Player.STATE_IDLE) {
              activePlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
              activePlayer.prepare()
            }
            if ((isVisible || isEngineVisible) && !currentPrefs.isStopped) {
              activePlayer.play()
            }
          }
        },
        onFallbackRequired = {
          forceDirectSurface = true
          usingGlRenderer = false
          attachedPlayerSurface = null
          releaseGlRenderer()
          val activePlayer = exoPlayer
          if (activePlayer != null && isSurfaceReady && (isVisible || isEngineVisible) && !currentPrefs.isStopped) {
            attachDirectSurfaceAndPlay(activePlayer, videoFile)
          }
        },
      )
    }

    private fun releaseExoPlayer() {
      attachedDirectSurface = null
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
      if (!isSurfaceReady || surfaceWidth <= 1 || surfaceHeight <= 1) return
      val videoPath = currentPrefs.videoPath
      if (videoPath.isNullOrBlank()) {
        drawBlankDominantSurface()
        return
      }
      val file = File(videoPath)
      if (!file.exists()) {
        drawBlankDominantSurface()
        return
      }

      val existingBitmap = cachedStillBitmap
      if (existingBitmap != null && !existingBitmap.isRecycled && cachedStillVersion == currentPrefs.videoVersion) {
        renderBitmapViaGl(existingBitmap, currentPrefs.effectiveBrightness)
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
              renderBitmapViaGl(frame, currentPrefs.effectiveBrightness)
            }
          } else if (frame == null && isSurfaceReady && (currentPrefs.isStopped || exoPlayer == null)) {
            drawBlankDominantSurface()
          }
        }
    }

    private fun renderBitmapViaGl(bitmap: Bitmap, brightnessPercent: Int) {
      if (!isSurfaceReady || surfaceWidth <= 1 || surfaceHeight <= 1 || bitmap.isRecycled) return
      val windowSurface = surfaceHolder.surface ?: return
      if (!windowSurface.isValid) return

      usingGlRenderer = false
      attachedPlayerSurface = null
      val renderer = glRenderer ?: WallpaperGlRenderer().also { glRenderer = it }
      renderer.renderStaticBitmap(
        windowSurface = windowSurface,
        width = surfaceWidth,
        height = surfaceHeight,
        bitmap = bitmap,
        brightness = brightnessPercent.coerceIn(1, 100) / 100f,
      )
    }

    private fun drawBlankDominantSurface() {
      if (!isSurfaceReady || surfaceWidth <= 1 || surfaceHeight <= 1) return
      val windowSurface = surfaceHolder.surface ?: return
      if (!windowSurface.isValid) return

      usingGlRenderer = false
      attachedPlayerSurface = null
      val renderer = glRenderer ?: WallpaperGlRenderer().also { glRenderer = it }
      renderer.renderSolidColor(
        windowSurface = windowSurface,
        width = surfaceWidth,
        height = surfaceHeight,
        color = 0xFFF7F7F5.toInt(),
      )
    }
  }
}
