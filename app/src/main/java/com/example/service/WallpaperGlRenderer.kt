package com.example.service

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
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
 * Renderer OpenGL ES 2.0 untuk LiveWallpaperService saat tingkat kecerahan < 100%.
 * Dilengkapi pengamanan penuh terhadap perubahan ukuran surface, pelepasan resource aman,
 * dan fallback otomatis jika EGL gagal agar tidak pernah memicu crash atau glitch layar hitam.
 */
class WallpaperGlRenderer {

  private val mainHandler = Handler(Looper.getMainLooper())
  private var glThread: HandlerThread? = null
  private var glHandler: Handler? = null

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
  private var eglConfig: EGLConfig? = null
  private var boundWindowSurface: Surface? = null

  private var programId = 0
  private var oesTextureId = 0
  private var surfaceTexture: SurfaceTexture? = null
  private var playerSurface: Surface? = null

  private var posHandle = 0
  private var texCoordHandle = 0
  private var texMatrixHandle = 0
  private var scaleHandle = 0
  private var brightnessHandle = 0

  private val texMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
  private var surfaceWidth = 1
  private var surfaceHeight = 1
  private var videoWidth = 0
  private var videoHeight = 0
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

  private val texCoordBuffer: FloatBuffer =
    ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(TEX_COORDS)
        position(0)
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
    brightnessFactor = initialBrightness.coerceIn(0.01f, 1f)
    surfaceWidth = width.coerceAtLeast(1)
    surfaceHeight = height.coerceAtLeast(1)
    boundWindowSurface = windowSurface

    val thread = HandlerThread("LiveWallpaperGL").apply { start() }
    glThread = thread
    val handler = Handler(thread.looper)
    glHandler = handler

    handler.post {
      if (isReleased || !windowSurface.isValid) {
        releaseInternal()
        if (!isReleased) mainHandler.post { onFallbackRequired() }
        return@post
      }

      val ok =
        runCatching { initEgl(windowSurface) && initGlProgram() }.getOrDefault(false)
      if (!ok) {
        releaseInternal()
        mainHandler.post { if (!isReleased) onFallbackRequired() }
        return@post
      }

      val st =
        SurfaceTexture(oesTextureId).apply {
          setOnFrameAvailableListener(
            {
              if (!isReleased) {
                hasFrame = true
                drawFrame(updateTexImage = true)
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
        if (!isReleased) {
          onPlayerSurfaceReady(surface)
        }
      }
    }
  }

  fun updateSurfaceSize(windowSurface: Surface, width: Int, height: Int) {
    glHandler?.post {
      if (isReleased || eglDisplay == EGL14.EGL_NO_DISPLAY || !windowSurface.isValid) return@post
      surfaceWidth = width.coerceAtLeast(1)
      surfaceHeight = height.coerceAtLeast(1)
      val config = eglConfig ?: return@post

      // Re-create EGLSurface jika Window Surface berganti atau belum ada
      if (eglSurface == EGL14.EGL_NO_SURFACE || boundWindowSurface !== windowSurface) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
          EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
          )
          EGL14.eglDestroySurface(eglDisplay, eglSurface)
          eglSurface = EGL14.EGL_NO_SURFACE
        }
        boundWindowSurface = windowSurface
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface =
          runCatching {
              EGL14.eglCreateWindowSurface(eglDisplay, config, windowSurface, surfaceAttribs, 0)
            }
            .getOrDefault(EGL14.EGL_NO_SURFACE)
      }

      if (eglSurface != EGL14.EGL_NO_SURFACE && hasFrame) {
        drawFrame(updateTexImage = false)
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
      if (hasFrame) {
        drawFrame(updateTexImage = false)
      }
    }
  }

  fun setBrightness(brightness: Float) {
    brightnessFactor = brightness.coerceIn(0.01f, 1f)
    glHandler?.post {
      if (!isReleased && hasFrame) {
        drawFrame(updateTexImage = false)
      }
    }
  }

  fun redraw() {
    glHandler?.post {
      if (!isReleased && hasFrame) {
        drawFrame(updateTexImage = false)
      }
    }
  }

  fun release() {
    isReleased = true
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
          runCatching { latch.await(250, TimeUnit.MILLISECONDS) }
        } else {
          thread?.quitSafely()
        }
      }
    } else {
      thread?.quitSafely()
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

    val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
    eglSurface =
      EGL14.eglCreateWindowSurface(eglDisplay, chosenConfig, windowSurface, surfaceAttribs, 0)
    if (eglSurface == EGL14.EGL_NO_SURFACE) return false

    return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
  }

  private fun initGlProgram(): Boolean {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
    if (vertexShader == 0 || fragmentShader == 0) {
      if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
      if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
      return false
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
      return false
    }
    programId = createdProgram

    posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
    texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
    texMatrixHandle = GLES20.glGetUniformLocation(programId, "uTexMatrix")
    scaleHandle = GLES20.glGetUniformLocation(programId, "uScale")
    brightnessHandle = GLES20.glGetUniformLocation(programId, "uBrightness")

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

    return true
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

  private fun drawFrame(updateTexImage: Boolean) {
    if (
      isReleased ||
        eglDisplay == EGL14.EGL_NO_DISPLAY ||
        eglSurface == EGL14.EGL_NO_SURFACE ||
        eglContext == EGL14.EGL_NO_CONTEXT
    ) {
      return
    }
    val st = surfaceTexture ?: return
    val windowSurface = boundWindowSurface
    if (windowSurface != null && !windowSurface.isValid) return

    runCatching {
      if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

      if (updateTexImage) {
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
      }

      if (!hasFrame) return

      GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
      GLES20.glClearColor(0f, 0f, 0f, 1f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

      GLES20.glUseProgram(programId)

      val (scaleX, scaleY) = computeCenterCropScale()
      GLES20.glUniform2f(scaleHandle, scaleX, scaleY)
      GLES20.glUniform1f(brightnessHandle, brightnessFactor)
      GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

      vertexBuffer.position(0)
      GLES20.glEnableVertexAttribArray(posHandle)
      GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

      texCoordBuffer.position(0)
      GLES20.glEnableVertexAttribArray(texCoordHandle)
      GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

      GLES20.glDisableVertexAttribArray(posHandle)
      GLES20.glDisableVertexAttribArray(texCoordHandle)

      EGL14.eglSwapBuffers(eglDisplay, eglSurface)
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
    boundWindowSurface = null
    runCatching { playerSurface?.release() }
    playerSurface = null
    runCatching { surfaceTexture?.release() }
    surfaceTexture = null

    if (oesTextureId != 0) {
      runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) }
      oesTextureId = 0
    }
    if (programId != 0) {
      runCatching { GLES20.glDeleteProgram(programId) }
      programId = 0
    }
    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
      runCatching {
        EGL14.eglMakeCurrent(
          eglDisplay,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_CONTEXT,
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
          EGL14.eglDestroySurface(eglDisplay, eglSurface)
          eglSurface = EGL14.EGL_NO_SURFACE
        }
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
    private val TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private const val VERTEX_SHADER =
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

    private const val FRAGMENT_SHADER =
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
  }
}
