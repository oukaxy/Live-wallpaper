package com.example

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.media.PlaybackCoordinator
import com.example.service.LiveWallpaperService
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.LiveWallpaperTheme

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  private val pickVideoLauncher =
    registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        viewModel.onVideoPicked(uri)
      }
    }

  private val createApkDocumentLauncher =
    registerForActivityResult(
      ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri ->
      viewModel.onSafDocumentResult(uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      LiveWallpaperTheme {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(uiState.pendingSafApkPath) {
          if (uiState.pendingSafApkPath != null) {
            createApkDocumentLauncher.launch(getString(R.string.export_apk_filename))
          }
        }

        LaunchedEffect(Unit) {
          viewModel.launchChooserEvents.collect {
            PlaybackCoordinator.setAppForeground(false)
            kotlinx.coroutines.delay(100L)
            launchLiveWallpaperChooser()
          }
        }

        MainScreen(
          uiState = uiState,
          onOpenSetWallpaper = viewModel::openSetWallpaperSheet,
          onOpenSettings = viewModel::openSettingsSheet,
          onExportApk = viewModel::startApkExport,
          onCloseSheets = viewModel::closeSheets,
          onPickVideoClick = {
            pickVideoLauncher.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
            )
          },
          onApplyWallpaper = viewModel::onWallpaperApplied,
          onVolumeChange = viewModel::onVolumeChange,
          onBrightnessChange = viewModel::onBrightnessChange,
          onGlobalBrightnessChange = viewModel::onGlobalBrightnessChange,
          onTogglePlayback = viewModel::onTogglePlayback,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    PlaybackCoordinator.setAppForeground(true)
    notifyColorsIfLiveWallpaperActive()
  }

  override fun onPause() {
    PlaybackCoordinator.setAppForeground(false)
    super.onPause()
  }

  private fun notifyColorsIfLiveWallpaperActive() {
    val activePackage = WallpaperManager.getInstance(this).wallpaperInfo?.packageName
    if (activePackage == packageName) {
      PlaybackCoordinator.requestColorRefresh()
    }
  }

  private fun launchLiveWallpaperChooser() {
    val serviceComponent = ComponentName(this, LiveWallpaperService::class.java)
    val changeIntent =
      Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
        putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, serviceComponent)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    try {
      startActivity(changeIntent)
    } catch (_: Throwable) {
      try {
        val chooserIntent =
          Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        startActivity(chooserIntent)
      } catch (_: Throwable) {
        try {
          val fallbackIntent =
            Intent.createChooser(
                Intent(Intent.ACTION_SET_WALLPAPER).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                getString(R.string.app_name),
              )
              .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
          startActivity(fallbackIntent)
        } catch (_: Throwable) {
          Toast.makeText(
              this,
              getString(R.string.error_open_wallpaper_chooser),
              Toast.LENGTH_LONG,
            )
            .show()
        }
      }
    }
  }
}
