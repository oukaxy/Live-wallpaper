package com.example.apk

import android.content.Context
import android.net.Uri
import com.android.apksig.ApkSigner
import com.example.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pipeline on-device untuk mengemas ulang arsip APK, menyelaraskan entry STORED pada batas 4-byte
 * (serta page-alignment untuk native library), dan menandatangani APK menggunakan skema v1/v2/v3
 * via Google apksig agar valid diinstal dan siap diekspor ke File Manager (SAF).
 */
class ApkBuilderPipeline(private val context: Context) {

  private val appContext = context.applicationContext

  suspend fun buildSignedApk(
    embeddedVideoPath: String?,
    onProgress: (stageLabel: String, progress: Float) -> Unit,
  ): Result<File> =
    withContext(Dispatchers.IO) {
      val workDir = File(appContext.cacheDir, "apk_export").apply { mkdirs() }
      val packagedFile = File(workDir, "packaged_unaligned.apk")
      val alignedFile = File(workDir, "aligned_unsigned.apk")
      val signedFile = File(workDir, "LiveWallpaper_signed.apk")

      runCatching {
          cleanTempFiles(packagedFile, alignedFile, signedFile)

          // Tahap 1: Pengemasan arsip APK (resources.arsc, assets, classes.dex, manifest)
          onProgress(appContext.getString(R.string.export_apk_packaging), 0.25f)
          packageBaseApk(
            sourceApkPath = appContext.applicationInfo.sourceDir,
            embeddedVideoPath = embeddedVideoPath,
            outputFile = packagedFile,
          )

          // Tahap 2: Penyelarasan 4-byte (zipalign) pada entry STORED (assets, resources.arsc, .so)
          onProgress(appContext.getString(R.string.export_apk_aligning), 0.60f)
          alignApkEntries(inputFile = packagedFile, outputFile = alignedFile)
          packagedFile.delete()

          // Tahap 3: Penandatanganan APK (v1 + v2 + v3) dengan debug keystore bawaan
          onProgress(appContext.getString(R.string.export_apk_signing), 0.88f)
          signApkWithDebugKeystore(inputApk = alignedFile, outputApk = signedFile)
          alignedFile.delete()

          onProgress(appContext.getString(R.string.export_apk_signing), 1.0f)
          signedFile
        }
        .onFailure {
          cleanTempFiles(packagedFile, alignedFile, signedFile)
        }
    }

