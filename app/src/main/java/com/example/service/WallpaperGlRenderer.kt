package com.example.service

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Renderer OpenGL ES 2.0 untuk LiveWallpaperService:
 * - Mode video (brightness < 100%) melalui SurfaceTexture (OES).
 * - Mode frame diam / latar polos melalui EGL + GLES20 (tanpa pernah memanggil lockCanvas(),
 *   sehingga Surface tidak pernah terkunci ke NATIVE_WINDOW_API_CPU).
 */
class WallpaperGlRenderer {

  private enum class RenderMode {
    NONE,
    VIDEO,
    STATIC_BITMAP,
    SOLID_COLOR,
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private var glThread: HandlerThread? = null
  private var glHandler: Handler? = null

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
  private var eglConfig: EGLConfig? = null
  private var boundWindowSurface: Surface? = null

  private var oesProgramId = 0
  private var oesTextureId = 0
  private var surfaceTexture: SurfaceTexture? = null
  private var playerSurface: Surface? = null

  private var oesPosHandle = 0
  private var oesTexCoordHandle = 0
  private var oesTexMatrixHandle = 0
  private var oesScaleHandle = 0
  private var oesBrightnessHandle = 0

  private var bitmapProgramId = 0
  private var bitmapTextureId = 0
  private var bitmapPosHandle = 0
  private var bitmapTexCoordHandle = 0
  private var bitmapScaleHandle = 0
  private var bitmapBrightnessHandle = 0

  private val texMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
  private var surfaceWidth = 1
  private var surfaceHeight = 1
  private var videoWidth = 0
  private var videoHeight = 0
  private var solidColor = Color.BLACK
  @Volatile private var renderMode = RenderMode.NONE
  @Volatile private var brightnessFactor = 1f
  @Volatile private var hasFrame = false
  @Volatile private var isReleased = false

  private val vertexBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(VERTEX_COORDS.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(VERTEX_COORDS)
        position(0)
      }

  private val oesTexCoordBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(OES_TEX_COORDS.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(OES_TEX_COORDS)
        position(0)
      }

  private val bitmapTexCoordBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(BITMAP_TEX_COORDS.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(BITMAP_TEX_COORDS)
        position(0)
      }

  private fun ensureGlThread(): Handler {
    val existing = glHandler
    if (existing != null && glThread?.isAlive == true) {
      return existing
    }
    val thread = HandlerThread("LiveWallpaperGL").apply { start() }
    glThread = thread
    return Handler(thread.looper).also { glHandler = it }
  }

  fun initialize(
    windowSurface: Surface,
    width: Int,
    height: Int,
    initialBrightness: Float,
    onPlayerSurfaceReady: (Surface) -> Unit,
    onFallbackRequired: () -> Unit,
  ) {
    isReleased = false
    hasFrame = false
    renderMode = RenderMode.VIDEO
    brightnessFactor = initialBrightness.coerceIn(0.01f, 1f)
    surfaceWidth = width.coerceAtLeast(1)
    surfaceHeight = height.coerceAtLeast(1)

    val handler = ensureGlThread()
    handler.post {
      if (isReleased || !windowSurface.isValid) {
        releaseInternal()
        if (!isReleased) mainHandler.post { onFallbackRequired() }
        return@post
      }

      val eglReady = runCatching { ensureEglReady(windowSurface) && ensureOesProgram() }.getOrDefault(false)
      if (!eglReady) {
        releaseInternal()
        mainHandler.post { if (!isReleased) onFallbackRequired() }
        return@post
      }

      runCatching { playerSurface?.release() }
      playerSurface = null
      runCatching { surfaceTexture?.release() }
      surfaceTexture = null

      val st =
        SurfaceTexture(oesTextureId).apply {
          setOnFrameAvailableListener(
            {
              if (!isReleased && renderMode == RenderMode.VIDEO) {
                hasFrame = true
                drawCurrentFrame(updateTexImage = true)
              }
            },
            handler,
          )
        }
      surfaceTexture = st
      val surface = Surface(st)
      playerSurface = surface

      GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
      mainHandler.post {
        if (!isReleased && renderMode == RenderMode.VIDEO) {
          onPlayerSurfaceReady(surface)
        }
      }
    }
  }

