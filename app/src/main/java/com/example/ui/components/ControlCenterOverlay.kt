package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.ControlCenterScrim
import com.example.ui.theme.ControlCenterShadow
import com.example.ui.theme.ControlCenterTrack
import com.example.ui.theme.ControlCenterTrackFill
import com.example.ui.theme.ControlCenterTrackIconDark
import com.example.ui.theme.InkInverse
import kotlin.math.roundToInt

private enum class ControlPanelMode {
  MAIN,
  BRIGHTNESS,
  VOLUME,
}

@Composable
fun ControlCenterOverlay(
  hasVideo: Boolean,
  videoFileName: String?,
  volume: Int,
  brightness: Int,
  globalBrightness: Int,
  effectiveBrightness: Int,
  isStopped: Boolean,
  onVolumeChange: (Int) -> Unit,
  onBrightnessChange: (Int) -> Unit,
  onGlobalBrightnessChange: (Int) -> Unit,
  onTogglePlayback: () -> Unit,
  onDismiss: () -> Unit,
) {
  var panelMode by remember { mutableStateOf(ControlPanelMode.MAIN) }

  BackHandler {
    if (panelMode != ControlPanelMode.MAIN) {
      panelMode = ControlPanelMode.MAIN
    } else {
      onDismiss()
    }
  }

  val backdropInteraction = remember { MutableInteractionSource() }
  val titleText =
    if (hasVideo) {
      videoFileName ?: stringResource(R.string.default_video_filename)
    } else {
      stringResource(R.string.viewport_status_empty)
    }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(ControlCenterScrim)
        .clickable(
          interactionSource = backdropInteraction,
          indication = null,
        ) {
          if (panelMode != ControlPanelMode.MAIN) {
            panelMode = ControlPanelMode.MAIN
          } else {
            onDismiss()
          }
        }
        .testTag("control_center_overlay"),
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize()
          .statusBarsPadding()
          .navigationBarsPadding(),
    ) {
      AnimatedContent(
        targetState = panelMode,
        transitionSpec = {
          fadeIn(animationSpec = tween(160)) togetherWith fadeOut(animationSpec = tween(130))
        },
        label = "control_center_mode",
      ) { mode ->
        when (mode) {
          ControlPanelMode.MAIN -> {
            ControlCenterMainView(
              titleText = titleText,
              volume = volume,
              isStopped = isStopped,
              onOpenBrightness = { panelMode = ControlPanelMode.BRIGHTNESS },
              onOpenVolume = { panelMode = ControlPanelMode.VOLUME },
              onTogglePlayback = onTogglePlayback,
              onDismiss = onDismiss,
            )
          }
          ControlPanelMode.BRIGHTNESS -> {
            ControlCenterBrightnessView(
              independentBrightness = brightness,
              globalBrightness = globalBrightness,
              effectiveBrightness = effectiveBrightness,
              onIndependentChange = onBrightnessChange,
              onGlobalChange = onGlobalBrightnessChange,
            )
          }
          ControlPanelMode.VOLUME -> {
            ControlCenterVolumeView(
              volume = volume,
              onVolumeChange = onVolumeChange,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ControlCenterMainView(
  titleText: String,
  volume: Int,
  isStopped: Boolean,
  onOpenBrightness: () -> Unit,
  onOpenVolume: () -> Unit,
  onTogglePlayback: () -> Unit,
  onDismiss: () -> Unit,
) {
  val textShadow = remember {
    Shadow(
      color = ControlCenterShadow,
      offset = Offset(3f, 3f),
      blurRadius = 6f,
    )
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // Top-right close icon (pure transparent background, zero rectangle smudge)
    FloatingShadowIconButton(
      icon = Icons.Rounded.Close,
      contentDescription = stringResource(R.string.cd_close_settings),
      onClick = onDismiss,
      iconSize = 24.dp,
      modifier =
        Modifier.align(Alignment.TopEnd)
          .padding(top = 10.dp, end = 10.dp)
          .testTag("close_settings_button"),
    )

    // Right-center vertical floating icons (Kecerahan + Volume) — pure floating glyphs matching res/FM.xml
    Column(
      modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      FloatingShadowIconButton(
        icon = Icons.Outlined.WbSunny,
        contentDescription = stringResource(R.string.cd_brightness_control),
        onClick = onOpenBrightness,
        iconSize = 26.dp,
        modifier = Modifier.testTag("brightness_control_button"),
      )

      FloatingShadowIconButton(
        icon =
          if (volume == 0) Icons.AutoMirrored.Outlined.VolumeOff
          else Icons.AutoMirrored.Outlined.VolumeUp,
        contentDescription = stringResource(R.string.cd_volume_control),
        onClick = onOpenVolume,
        iconSize = 26.dp,
        modifier = Modifier.testTag("volume_control_button"),
      )
    }

    // Bottom-center video title + centered floating Play/Pause button (pure glyph with drop shadow)
    Column(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .widthIn(max = 360.dp)
          .fillMaxWidth()
          .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = titleText,
        style =
          TextStyle(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = InkInverse,
            shadow = textShadow,
          ),
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().basicMarquee(),
      )

      FloatingShadowIconButton(
        icon = if (isStopped) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
        contentDescription = stringResource(R.string.cd_toggle_playback),
        onClick = onTogglePlayback,
        iconSize = 34.dp,
        modifier = Modifier.testTag("toggle_playback_button"),
      )
    }
  }
}

@Composable
private fun ControlCenterBrightnessView(
  independentBrightness: Int,
  globalBrightness: Int,
  effectiveBrightness: Int,
  onIndependentChange: (Int) -> Unit,
  onGlobalChange: (Int) -> Unit,
) {
  val textShadow = remember {
    Shadow(
      color = ControlCenterShadow,
      offset = Offset(3f, 3f),
      blurRadius = 8f,
    )
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val sliderHeight = minOf(300.dp, (maxHeight * 0.56f).coerceAtLeast(180.dp))

    // Left/center-aligned effective brightness readout + formula summary (matching res/FM.xml marginRight=116dp)
    Column(
      modifier =
        Modifier.align(Alignment.Center)
          .padding(start = 20.dp, end = 120.dp, bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "$effectiveBrightness%",
        style =
          TextStyle(
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = InkInverse,
            fontFeatureSettings = "tnum",
            shadow = textShadow,
          ),
        modifier = Modifier.testTag("control_center_value_readout"),
      )

      Text(
        text =
          stringResource(
            R.string.brightness_formula_summary,
            independentBrightness,
            globalBrightness,
            effectiveBrightness,
          ),
        style =
          TextStyle(
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            color = InkInverse,
            shadow = textShadow,
          ),
        textAlign = TextAlign.Center,
      )
    }

    // Two side-by-side vertical pill sliders on the right (Khusus + Global, exact match to res/FM.xml)
    Row(
      modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      VerticalPillSeekBar(
        value = independentBrightness,
        minValue = 10,
        maxValue = 100,
        topTitle = stringResource(R.string.label_brightness_independent),
        topSubtitle = "$independentBrightness%",
        icon = Icons.Outlined.WbSunny,
        contentDescription = stringResource(R.string.cd_brightness_independent),
        onValueChange = onIndependentChange,
        modifier =
          Modifier.width(46.dp)
            .height(sliderHeight)
            .testTag("brightness_slider"),
      )

      VerticalPillSeekBar(
        value = globalBrightness,
        minValue = 10,
        maxValue = 100,
        topTitle = stringResource(R.string.label_brightness_global),
        topSubtitle = "$globalBrightness%",
        icon = Icons.Outlined.WbSunny,
        contentDescription = stringResource(R.string.cd_brightness_global),
        onValueChange = onGlobalChange,
        modifier =
          Modifier.width(46.dp)
            .height(sliderHeight)
            .testTag("global_brightness_slider"),
      )
    }
  }
}

@Composable
private fun ControlCenterVolumeView(
  volume: Int,
  onVolumeChange: (Int) -> Unit,
) {
  val textShadow = remember {
    Shadow(
      color = ControlCenterShadow,
      offset = Offset(3f, 3f),
      blurRadius = 8f,
    )
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val sliderHeight = minOf(300.dp, (maxHeight * 0.56f).coerceAtLeast(180.dp))

    Column(
      modifier =
        Modifier.align(Alignment.Center)
          .padding(start = 24.dp, end = 76.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = "$volume%",
        style =
          TextStyle(
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = InkInverse,
            fontFeatureSettings = "tnum",
            shadow = textShadow,
          ),
        modifier = Modifier.testTag("control_center_value_readout"),
      )

      Text(
        text = stringResource(R.string.label_volume_video),
        style =
          TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = InkInverse,
            shadow = textShadow,
          ),
        textAlign = TextAlign.Center,
      )

      Text(
        text =
          if (volume == 0) stringResource(R.string.control_center_muted_hint)
          else stringResource(R.string.control_center_slider_hint),
        style =
          TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = InkInverse.copy(alpha = 0.84f),
            shadow = textShadow,
          ),
        textAlign = TextAlign.Center,
      )
    }

    VerticalPillSeekBar(
      value = volume,
      minValue = 0,
      maxValue = 100,
      topTitle = null,
      topSubtitle = "$volume%",
      icon =
        if (volume == 0) Icons.AutoMirrored.Outlined.VolumeOff
        else Icons.AutoMirrored.Outlined.VolumeUp,
      contentDescription = stringResource(R.string.cd_volume_control),
      onValueChange = onVolumeChange,
      modifier =
        Modifier.align(Alignment.CenterEnd)
          .padding(end = 10.dp)
          .width(46.dp)
          .height(sliderHeight)
          .testTag("volume_slider"),
    )
  }
}

@Composable
private fun VerticalPillSeekBar(
  value: Int,
  minValue: Int,
  maxValue: Int,
  topTitle: String?,
  topSubtitle: String,
  icon: ImageVector,
  contentDescription: String,
  onValueChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val fraction = ((value - minValue).toFloat() / (maxValue - minValue).toFloat()).coerceIn(0f, 1f)
  var heightPx by remember { mutableFloatStateOf(0f) }
  val currentOnValueChange by rememberUpdatedState(onValueChange)
  val pillShape = RoundedCornerShape(12.dp)

  fun updateFromY(y: Float) {
    val h = heightPx
    if (h <= 0f) return
    val nextFraction = (1f - (y / h)).coerceIn(0f, 1f)
    val nextValue = (minValue + nextFraction * (maxValue - minValue)).roundToInt()
      .coerceIn(minValue, maxValue)
    currentOnValueChange(nextValue)
  }

  Box(
    modifier =
      modifier
        .shadow(
          elevation = 8.dp,
          shape = pillShape,
          ambientColor = Color.Black.copy(alpha = 0.35f),
          spotColor = Color.Black.copy(alpha = 0.35f),
        )
        .clip(pillShape)
        .background(ControlCenterTrack)
        .onSizeChanged { size -> heightPx = size.height.toFloat() }
        .semantics {
          this.contentDescription = contentDescription
          progressBarRangeInfo =
            ProgressBarRangeInfo(
              current = value.toFloat(),
              range = minValue.toFloat()..maxValue.toFloat(),
            )
        }
        .pointerInput(minValue, maxValue) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            updateFromY(down.position.y)
            while (true) {
              val event = awaitPointerEvent()
              val activeChange = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!activeChange.pressed) {
                activeChange.consume()
                break
              }
              activeChange.consume()
              updateFromY(activeChange.position.y)
            }
          }
        },
  ) {
    // Upward white fill clipped cleanly inside 12dp pill
    Box(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .fillMaxWidth()
          .fillMaxHeight(fraction)
          .background(ControlCenterTrackFill),
    )

    // Top label(s) inside the pill (matching res/FM.xml marginTop=14dp & marginTop=32dp)
    val topLabelColor = if (fraction >= 0.88f) ControlCenterTrackIconDark else InkInverse
    Column(
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (topTitle != null) {
        Text(
          text = topTitle,
          style =
            TextStyle(
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = topLabelColor,
            ),
          maxLines = 1,
        )
      }
      Text(
        text = topSubtitle,
        style =
          TextStyle(
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum",
            color = topLabelColor,
          ),
        maxLines = 1,
      )
    }

    // Bottom icon inside the pill
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (fraction >= 0.14f) ControlCenterTrackIconDark else InkInverse,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(20.dp),
    )
  }
}

@Composable
private fun FloatingShadowIconButton(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  iconSize: Dp,
  modifier: Modifier = Modifier,
) {
  val interactionSource = remember { MutableInteractionSource() }
  Box(
    modifier =
      modifier
        .minimumInteractiveComponentSize()
        .size(48.dp)
        .clip(CircleShape)
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(bounded = true, color = InkInverse),
          role = Role.Button,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    // Glyph drop shadow directly under the icon with zero background box
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = ControlCenterShadow,
      modifier = Modifier.offset(x = 1.5.dp, y = 2.dp).size(iconSize),
    )
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = InkInverse,
      modifier = Modifier.size(iconSize),
    )
  }
}
