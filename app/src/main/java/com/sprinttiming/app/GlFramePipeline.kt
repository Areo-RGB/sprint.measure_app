package com.sprinttiming.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GPU frame path for constrained high-speed sessions (120/240 fps). Those sessions only accept
 * preview or encoder surfaces, so a YUV ImageReader cannot be used. Camera frames land in an OES
 * texture; the camera's SurfaceTexture transform already rotates (and mirrors for the front camera)
 * exactly like the preview, so frames are rendered upright into a small FBO with four luma pixels
 * packed per RGBA texel and read back. The same texture is drawn to the TextureView at ~30 fps.
 * Frame timestamps come from SurfaceTexture.getTimestamp(), i.e. the sensor timestamp.
 */
class GlFramePipeline(
    private val previewTexture: SurfaceTexture?,
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    val analysisWidth: Int,
    val analysisHeight: Int,
    /** Called for every frame with its sensor timestamp; return true to read the luma frame back. */
    private val onFrame: (Long) -> Boolean,
    private val onLuma: (ByteArray, Int, Int, Long) -> Unit
) {
    private val thread = HandlerThread("gl-analysis").apply { start() }
    val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var window = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var oesTexture = 0
    private var fbo = 0
    private var fboTexture = 0
    private var lumaProgram = 0
    private var previewProgram = 0
    private var inputTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null; private set
    private val matrix = FloatArray(16)
    private val readBuffer = ByteBuffer.allocateDirect(analysisWidth * analysisHeight).order(ByteOrder.nativeOrder())
    private val luma = ByteArray(analysisWidth * analysisHeight)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD); position(0) }
    @Volatile var previewEnabled = true
    @Volatile var hasPreview = false; private set
    private var lastPreviewNanos = 0L
    @Volatile private var released = false

    init { require(analysisWidth % 4 == 0) { "analysis width must be a multiple of 4" } }

    /** Creates the EGL context and input surface on the GL thread. Returns an error message or null. */
    fun start(): String? {
        var error: String? = "GL setup timed out"
        val done = CountDownLatch(1)
        handler.post {
            error = try { setup(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        return error
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        if (previewTexture != null) {
            window = EGL14.eglCreateWindowSurface(display, config, previewTexture, intArrayOf(EGL14.EGL_NONE), 0)
            hasPreview = window != EGL14.EGL_NO_SURFACE
        }
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); oesTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glGenTextures(1, ids, 0); fboTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, analysisWidth / 4, analysisHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)

        lumaProgram = program(LUMA_VERTEX, LUMA_FRAGMENT)
        previewProgram = program(PREVIEW_VERTEX, PREVIEW_FRAGMENT)

        inputTexture = SurfaceTexture(oesTexture).apply {
            setDefaultBufferSize(bufferWidth, bufferHeight)
            setOnFrameAvailableListener({ drawFrame() }, handler)
        }
        inputSurface = Surface(inputTexture)
    }

    private fun drawFrame() {
        if (released) return
        val texture = inputTexture ?: return
        try { texture.updateTexImage() } catch (_: Exception) { return }
        val timestamp = texture.timestamp
        texture.getTransformMatrix(matrix)
        if (onFrame(timestamp)) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, analysisWidth / 4, analysisHeight)
            GLES20.glUseProgram(lumaProgram)
            bindQuad(lumaProgram, withTexCoords = false)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(lumaProgram, "sTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lumaProgram, "uTexMatrix"), 1, false, matrix, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(lumaProgram, "uSize"), analysisWidth.toFloat(), analysisHeight.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            readBuffer.clear()
            GLES20.glReadPixels(0, 0, analysisWidth / 4, analysisHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            // GL rows are bottom-up; the detector expects top-down.
            for (row in 0 until analysisHeight) {
                readBuffer.position((analysisHeight - 1 - row) * analysisWidth)
                readBuffer.get(luma, row * analysisWidth, analysisWidth)
            }
            onLuma(luma, analysisWidth, analysisHeight, timestamp)
        }
        if (hasPreview && previewEnabled && timestamp - lastPreviewNanos >= PREVIEW_INTERVAL_NANOS) {
            lastPreviewNanos = timestamp
            drawPreview()
        }
    }

    private fun drawPreview() {
        if (!EGL14.eglMakeCurrent(display, window, window, context)) { hasPreview = false; return }
        EGL14.eglSwapInterval(display, 0)
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, h, 0)
        GLES20.glViewport(0, 0, w[0], h[0])
        GLES20.glUseProgram(previewProgram)
        bindQuad(previewProgram, withTexCoords = true)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(previewProgram, "sTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(previewProgram, "uTexMatrix"), 1, false, matrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (!EGL14.eglSwapBuffers(display, window)) hasPreview = false
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun bindQuad(program: Int, withTexCoords: Boolean) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        if (withTexCoords) {
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            quad.position(2)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(texCoord)
        }
    }

    private fun program(vertexSource: String, fragmentSource: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val ok = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(id)}" }
            return id
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, shader(GLES20.GL_VERTEX_SHADER, vertexSource))
        GLES20.glAttachShader(id, shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
        GLES20.glLinkProgram(id)
        val ok = IntArray(1); GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: ${GLES20.glGetProgramInfoLog(id)}" }
        return id
    }

    /** Releases GL objects on the GL thread. Call after the camera session no longer targets [inputSurface]. */
    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        handler.post {
            try {
                inputSurface?.release(); inputTexture?.release()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                }
            } catch (_: Exception) { }
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private companion object {
        const val PREVIEW_INTERVAL_NANOS = 33_000_000L
        // x, y, u, v for a full-screen triangle strip.
        val QUAD = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        const val LUMA_VERTEX = "attribute vec4 aPosition;\nvoid main() { gl_Position = aPosition; }\n"
        const val LUMA_FRAGMENT = """#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES sTexture;
uniform mat4 uTexMatrix;
uniform vec2 uSize;
const vec3 kLuma = vec3(0.299, 0.587, 0.114);
float lumaAt(float x, float y) {
    vec2 t = (uTexMatrix * vec4(x / uSize.x, y / uSize.y, 0.0, 1.0)).xy;
    return dot(texture2D(sTexture, t).rgb, kLuma);
}
void main() {
    float x = floor(gl_FragCoord.x) * 4.0 + 0.5;
    float y = gl_FragCoord.y;
    gl_FragColor = vec4(lumaAt(x, y), lumaAt(x + 1.0, y), lumaAt(x + 2.0, y), lumaAt(x + 3.0, y));
}
"""
        const val PREVIEW_VERTEX = """attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;
void main() { gl_Position = aPosition; vTexCoord = (uTexMatrix * aTexCoord).xy; }
"""
        const val PREVIEW_FRAGMENT = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES sTexture;
varying vec2 vTexCoord;
void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
"""
    }
}