  fun renderStaticBitmap(
    windowSurface: Surface,
    width: Int,
    height: Int,
    bitmap: Bitmap,
    brightness: Float,
    onFallbackRequired: (() -> Unit)? = null,
  ) {
    if (bitmap.isRecycled) return
    isReleased = false
    renderMode = RenderMode.STATIC_BITMAP
    brightnessFactor = brightness.coerceIn(0.01f, 1f)
    surfaceWidth = width.coerceAtLeast(1)
    surfaceHeight = height.coerceAtLeast(1)
    videoWidth = bitmap.width.coerceAtLeast(1)
    videoHeight = bitmap.height.coerceAtLeast(1)

    val handler = ensureGlThread()
    handler.post {
      if (isReleased || !windowSurface.isValid || bitmap.isRecycled) return@post

      val ok =
        runCatching {
            ensureEglReady(windowSurface) &&
              ensureBitmapProgram() &&
              uploadBitmapTexture(bitmap)
          }
          .getOrDefault(false)

      if (!ok) {
        releaseInternal()
        mainHandler.post { if (!isReleased) onFallbackRequired?.invoke() }
        return@post
      }

      hasFrame = true
      drawCurrentFrame(updateTexImage = false)
    }
  }

  fun renderSolidColor(
    windowSurface: Surface,
    width: Int,
    height: Int,
    color: Int,
  ) {
    isReleased = false
    renderMode = RenderMode.SOLID_COLOR
    solidColor = color
    surfaceWidth = width.coerceAtLeast(1)
    surfaceHeight = height.coerceAtLeast(1)

    val handler = ensureGlThread()
    handler.post {
      if (isReleased || !windowSurface.isValid) return@post
      val ok = runCatching { ensureEglReady(windowSurface) }.getOrDefault(false)
      if (!ok) return@post
      hasFrame = true
      drawCurrentFrame(updateTexImage = false)
    }
  }

  fun updateSurfaceSize(windowSurface: Surface, width: Int, height: Int) {
    glHandler?.post {
      if (isReleased || eglDisplay == EGL14.EGL_NO_DISPLAY || !windowSurface.isValid) return@post
      surfaceWidth = width.coerceAtLeast(1)
      surfaceHeight = height.coerceAtLeast(1)
      val config = eglConfig ?: return@post

      if (eglSurface == EGL14.EGL_NO_SURFACE || boundWindowSurface !== windowSurface) {
        destroyWindowEglSurface()
        boundWindowSurface = windowSurface
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface =
          runCatching {
              EGL14.eglCreateWindowSurface(eglDisplay, config, windowSurface, surfaceAttribs, 0)
            }
            .getOrDefault(EGL14.EGL_NO_SURFACE)
      }

      if (eglSurface != EGL14.EGL_NO_SURFACE && (hasFrame || renderMode == RenderMode.SOLID_COLOR)) {
        drawCurrentFrame(updateTexImage = false)
      }
    }
  }

  fun updateVideoSize(
    width: Int,
    height: Int,
    unappliedRotationDegrees: Int = 0,
    pixelWidthHeightRatio: Float = 1f,
  ) {
    glHandler?.post {
      if (isReleased) return@post
      val ratio = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
      val scaledW = (width * ratio).roundToInt().coerceAtLeast(1)
      val swapped = unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270
      videoWidth = if (swapped) height else scaledW
      videoHeight = if (swapped) scaledW else height
      if (hasFrame && renderMode == RenderMode.VIDEO) {
        drawCurrentFrame(updateTexImage = false)
      }
    }
  }

  fun setBrightness(brightness: Float) {
    brightnessFactor = brightness.coerceIn(0.01f, 1f)
    glHandler?.post {
      if (!isReleased && hasFrame) {
        drawCurrentFrame(updateTexImage = false)
      }
    }
  }

