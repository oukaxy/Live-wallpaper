package com.example.ui

import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.media.PlaybackCoordinator
import com.example.ui.components.ControlCenterOverlay
import com.example.ui.components.SetWallpaperSheet
import com.example.ui.theme.AccentCobalt
import com.example.ui.theme.BgDominant
import com.example.ui.theme.BorderGlass
import com.example.ui.theme.InkInverse
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.SurfaceGlass
import com.example.ui.theme.SurfaceSecondary
import com.example.ui.theme.WireframeStroke
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
  uiState: MainUiState,
  onOpenSetWallpaper: () -> Unit,
  onOpenSettings: () -> Unit,
  onExportApk: () -> Unit,
  onCloseSheets: () -> Unit,
  onPickVideoClick: () -> Unit,
  onApplyWallpaper: () -> Unit,
  onVolumeChange: (Int) -> Unit,
  onBrightnessChange: (Int) -> Unit,
  onGlobalBrightnessChange: (Int) -> Unit,
  onTogglePlayback: () -> Unit,
) {
  val prefs = uiState.preferences

  Box(modifier = Modifier.fillMaxSize().background(BgDominant)) {
    // 1. Full-Bleed Viewport (Video Preview or Minimal Empty Wireframe)
    if (prefs.hasVideo && prefs.videoPath != null) {
      LiveVideoPreviewViewport(
        videoPath = prefs.videoPath,
        videoVersion = prefs.videoVersion,
        volume = prefs.volume,
        brightness = prefs.effectiveBrightness,
        isStopped = prefs.isStopped,
      )
    } else {
      EmptyViewportIndicator(modifier = Modifier.align(Alignment.Center))
    }

    // 2. Floating Top Progress Indicator (APK Packaging/Signing) or Toast Pill
    val exportProgress = uiState.apkExportProgress
    AnimatedVisibility(
      visible = exportProgress != null || uiState.toastMessage != null,
      enter = fadeIn() + slideInVertically { -it },
      exit = fadeOut() + slideOutVertically { -it },
      modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 20.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = SurfaceGlass,
        border = BorderStroke(1.dp, BorderGlass),
        shadowElevation = 6.dp,
        modifier = Modifier.testTag("top_status_pill"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (exportProgress != null) {
            CircularProgressIndicator(
              progress = { exportProgress.progress },
              modifier = Modifier.size(14.dp),
              color = AccentCobalt,
              trackColor = AccentCobalt.copy(alpha = 0.2f),
              strokeWidth = 2.dp,
            )
            Text(
              text = exportProgress.stageLabel,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Medium,
              letterSpacing = (-0.12).sp,
              color = InkPrimary,
            )
          } else {
            Box(
              modifier = Modifier.size(7.dp).clip(CircleShape).background(AccentCobalt),
            )
            Text(
              text = uiState.toastMessage.orEmpty(),
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Medium,
              letterSpacing = (-0.12).sp,
              color = InkPrimary,
            )
          }
        }
      }
    }

    // 3. ONLY 2 Floating Bottom Controls ('Set Wallpaper' + 'Pengaturan')
    AnimatedVisibility(
      visible = uiState.activeSheet == null,
      enter = fadeIn() + slideInVertically { it / 2 },
      exit = fadeOut() + slideOutVertically { it / 2 },
      modifier = Modifier.align(Alignment.BottomCenter),
    ) {
      Row(
        modifier =
          Modifier.widthIn(max = 480.dp)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Element 1: Pill CTA 'Set Wallpaper'
        Button(
          onClick = onOpenSetWallpaper,
          shape = CircleShape,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = AccentCobalt,
              contentColor = InkInverse,
            ),
          modifier =
            Modifier.weight(1f)
              .height(52.dp)
              .shadow(
                elevation = 14.dp,
                shape = CircleShape,
                ambientColor = AccentCobalt.copy(alpha = 0.38f),
                spotColor = AccentCobalt.copy(alpha = 0.38f),
              )
              .testTag("open_set_wallpaper_button"),
        ) {
          Text(
            text = stringResource(R.string.cta_set_wallpaper),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.15).sp,
          )
        }

        // Element 2: Circular Glassmorphic 'Pengaturan' Icon Button (52dp x 52dp)
        // Tap singkat -> buka Pengaturan | Tekan lama 2 detik -> Export APK mandiri
        SettingsFloatingButton(
          isExporting = exportProgress != null,
          onTap = onOpenSettings,
          onLongPressExport = onExportApk,
        )
      }
    }

    // 4. Bottom Sheets & Floating Control Center
    if (uiState.activeSheet == ActiveSheet.SET_WALLPAPER) {
      SetWallpaperSheet(
        hasVideo = prefs.hasVideo,
        videoFileName = prefs.videoFileName,
        isCopyingVideo = uiState.isCopyingVideo,
        onPickVideoClick = onPickVideoClick,
        onApplyWallpaper = onApplyWallpaper,
        onDismiss = onCloseSheets,
      )
    }

    AnimatedVisibility(
      visible = uiState.activeSheet == ActiveSheet.SETTINGS,
      enter = fadeIn(animationSpec = tween(180)),
      exit = fadeOut(animationSpec = tween(150)),
    ) {
      ControlCenterOverlay(
        hasVideo = prefs.hasVideo,
        videoFileName = prefs.videoFileName,
        volume = prefs.volume,
        brightness = prefs.brightness,
        globalBrightness = prefs.globalBrightness,
        effectiveBrightness = prefs.effectiveBrightness,
        isStopped = prefs.isStopped,
        onVolumeChange = onVolumeChange,
        onBrightnessChange = onBrightnessChange,
        onGlobalBrightnessChange = onGlobalBrightnessChange,
        onTogglePlayback = onTogglePlayback,
        onDismiss = onCloseSheets,
      )
    }
  }
}

