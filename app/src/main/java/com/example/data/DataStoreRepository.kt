package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.wallpaperDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "live_wallpaper_preferences")

class DataStoreRepository(context: Context) {

  private val appContext = context.applicationContext
  private val dataStore = appContext.wallpaperDataStore

  val preferencesFlow: Flow<WallpaperPreferences> =
    dataStore.data
      .catch { exception ->
        if (exception is IOException) {
          emit(emptyPreferences())
        } else {
          throw exception
        }
      }
      .map { prefs ->
        val rawPath = prefs[KEY_VIDEO_PATH]
        val validPath = rawPath?.takeIf { File(it).exists() }
        WallpaperPreferences(
          videoPath = validPath,
          videoFileName = prefs[KEY_VIDEO_FILE_NAME] ?: DEFAULT_FILE_NAME,
          videoVersion = prefs[KEY_VIDEO_VERSION] ?: 0L,
          volume = (prefs[KEY_VOLUME] ?: DEFAULT_VOLUME).coerceIn(0, 100),
          brightness = (prefs[KEY_BRIGHTNESS] ?: DEFAULT_BRIGHTNESS).coerceIn(10, 100),
          globalBrightness =
            (prefs[KEY_GLOBAL_BRIGHTNESS] ?: DEFAULT_BRIGHTNESS).coerceIn(10, 100),
          isStopped = prefs[KEY_IS_STOPPED] ?: false,
        )
      }

  suspend fun copyVideoToInternalStorage(uri: Uri): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val displayName = resolveFileName(uri) ?: DEFAULT_FILE_NAME
        val version = System.currentTimeMillis()
        // Gunakan nama file berversi agar video baru tidak menimpa file yang sedang dibuka ExoPlayer
        val targetFile = File(appContext.filesDir, "${VIDEO_FILE_PREFIX}$version.mp4")
        val tempFile = File(appContext.filesDir, "${VIDEO_FILE_PREFIX}$version.mp4.tmp")

        appContext.contentResolver.openInputStream(uri)?.use { rawInput ->
          BufferedInputStream(rawInput, COPY_BUFFER_SIZE).use { input ->
            BufferedOutputStream(tempFile.outputStream(), COPY_BUFFER_SIZE).use { output ->
              input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
              output.flush()
            }
          }
        } ?: throw IOException("Tidak dapat membuka file video terpilih")

        if (!tempFile.renameTo(targetFile)) {
          tempFile.copyTo(targetFile, overwrite = true)
          tempFile.delete()
        }

        dataStore.edit { prefs ->
          prefs[KEY_VIDEO_PATH] = targetFile.absolutePath
          prefs[KEY_VIDEO_FILE_NAME] = displayName
          prefs[KEY_VIDEO_VERSION] = version
          prefs[KEY_BRIGHTNESS] = DEFAULT_BRIGHTNESS
          prefs[KEY_IS_STOPPED] = false
        }

        cleanupOldVideoFiles(keepFileName = targetFile.name)
        com.example.media.PlaybackCoordinator.cacheStaticFallbackFrame(
          appContext,
          targetFile.absolutePath,
        )
        targetFile.absolutePath
      }
    }

  suspend fun ensureEmbeddedVideoLoaded() {
    withContext(Dispatchers.IO) {
      val existingPath =
        runCatching { dataStore.data.first()[KEY_VIDEO_PATH] }.getOrNull()
      if (!existingPath.isNullOrBlank() && File(existingPath).exists()) {
        return@withContext
      }

      val targetFile = File(appContext.filesDir, INTERNAL_VIDEO_FILE_NAME)
      if (targetFile.exists()) {
        val version = System.currentTimeMillis()
        dataStore.edit { prefs ->
          prefs[KEY_VIDEO_PATH] = targetFile.absolutePath
          if (prefs[KEY_VIDEO_FILE_NAME].isNullOrBlank()) {
            prefs[KEY_VIDEO_FILE_NAME] = DEFAULT_FILE_NAME
          }
          prefs[KEY_VIDEO_VERSION] = version
        }
        return@withContext
      }

      val assetExists =
        runCatching {
            appContext.assets.open(com.example.apk.ApkBuilderPipeline.EMBEDDED_VIDEO_ASSET_NAME).use {
              rawInput ->
              BufferedInputStream(rawInput, COPY_BUFFER_SIZE).use { input ->
                BufferedOutputStream(targetFile.outputStream(), COPY_BUFFER_SIZE).use { output ->
                  input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                  output.flush()
                }
              }
            }
            true
          }
          .getOrDefault(false)

      if (assetExists && targetFile.exists()) {
        val version = System.currentTimeMillis()
        dataStore.edit { prefs ->
          prefs[KEY_VIDEO_PATH] = targetFile.absolutePath
          prefs[KEY_VIDEO_FILE_NAME] = DEFAULT_FILE_NAME
          prefs[KEY_VIDEO_VERSION] = version
        }
        com.example.media.PlaybackCoordinator.cacheStaticFallbackFrame(
          appContext,
          targetFile.absolutePath,
        )
      }
    }
  }

  suspend fun updateVolume(volume: Int) {
    val clamped = volume.coerceIn(0, 100)
    dataStore.edit { prefs -> prefs[KEY_VOLUME] = clamped }
  }

  suspend fun updateBrightness(brightness: Int) {
    val clamped = brightness.coerceIn(10, 100)
    dataStore.edit { prefs -> prefs[KEY_BRIGHTNESS] = clamped }
  }

  suspend fun updateGlobalBrightness(globalBrightness: Int) {
    val clamped = globalBrightness.coerceIn(10, 100)
    dataStore.edit { prefs -> prefs[KEY_GLOBAL_BRIGHTNESS] = clamped }
  }

  suspend fun setStopped(stopped: Boolean) {
    dataStore.edit { prefs -> prefs[KEY_IS_STOPPED] = stopped }
  }

  private fun cleanupOldVideoFiles(keepFileName: String) {
    runCatching {
      appContext.filesDir
        .listFiles { file ->
          file.isFile &&
            file.name != keepFileName &&
            (file.name == INTERNAL_VIDEO_FILE_NAME || file.name.startsWith(VIDEO_FILE_PREFIX))
        }
        ?.forEach { oldFile -> oldFile.delete() }
    }
  }

  private fun resolveFileName(uri: Uri): String? {
    return runCatching {
        appContext.contentResolver
          .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (index >= 0) cursor.getString(index) else null
            } else {
              null
            }
          }
      }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
  }

  companion object {
    private const val INTERNAL_VIDEO_FILE_NAME = "active_wallpaper_video.mp4"
    private const val VIDEO_FILE_PREFIX = "active_wallpaper_video_"
    private const val DEFAULT_FILE_NAME = "video_wallpaper.mp4"
    private const val DEFAULT_VOLUME = 70
    private const val DEFAULT_BRIGHTNESS = 100
    private const val COPY_BUFFER_SIZE = 64 * 1024

    private val KEY_VIDEO_PATH = stringPreferencesKey("video_path")
    private val KEY_VIDEO_FILE_NAME = stringPreferencesKey("video_file_name")
    private val KEY_VIDEO_VERSION = longPreferencesKey("video_version")
    private val KEY_VOLUME = intPreferencesKey("volume")
    private val KEY_BRIGHTNESS = intPreferencesKey("brightness")
    private val KEY_GLOBAL_BRIGHTNESS = intPreferencesKey("global_brightness")
    private val KEY_IS_STOPPED = booleanPreferencesKey("is_stopped")
  }
}