  fun redraw() {
    glHandler?.post {
      if (!isReleased && (hasFrame || renderMode == RenderMode.SOLID_COLOR)) {
        drawCurrentFrame(updateTexImage = false)
      }
    }
  }

  fun release() {
    isReleased = true
    renderMode = RenderMode.NONE
    val handler = glHandler
    val thread = glThread
    glHandler = null
    glThread = null
    if (handler != null) {
      if (Thread.currentThread() === thread) {
        releaseInternal()
        thread.quitSafely()
      } else {
        val latch = CountDownLatch(1)
        val posted =
          handler.post {
            try {
              releaseInternal()
            } finally {
              latch.countDown()
              thread?.quitSafely()
            }
          }
        if (posted) {
          runCatching { latch.await(1500, TimeUnit.MILLISECONDS) }
        } else {
          thread?.quitSafely()
        }
      }
    } else {
      thread?.quitSafely()
    }
  }

  private fun ensureEglReady(windowSurface: Surface): Boolean {
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
      return initEgl(windowSurface)
    }
    if (eglSurface == EGL14.EGL_NO_SURFACE || boundWindowSurface !== windowSurface) {
      val config = eglConfig ?: return false
      destroyWindowEglSurface()
      boundWindowSurface = windowSurface
      val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
      eglSurface =
        runCatching {
            EGL14.eglCreateWindowSurface(eglDisplay, config, windowSurface, surfaceAttribs, 0)
          }
          .getOrDefault(EGL14.EGL_NO_SURFACE)
      if (eglSurface == EGL14.EGL_NO_SURFACE) return false
    }
    return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
  }