@Composable
private fun SettingsFloatingButton(
  isExporting: Boolean,
  onTap: () -> Unit,
  onLongPressExport: () -> Unit,
) {
  val holdProgress = remember { Animatable(0f) }
  val haptic = LocalHapticFeedback.current
  val settingsDesc = stringResource(R.string.cd_settings)

  Surface(
    modifier =
      Modifier.size(52.dp)
        .shadow(
          elevation = 8.dp,
          shape = CircleShape,
          ambientColor = InkPrimary.copy(alpha = 0.10f),
          spotColor = InkPrimary.copy(alpha = 0.10f),
        )
        .clip(CircleShape)
        .semantics {
          role = Role.Button
          contentDescription = settingsDesc
        }
        .pointerInput(isExporting) {
          coroutineScope {
            awaitEachGesture {
              awaitFirstDown(requireUnconsumed = false)
              var triggeredExport = false
              val holdJob =
                if (!isExporting) {
                  launch {
                    holdProgress.snapTo(0f)
                    holdProgress.animateTo(
                      targetValue = 1f,
                      animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
                    )
                    triggeredExport = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPressExport()
                  }
                } else {
                  null
                }

              val up = waitForUpOrCancellation()
              holdJob?.cancel()
              launch { holdProgress.snapTo(0f) }

              if (up != null && !triggeredExport) {
                onTap()
              }
            }
          }
        }
        .drawWithContent {
          drawContent()
          val progress = holdProgress.value
          if (progress > 0f) {
            drawArc(
              color = AccentCobalt,
              startAngle = -90f,
              sweepAngle = 360f * progress,
              useCenter = false,
              style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
          }
        }
        .testTag("open_settings_button"),
    shape = CircleShape,
    color = SurfaceGlass,
    border = BorderStroke(1.dp, BorderGlass),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Outlined.Settings,
        contentDescription = null,
        tint = InkPrimary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun EmptyViewportIndicator(modifier: Modifier = Modifier) {
  Column(
    modifier =
      modifier
        .offset(y = (-24).dp)
        .drawBehind {
          drawRoundRect(
            color = WireframeStroke,
            cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
            style =
              Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f),
              ),
          )
        }
        .padding(horizontal = 26.dp, vertical = 22.dp)
        .testTag("empty_viewport_indicator"),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = Icons.Outlined.Movie,
      contentDescription = null,
      tint = InkMuted.copy(alpha = 0.75f),
      modifier = Modifier.size(22.dp),
    )

    Text(
      text = stringResource(R.string.viewport_title),
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = (-0.13).sp,
      color = InkMuted,
    )

    Row(
      modifier =
        Modifier.clip(CircleShape)
          .background(SurfaceSecondary)
          .padding(horizontal = 11.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(InkMuted))
      Text(
        text = stringResource(R.string.viewport_status_empty),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.1).sp,
        color = InkMuted,
      )
    }
  }
}

