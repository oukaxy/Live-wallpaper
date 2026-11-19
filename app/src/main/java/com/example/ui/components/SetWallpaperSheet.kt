package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AccentCobalt
import com.example.ui.theme.BgDominant
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.DragHandleColor
import com.example.ui.theme.InkInverse
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.ScrimOverlay
import com.example.ui.theme.SurfaceSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetWallpaperSheet(
  hasVideo: Boolean,
  videoFileName: String,
  isCopyingVideo: Boolean,
  onPickVideoClick: () -> Unit,
  onApplyWallpaper: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    sheetMaxWidth = 480.dp,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor = BgDominant,
    scrimColor = ScrimOverlay,
    dragHandle = null,
    contentWindowInsets = { WindowInsets.navigationBars },
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
    ) {
      // Drag handle matching blueprint
      Box(
        modifier =
          Modifier.align(Alignment.CenterHorizontally)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(DragHandleColor),
      )

      Spacer(modifier = Modifier.height(14.dp))

      // Header row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.sheet_set_wallpaper_title),
          fontSize = 15.5.sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = (-0.2).sp,
          color = InkPrimary,
        )

        Box(
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .size(30.dp)
              .clip(CircleShape)
              .background(SurfaceSecondary)
              .clickable(onClick = onDismiss)
              .testTag("close_set_wallpaper_button"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_close_set_wallpaper),
            tint = InkMuted,
            modifier = Modifier.size(14.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Progressive Disclosure Flow (State 1 Initial vs State 2 Configured)
      AnimatedContent(
        targetState = hasVideo,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "SetWallpaperDisclosure",
      ) { videoReady ->
        if (!videoReady) {
          // State 1: Initial — Only Gallery Selection Card
          Surface(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(enabled = !isCopyingVideo, onClick = onPickVideoClick)
                .testTag("pick_video_gallery_card"),
            shape = RoundedCornerShape(18.dp),
            color = SurfaceSecondary,
            border = BorderStroke(1.dp, BorderHairline),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier =
                  Modifier.size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgDominant),
                contentAlignment = Alignment.Center,
              ) {
                if (isCopyingVideo) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AccentCobalt,
                    strokeWidth = 2.dp,
                  )
                } else {
                  Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = null,
                    tint = AccentCobalt,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }

              Spacer(modifier = Modifier.width(14.dp))

              Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                  text =
                    if (isCopyingVideo) stringResource(R.string.copying_video)
                    else stringResource(R.string.pick_video_gallery),
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Medium,
                  letterSpacing = (-0.14).sp,
                  color = InkPrimary,
                )
                Text(
                  text = stringResource(R.string.pick_video_formats),
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Normal,
                  letterSpacing = (-0.05).sp,
                  color = InkMuted,
                )
              }
            }
          }
        } else {
          // State 2: After Video Setup — Summary Card + Target Screen Pills + Apply CTA
          Column(modifier = Modifier.fillMaxWidth()) {
            Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(18.dp),
              color = SurfaceSecondary,
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Row(
                  modifier = Modifier.weight(1f),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Box(
                    modifier =
                      Modifier.size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgDominant),
                    contentAlignment = Alignment.Center,
                  ) {
                    if (isCopyingVideo) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = AccentCobalt,
                        strokeWidth = 2.dp,
                      )
                    } else {
                      Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        tint = InkPrimary,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  }

                  Spacer(modifier = Modifier.width(10.dp))

                  Text(
                    text = videoFileName.ifBlank { stringResource(R.string.default_video_filename) },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.13).sp,
                    color = InkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }

                TextButton(
                  onClick = onPickVideoClick,
                  enabled = !isCopyingVideo,
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("change_video_button"),
                ) {
                  Text(
                    text = stringResource(R.string.action_change_video),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.1).sp,
                    color = AccentCobalt,
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Full-Width Apply CTA ('Terapkan Wallpaper')
            Button(
              onClick = onApplyWallpaper,
              enabled = !isCopyingVideo,
              shape = CircleShape,
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = AccentCobalt,
                  contentColor = InkInverse,
                ),
              modifier =
                Modifier.fillMaxWidth()
                  .height(48.dp)
                  .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    ambientColor = AccentCobalt.copy(alpha = 0.38f),
                    spotColor = AccentCobalt.copy(alpha = 0.38f),
                  )
                  .testTag("apply_wallpaper_button"),
            ) {
              Text(
                text = stringResource(R.string.cta_apply_wallpaper),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.14).sp,
              )
            }
          }
        }
      }
    }
  }
}