  private fun destroyWindowEglSurface() {
    if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
      EGL14.eglMakeCurrent(
        eglDisplay,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_CONTEXT,
      )
      EGL14.eglDestroySurface(eglDisplay, eglSurface)
      eglSurface = EGL14.EGL_NO_SURFACE
    }
  }

  private fun initEgl(windowSurface: Surface): Boolean {
    eglDisplay = EGL14.EGL_NO_DISPLAY
    eglContext = EGL14.EGL_NO_CONTEXT
    eglSurface = EGL14.EGL_NO_SURFACE

    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

    val version = IntArray(2)
    if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

    val configAttribs =
      intArrayOf(
        EGL14.EGL_RED_SIZE,
        8,
        EGL14.EGL_GREEN_SIZE,
        8,
        EGL14.EGL_BLUE_SIZE,
        8,
        EGL14.EGL_ALPHA_SIZE,
        8,
        EGL14.EGL_RENDERABLE_TYPE,
        EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_NONE,
      )
    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
      return false
    }
    val chosenConfig = configs[0] ?: return false
    eglConfig = chosenConfig

    val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
    eglContext =
      EGL14.eglCreateContext(eglDisplay, chosenConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
    if (eglContext == EGL14.EGL_NO_CONTEXT) return false

    boundWindowSurface = windowSurface
    val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
    eglSurface =
      EGL14.eglCreateWindowSurface(eglDisplay, chosenConfig, windowSurface, surfaceAttribs, 0)
    if (eglSurface == EGL14.EGL_NO_SURFACE) return false

    return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
  }

  private fun ensureOesProgram(): Boolean {
    if (oesProgramId != 0 && oesTextureId != 0) return true
    val createdProgram = linkProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
    if (createdProgram == 0) return false
    oesProgramId = createdProgram

    oesPosHandle = GLES20.glGetAttribLocation(oesProgramId, "aPosition")
    oesTexCoordHandle = GLES20.glGetAttribLocation(oesProgramId, "aTexCoord")
    oesTexMatrixHandle = GLES20.glGetUniformLocation(oesProgramId, "uTexMatrix")
    oesScaleHandle = GLES20.glGetUniformLocation(oesProgramId, "uScale")
    oesBrightnessHandle = GLES20.glGetUniformLocation(oesProgramId, "uBrightness")

    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    oesTextureId = textures[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MIN_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MAG_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_S,
      GLES20.GL_CLAMP_TO_EDGE,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_T,
      GLES20.GL_CLAMP_TO_EDGE,
    )

    return oesTextureId != 0
  }

  private fun ensureBitmapProgram(): Boolean {
    if (bitmapProgramId != 0 && bitmapTextureId != 0) return true
    val createdProgram = linkProgram(BITMAP_VERTEX_SHADER, BITMAP_FRAGMENT_SHADER)
    if (createdProgram == 0) return false
    bitmapProgramId = createdProgram

    bitmapPosHandle = GLES20.glGetAttribLocation(bitmapProgramId, "aPosition")
    bitmapTexCoordHandle = GLES20.glGetAttribLocation(bitmapProgramId, "aTexCoord")
    bitmapScaleHandle = GLES20.glGetUniformLocation(bitmapProgramId, "uScale")
    bitmapBrightnessHandle = GLES20.glGetUniformLocation(bitmapProgramId, "uBrightness")

    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    bitmapTextureId = textures[0]
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

    return bitmapTextureId != 0
  }

  private fun uploadBitmapTexture(bitmap: Bitmap): Boolean {
    if (bitmap.isRecycled || bitmapTextureId == 0) return false
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    return true
  }

  private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (vertexShader == 0 || fragmentShader == 0) {
      if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
      if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
      return 0
    }

    val createdProgram = GLES20.glCreateProgram()
    GLES20.glAttachShader(createdProgram, vertexShader)
    GLES20.glAttachShader(createdProgram, fragmentShader)
    GLES20.glLinkProgram(createdProgram)
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)

    val linkStatus = IntArray(1)
    GLES20.glGetProgramiv(createdProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] == 0) {
      GLES20.glDeleteProgram(createdProgram)
      return 0
    }
    return createdProgram
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      GLES20.glDeleteShader(shader)
      return 0
    }
    return shader
  }

  private fun drawCurrentFrame(updateTexImage: Boolean) {
    if (
      isReleased ||
        eglDisplay == EGL14.EGL_NO_DISPLAY ||
        eglSurface == EGL14.EGL_NO_SURFACE ||
        eglContext == EGL14.EGL_NO_CONTEXT
    ) {
      return
    }
    val windowSurface = boundWindowSurface
    if (windowSurface != null && !windowSurface.isValid) return

    runCatching {
      if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

      when (renderMode) {
        RenderMode.NONE -> return
        RenderMode.SOLID_COLOR -> {
          GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
          GLES20.glClearColor(
            Color.red(solidColor) / 255f,
            Color.green(solidColor) / 255f,
            Color.blue(solidColor) / 255f,
            1f,
          )
          GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
          EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
        RenderMode.VIDEO -> {
          val st = surfaceTexture ?: return
          if (updateTexImage) {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
          }
          if (!hasFrame) return

          GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
          GLES20.glClearColor(0f, 0f, 0f, 1f)
          GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

          GLES20.glUseProgram(oesProgramId)
          val (scaleX, scaleY) = computeCenterCropScale()
          GLES20.glUniform2f(oesScaleHandle, scaleX, scaleY)
          GLES20.glUniform1f(oesBrightnessHandle, brightnessFactor)
          GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, texMatrix, 0)

          GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
          GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

          vertexBuffer.position(0)
          GLES20.glEnableVertexAttribArray(oesPosHandle)
          GLES20.glVertexAttribPointer(oesPosHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

          oesTexCoordBuffer.position(0)
          GLES20.glEnableVertexAttribArray(oesTexCoordHandle)
          GLES20.glVertexAttribPointer(
            oesTexCoordHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            oesTexCoordBuffer,
          )

          GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

          GLES20.glDisableVertexAttribArray(oesPosHandle)
          GLES20.glDisableVertexAttribArray(oesTexCoordHandle)

          EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
        RenderMode.STATIC_BITMAP -> {
          if (!hasFrame || bitmapProgramId == 0 || bitmapTextureId == 0) return

          GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
          GLES20.glClearColor(0f, 0f, 0f, 1f)
          GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

          GLES20.glUseProgram(bitmapProgramId)
          val (scaleX, scaleY) = computeCenterCropScale()
          GLES20.glUniform2f(bitmapScaleHandle, scaleX, scaleY)
          GLES20.glUniform1f(bitmapBrightnessHandle, brightnessFactor)

          GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
          GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTextureId)

          vertexBuffer.position(0)
          GLES20.glEnableVertexAttribArray(bitmapPosHandle)
          GLES20.glVertexAttribPointer(bitmapPosHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

          bitmapTexCoordBuffer.position(0)
          GLES20.glEnableVertexAttribArray(bitmapTexCoordHandle)
          GLES20.glVertexAttribPointer(
            bitmapTexCoordHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            bitmapTexCoordBuffer,
          )

          GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

          GLES20.glDisableVertexAttribArray(bitmapPosHandle)
          GLES20.glDisableVertexAttribArray(bitmapTexCoordHandle)

          EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
      }
    }
  }

  private fun computeCenterCropScale(): Pair<Float, Float> {
    if (videoWidth <= 0 || videoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
      return 1f to 1f
    }
    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
    return if (videoAspect > surfaceAspect) {
      (videoAspect / surfaceAspect) to 1f
    } else {
      1f to (surfaceAspect / videoAspect)
    }
  }

  private fun releaseInternal() {
    hasFrame = false
    renderMode = RenderMode.NONE
    boundWindowSurface = null
    runCatching { playerSurface?.release() }
    playerSurface = null
    runCatching { surfaceTexture?.release() }
    surfaceTexture = null

    if (oesTextureId != 0) {
      runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) }
      oesTextureId = 0
    }
    if (bitmapTextureId != 0) {
      runCatching { GLES20.glDeleteTextures(1, intArrayOf(bitmapTextureId), 0) }
      bitmapTextureId = 0
    }
    if (oesProgramId != 0) {
      runCatching { GLES20.glDeleteProgram(oesProgramId) }
      oesProgramId = 0
    }
    if (bitmapProgramId != 0) {
      runCatching { GLES20.glDeleteProgram(bitmapProgramId) }
      bitmapProgramId = 0
    }
    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
      runCatching {
        destroyWindowEglSurface()
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
          EGL14.eglDestroyContext(eglDisplay, eglContext)
          eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglTerminate(eglDisplay)
      }
      eglDisplay = EGL14.EGL_NO_DISPLAY
    }
    eglConfig = null
  }

  companion object {
    private val VERTEX_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val OES_TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val BITMAP_TEX_COORDS = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    private const val OES_VERTEX_SHADER =
      """
      attribute vec2 aPosition;
      attribute vec2 aTexCoord;
      uniform mat4 uTexMatrix;
      uniform vec2 uScale;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, 0.0, 1.0);
        vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
      }
      """

    private const val OES_FRAGMENT_SHADER =
      """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      varying vec2 vTexCoord;
      uniform samplerExternalOES uTexture;
      uniform float uBrightness;
      void main() {
        vec4 color = texture2D(uTexture, vTexCoord);
        gl_FragColor = vec4(color.rgb * uBrightness, 1.0);
      }
      """

    private const val BITMAP_VERTEX_SHADER =
      """
      attribute vec2 aPosition;
      attribute vec2 aTexCoord;
      uniform vec2 uScale;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, 0.0, 1.0);
        vTexCoord = aTexCoord;
      }
      """

    private const val BITMAP_FRAGMENT_SHADER =
      """
      precision mediump float;
      varying vec2 vTexCoord;
      uniform sampler2D uTexture;
      uniform float uBrightness;
      void main() {
        vec4 color = texture2D(uTexture, vTexCoord);
        gl_FragColor = vec4(color.rgb * uBrightness, 1.0);
      }
      """
  }
}