@Composable
private fun LiveVideoPreviewViewport(
  videoPath: String,
  videoVersion: Long,
  volume: Int,
  brightness: Int,
  isStopped: Boolean,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var isResumed by remember {
    mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
  }

  val (exoPlayer, trackSelector) = remember(context) {
    PlaybackCoordinator.buildLightweightExoPlayer(context, volume)
  }

  val isAppForeground by PlaybackCoordinator.isAppForeground.collectAsStateWithLifecycle()
  var preparedKey by remember { mutableStateOf<Pair<String, Long>?>(null) }

  DisposableEffect(lifecycleOwner, exoPlayer) {
    val errorListener =
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          preparedKey = null
          exoPlayer.stop()
        }
      }
    exoPlayer.addListener(errorListener)

    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> isResumed = true
        Lifecycle.Event.ON_PAUSE -> {
          isResumed = false
          preparedKey = null
          exoPlayer.pause()
          // Lepas decoder perangkat keras segera saat MainActivity kehilangan fokus
          exoPlayer.stop()
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      exoPlayer.removeListener(errorListener)
      exoPlayer.release()
    }
  }

  LaunchedEffect(volume) {
    exoPlayer.volume = (volume / 100f).coerceIn(0f, 1f)
    PlaybackCoordinator.syncAudioTrackState(trackSelector, volume)
  }

  LaunchedEffect(videoPath, videoVersion, isResumed, isStopped, isAppForeground) {
    if (!isResumed || !isAppForeground) {
      if (!isAppForeground && exoPlayer.playbackState != Player.STATE_IDLE) {
        preparedKey = null
        exoPlayer.pause()
        exoPlayer.stop()
      }
      return@LaunchedEffect
    }
    val currentKey = videoPath to videoVersion
    val file = File(videoPath)
    if (!file.exists()) {
      exoPlayer.stop()
      preparedKey = null
      return@LaunchedEffect
    }

    if (preparedKey != currentKey || exoPlayer.playbackState == Player.STATE_IDLE) {
      exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
      exoPlayer.prepare()
      preparedKey = currentKey
    }

    if (isStopped) {
      exoPlayer.pause()
    } else {
      exoPlayer.play()
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(BgDominant)) {
    AndroidView(
      factory = { ctx ->
        (LayoutInflater.from(ctx).inflate(R.layout.view_video_player, null, false) as PlayerView)
          .apply {
            player = exoPlayer
            useController = false
          }
      },
      update = { playerView ->
        if (playerView.player !== exoPlayer) {
          playerView.player = exoPlayer
        }
      },
      modifier = Modifier.fillMaxSize().testTag("video_preview_player"),
    )

    val dimmingAlpha = (1f - (brightness.coerceIn(1, 100) / 100f)).coerceIn(0f, 0.99f)
    if (dimmingAlpha > 0f) {
      Box(
        modifier =
          Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = dimmingAlpha))
            .testTag("video_brightness_dimmer"),
      )
    }
  }
}
