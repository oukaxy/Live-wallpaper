package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.apk.ApkBuilderPipeline
import com.example.data.DataStoreRepository
import com.example.data.WallpaperPreferences
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ActiveSheet {
  SET_WALLPAPER,
  SETTINGS,
}

data class ApkExportProgress(
  val stageLabel: String,
  val progress: Float,
)

data class MainUiState(
  val preferences: WallpaperPreferences = WallpaperPreferences(),
  val activeSheet: ActiveSheet? = null,
  val isCopyingVideo: Boolean = false,
  val apkExportProgress: ApkExportProgress? = null,
  val pendingSafApkPath: String? = null,
  val toastMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = DataStoreRepository(application)
  private val apkBuilder = ApkBuilderPipeline(application)

  private val activeSheetFlow = MutableStateFlow<ActiveSheet?>(null)
  private val isCopyingVideoFlow = MutableStateFlow(false)
  private val apkExportProgressFlow = MutableStateFlow<ApkExportProgress?>(null)
  private val pendingSafApkPathFlow = MutableStateFlow<String?>(null)
  private val toastMessageFlow = MutableStateFlow<String?>(null)
  private val _launchChooserEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val launchChooserEvents: SharedFlow<Unit> = _launchChooserEvents.asSharedFlow()

  private var toastJob: Job? = null

  init {
    viewModelScope.launch { repository.ensureEmbeddedVideoLoaded() }
  }

  val uiState: StateFlow<MainUiState> =
    combine(
        repository.preferencesFlow,
        activeSheetFlow,
        isCopyingVideoFlow,
        apkExportProgressFlow,
        pendingSafApkPathFlow,
        toastMessageFlow,
      ) { values ->
        val prefs = values[0] as WallpaperPreferences
        val activeSheet = values[1] as ActiveSheet?
        val isCopying = values[2] as Boolean
        val exportProgress = values[3] as ApkExportProgress?
        val pendingApkPath = values[4] as String?
        val toast = values[5] as String?
        MainUiState(
          preferences = prefs,
          activeSheet = activeSheet,
          isCopyingVideo = isCopying,
          apkExportProgress = exportProgress,
          pendingSafApkPath = pendingApkPath,
          toastMessage = toast,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
      )

  fun openSetWallpaperSheet() {
    activeSheetFlow.value = ActiveSheet.SET_WALLPAPER
  }

  fun openSettingsSheet() {
    activeSheetFlow.value = ActiveSheet.SETTINGS
  }

  fun closeSheets() {
    activeSheetFlow.value = null
  }

  fun onVideoPicked(uri: Uri) {
    viewModelScope.launch {
      isCopyingVideoFlow.value = true
      val result = repository.copyVideoToInternalStorage(uri)
      isCopyingVideoFlow.value = false
      if (result.isFailure) {
        showToast(getApplication<Application>().getString(R.string.error_copy_video))
      }
    }
  }

  fun onVolumeChange(volume: Int) {
    viewModelScope.launch { repository.updateVolume(volume) }
  }

  fun onBrightnessChange(brightness: Int) {
    viewModelScope.launch { repository.updateBrightness(brightness) }
  }

  fun onGlobalBrightnessChange(globalBrightness: Int) {
    viewModelScope.launch { repository.updateGlobalBrightness(globalBrightness) }
  }

  fun onTogglePlayback() {
    val nextStopped = !uiState.value.preferences.isStopped
    viewModelScope.launch { repository.setStopped(nextStopped) }
  }

  fun onWallpaperApplied() {
    val videoPath = uiState.value.preferences.videoPath
    if (videoPath.isNullOrBlank() || !File(videoPath).exists()) return
    activeSheetFlow.value = null
    _launchChooserEvents.tryEmit(Unit)
  }

  fun startApkExport() {
    if (apkExportProgressFlow.value != null) return
    viewModelScope.launch {
      val currentVideoPath = uiState.value.preferences.videoPath
      val result =
        apkBuilder.buildSignedApk(
          embeddedVideoPath = currentVideoPath,
          onProgress = { label, progress ->
            apkExportProgressFlow.value = ApkExportProgress(label, progress)
          },
        )
      apkExportProgressFlow.value = null

      result
        .onSuccess { signedApk -> pendingSafApkPathFlow.value = signedApk.absolutePath }
        .onFailure {
          showToast(getApplication<Application>().getString(R.string.error_export_apk))
        }
    }
  }

  fun onSafDocumentResult(uri: Uri?) {
    val apkPath = pendingSafApkPathFlow.value
    pendingSafApkPathFlow.value = null
    if (apkPath == null) return

    val apkFile = File(apkPath)
    if (uri == null) {
      apkFile.delete()
      return
    }

    viewModelScope.launch {
      val writeResult = apkBuilder.writeApkToSafUri(apkFile, uri)
      if (writeResult.isSuccess) {
        showToast(getApplication<Application>().getString(R.string.toast_apk_saved))
      } else {
        showToast(getApplication<Application>().getString(R.string.error_export_apk))
      }
    }
  }

  private fun showToast(message: String) {
    toastJob?.cancel()
    toastMessageFlow.value = message
    toastJob =
      viewModelScope.launch {
        delay(2200L)
        toastMessageFlow.value = null
      }
  }
}