  suspend fun writeApkToSafUri(apkFile: File, destinationUri: Uri): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
          appContext.contentResolver.openOutputStream(destinationUri)?.use { out ->
            apkFile.inputStream().use { input ->
              input.copyTo(out, bufferSize = BUFFER_SIZE)
            }
            out.flush()
          } ?: throw IOException("Tidak dapat menulis ke tujuan penyimpanan")
        }
        .also { apkFile.delete() }
    }

  private fun packageBaseApk(
    sourceApkPath: String,
    embeddedVideoPath: String?,
    outputFile: File,
  ) {
    val videoFile = embeddedVideoPath?.let { File(it) }?.takeIf { it.exists() && it.isFile }
    val buffer = ByteArray(BUFFER_SIZE)

    ZipFile(sourceApkPath).use { sourceZip ->
      ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile), BUFFER_SIZE)).use { zos ->
        val entries = sourceZip.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          val name = entry.name

          // Lewati file tanda tangan lama agar apksigner membuat tanda tangan baru yang valid
          if (isSignatureEntry(name)) continue
          // Jika video baru tersedia, kita akan menulis ulang entry embedded_wallpaper.mp4
          if (videoFile != null && name == EMBEDDED_VIDEO_ASSET_PATH) continue

          val newEntry = ZipEntry(name)
          val shouldStoreUncompressed =
            entry.method == ZipEntry.STORED || mustStoreUncompressed(name)

          if (shouldStoreUncompressed) {
            if (entry.method == ZipEntry.STORED && entry.size >= 0 && entry.crc >= 0) {
              newEntry.method = ZipEntry.STORED
              newEntry.size = entry.size
              newEntry.compressedSize = entry.size
              newEntry.crc = entry.crc
            } else {
              val (size, crc) = computeSizeAndCrc { sourceZip.getInputStream(entry) }
              newEntry.method = ZipEntry.STORED
              newEntry.size = size
              newEntry.compressedSize = size
              newEntry.crc = crc
            }
          } else {
            newEntry.method = ZipEntry.DEFLATED
          }

          zos.putNextEntry(newEntry)
          if (!entry.isDirectory) {
            sourceZip.getInputStream(entry).use { input ->
              copyStream(input, zos, buffer)
            }
          }
          zos.closeEntry()
        }

        // Sematkan video wallpaper aktif ke assets/ tanpa kompresi (STORED)
        if (videoFile != null) {
          val (size, crc) = computeSizeAndCrc { videoFile.inputStream() }
          val videoEntry =
            ZipEntry(EMBEDDED_VIDEO_ASSET_PATH).apply {
              method = ZipEntry.STORED
              this.size = size
              this.compressedSize = size
              this.crc = crc
            }
          zos.putNextEntry(videoEntry)
          BufferedInputStream(videoFile.inputStream(), BUFFER_SIZE).use { input ->
            copyStream(input, zos, buffer)
          }
          zos.closeEntry()
        }
      }
    }
  }

  private fun alignApkEntries(inputFile: File, outputFile: File) {
    val buffer = ByteArray(BUFFER_SIZE)
    ZipFile(inputFile).use { zipFile ->
      val countingOut = CountingOutputStream(BufferedOutputStream(FileOutputStream(outputFile), BUFFER_SIZE))
      ZipOutputStream(countingOut).use { zos ->
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          val name = entry.name
          val newEntry = ZipEntry(name)

          if (entry.method == ZipEntry.STORED && !entry.isDirectory) {
            val alignBytes =
              if (name.endsWith(".so", ignoreCase = true)) PAGE_ALIGNMENT_BYTES
              else FOUR_BYTE_ALIGNMENT
            val nameByteLength = name.toByteArray(Charsets.UTF_8).size
            val headerEndWithoutExtra = countingOut.bytesWritten + ZIP_LOCAL_HEADER_FIXED_BYTES + nameByteLength
            val remainder = (headerEndWithoutExtra % alignBytes).toInt()
            val padBytes = if (remainder == 0) 0 else (alignBytes - remainder)

            newEntry.method = ZipEntry.STORED
            newEntry.size = entry.size
            newEntry.compressedSize = entry.size
            newEntry.crc = entry.crc
            if (padBytes > 0) {
              newEntry.extra = ByteArray(padBytes)
            }
          } else {
            newEntry.method = if (entry.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
            if (newEntry.method == ZipEntry.STORED) {
              newEntry.size = entry.size
              newEntry.compressedSize = entry.size
              newEntry.crc = entry.crc
            }
          }

          zos.putNextEntry(newEntry)
          if (!entry.isDirectory) {
            zipFile.getInputStream(entry).use { input ->
              copyStream(input, zos, buffer)
            }
          }
          zos.closeEntry()
        }
      }
    }
  }

  private fun signApkWithDebugKeystore(inputApk: File, outputApk: File) {
    val keyStore = KeyStore.getInstance("PKCS12")
    appContext.resources.openRawResource(R.raw.debug_signing).use { ksStream ->
      keyStore.load(ksStream, DEBUG_KEYSTORE_PASSWORD)
    }

    val privateKey =
      keyStore.getKey(DEBUG_KEY_ALIAS, DEBUG_KEYSTORE_PASSWORD) as? PrivateKey
        ?: throw IllegalStateException("Debug signing private key tidak ditemukan")
    val certChain =
      keyStore.getCertificateChain(DEBUG_KEY_ALIAS)?.filterIsInstance<X509Certificate>()
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("Debug signing certificate tidak ditemukan")

    val signerConfig =
      ApkSigner.SignerConfig.Builder(DEBUG_SIGNER_NAME, privateKey, certChain).build()

    ApkSigner.Builder(listOf(signerConfig))
      .setInputApk(inputApk)
      .setOutputApk(outputApk)
      .setV1SigningEnabled(true)
      .setV2SigningEnabled(true)
      .setV3SigningEnabled(true)
      .setMinSdkVersion(24)
      .build()
      .sign()
  }

  private fun mustStoreUncompressed(name: String): Boolean {
    return name == "resources.arsc" ||
      name.startsWith("assets/") ||
      name.endsWith(".so", ignoreCase = true)
  }

  private fun isSignatureEntry(name: String): Boolean {
    if (!name.startsWith("META-INF/")) return false
    return name == "META-INF/MANIFEST.MF" ||
      name.endsWith(".SF", ignoreCase = true) ||
      name.endsWith(".RSA", ignoreCase = true) ||
      name.endsWith(".DSA", ignoreCase = true) ||
      name.endsWith(".EC", ignoreCase = true)
  }

  private inline fun computeSizeAndCrc(openStream: () -> InputStream): Pair<Long, Long> {
    val crc = CRC32()
    var total = 0L
    val buffer = ByteArray(BUFFER_SIZE)
    openStream().use { input ->
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        crc.update(buffer, 0, read)
        total += read
      }
    }
    return total to crc.value
  }

  private fun copyStream(input: InputStream, output: OutputStream, buffer: ByteArray) {
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
      output.write(buffer, 0, read)
    }
  }

  private fun cleanTempFiles(vararg files: File) {
    files.forEach { if (it.exists()) it.delete() }
  }

  private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var bytesWritten: Long = 0L
      private set

    override fun write(b: Int) {
      out.write(b)
      bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      out.write(b, off, len)
      bytesWritten += len
    }
  }

  companion object {
    const val EMBEDDED_VIDEO_ASSET_PATH = "assets/embedded_wallpaper.mp4"
    const val EMBEDDED_VIDEO_ASSET_NAME = "embedded_wallpaper.mp4"

    private const val BUFFER_SIZE = 64 * 1024
    private const val ZIP_LOCAL_HEADER_FIXED_BYTES = 30L
    private const val FOUR_BYTE_ALIGNMENT = 4
    private const val PAGE_ALIGNMENT_BYTES = 16384

    private const val DEBUG_SIGNER_NAME = "AndroidDebug"
    private const val DEBUG_KEY_ALIAS = "androiddebugkey"
    private val DEBUG_KEYSTORE_PASSWORD = "android".toCharArray()
  }
}
