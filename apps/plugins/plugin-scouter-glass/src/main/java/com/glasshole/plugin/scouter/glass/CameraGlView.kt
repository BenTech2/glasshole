// SPDX-License-Identifier: MIT
package com.glasshole.plugin.scouter.glass

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView that renders a Camera1 preview through the
 * `GL_OES_EGL_image_external` extension. This is the canonical path
 * for Camera1 YUV → display on KitKat-era Adreno drivers (Glass XE =
 * Adreno 305) that mishandle TextureView and SurfaceView direct paths:
 * the OES-external sampler does YUV→RGB conversion in hardware at
 * texture-sample time, which the driver handles correctly even when
 * its TextureView GL composition pipeline is broken.
 *
 * Lifecycle:
 *   1. Activity creates the view + sets [onSurfaceReady]
 *   2. The GL thread creates the OES texture + wraps it in a
 *      SurfaceTexture, fires [onSurfaceReady] back on the main thread.
 *   3. Activity opens the camera + calls
 *      `camera.setPreviewTexture(surfaceTexture)` + `startPreview()`.
 *   4. Camera writes frames; SurfaceTexture's onFrameAvailable
 *      triggers our [requestRender]; the renderer samples + draws.
 *
 * The Scouter's red ColorMatrix is folded into the fragment shader
 * so the aesthetic survives even though we no longer use Android's
 * Paint-based ColorMatrixColorFilter (which itself is broken on XE
 * for hardware layer composition).
 */
class CameraGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = CameraRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Fires on the main thread once the OES texture + SurfaceTexture
     *  pair are created on the GL thread. Activity should bind the
     *  camera in this callback. */
    fun setOnSurfaceReady(callback: (SurfaceTexture) -> Unit) {
        renderer.surfaceReadyCallback = callback
    }

    /** Toggle the Vegeta-style red Scouter filter (R boosted, G/B
     *  crushed). Mirrors the ColorMatrix used on EE1/EE2 TextureView. */
    fun setRedFilter(enabled: Boolean) {
        renderer.redFilterEnabled = enabled
        requestRender()
    }

    /** Tell the renderer that the camera's preview dimensions are W x H.
     *  Used by the renderer to correct aspect ratio if Camera1 picks a
     *  preview size that doesn't match the GLSurfaceView's pixel size. */
    fun setCameraPreviewSize(w: Int, h: Int) {
        renderer.cameraW = w
        renderer.cameraH = h
        queueEvent { renderer.recomputeAspect() }
        requestRender()
    }

    /** Release GL resources. Call from Activity.onDestroy (or onPause
     *  if the activity is short-lived). */
    fun releaseCamera() {
        queueEvent { renderer.releaseSurfaceTexture() }
    }

    private inner class CameraRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {

        var surfaceReadyCallback: ((SurfaceTexture) -> Unit)? = null
        var redFilterEnabled = true
        var cameraW: Int = 0
        var cameraH: Int = 0

        private var oesTexture = 0
        private var surfaceTexture: SurfaceTexture? = null

        private var program = 0
        private var aPositionLoc = -1
        private var aTexCoordLoc = -1
        private var uTexMatrixLoc = -1
        private var uTextureLoc = -1
        private var uRedFilterLoc = -1

        private val texMatrix = FloatArray(16)

        private var viewW = 0
        private var viewH = 0
        private val vertexCoords = FloatArray(8)
        private lateinit var vertexBuf: FloatBuffer
        private lateinit var texBuf: FloatBuffer

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            Log.i(TAG, "GL surface created — vendor=${GLES20.glGetString(GLES20.GL_VENDOR)} " +
                "renderer=${GLES20.glGetString(GLES20.GL_RENDERER)} " +
                "version=${GLES20.glGetString(GLES20.GL_VERSION)}")

            // Direct ByteBuffers for vertex + tex coord — must be
            // direct + native byte order for GLES.
            vertexBuf = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            // Texture coords map (0,0)=bottom-left to (1,1)=top-right.
            // The texMatrix from SurfaceTexture handles camera-to-screen
            // orientation correction, so this stays a simple unit square.
            val texCoords = floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            )
            texBuf = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(texCoords); position(0)
                }

            // Compile shaders + link.
            program = buildProgram() ?: 0
            if (program == 0) {
                Log.e(TAG, "GL program failed to build — preview will be blank")
                return
            }
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uRedFilterLoc = GLES20.glGetUniformLocation(program, "uRedFilter")

            // Allocate the OES-external texture that the camera will
            // write into. CRUCIAL: must use GL_OES_EGL_image_external
            // as the texture target — not GL_TEXTURE_2D — so the
            // driver's YUV→RGB conversion kicks in at sample time.
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            oesTexture = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            surfaceTexture = SurfaceTexture(oesTexture).apply {
                setOnFrameAvailableListener(this@CameraRenderer)
            }

            GLES20.glClearColor(0f, 0f, 0f, 1f)

            // Notify the activity on its main thread so it can open
            // the camera + bind it to the freshly-created surface.
            val st = surfaceTexture!!
            post { surfaceReadyCallback?.invoke(st) }
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            viewW = w; viewH = h
            GLES20.glViewport(0, 0, w, h)
            recomputeAspect()
        }

        /** Build the vertex quad in clip space, factoring camera
         *  preview aspect ratio into the view's aspect. CENTER_CROP
         *  semantics — the smaller dimension fills the view, the
         *  larger overflows symmetrically. */
        fun recomputeAspect() {
            val vw = viewW.toFloat(); val vh = viewH.toFloat()
            val cw = cameraW.toFloat(); val ch = cameraH.toFloat()
            if (vw == 0f || vh == 0f) return

            // Default: unit quad (fills viewport)
            var x0 = -1f; var x1 = 1f
            var y0 = -1f; var y1 = 1f

            if (cw > 0f && ch > 0f) {
                val viewAspect = vw / vh
                val camAspect = cw / ch
                if (camAspect > viewAspect) {
                    // Camera wider than view — overflow horizontally.
                    val scale = camAspect / viewAspect
                    x0 = -scale; x1 = scale
                } else if (camAspect < viewAspect) {
                    val scale = viewAspect / camAspect
                    y0 = -scale; y1 = scale
                }
            }

            vertexCoords[0] = x0; vertexCoords[1] = y0
            vertexCoords[2] = x1; vertexCoords[3] = y0
            vertexCoords[4] = x0; vertexCoords[5] = y1
            vertexCoords[6] = x1; vertexCoords[7] = y1
            vertexBuf.position(0)
            vertexBuf.put(vertexCoords).position(0)
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return
            try {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            } catch (e: RuntimeException) {
                // SurfaceTexture detached during teardown; skip frame.
                Log.w(TAG, "updateTexImage failed: ${e.message}")
                return
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0) return

            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES20.glUniform1i(uTextureLoc, 0)

            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform1i(uRedFilterLoc, if (redFilterEnabled) 1 else 0)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(
                aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf
            )
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(
                aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        override fun onFrameAvailable(st: SurfaceTexture) {
            // SurfaceTexture's onFrameAvailable can fire from any
            // thread; requestRender is documented thread-safe.
            requestRender()
        }

        fun releaseSurfaceTexture() {
            try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Exception) {}
            try { surfaceTexture?.release() } catch (_: Exception) {}
            surfaceTexture = null
            if (oesTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
                oesTexture = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun buildProgram(): Int? {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return null
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
                GLES20.glDeleteProgram(p)
                return null
            }
            // Shaders can be deleted once linked into the program.
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return p
        }

        private fun compileShader(type: Int, src: String): Int? {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Shader compile failed (type=$type): ${GLES20.glGetShaderInfoLog(s)}")
                GLES20.glDeleteShader(s)
                return null
            }
            return s
        }
    }

    companion object {
        private const val TAG = "CameraGlView"

        // Vertex shader: pass through clip-space position + apply the
        // SurfaceTexture's transform matrix to tex coords (the matrix
        // handles camera→screen rotation on devices where the camera
        // sensor is mounted at a non-zero angle).
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        // Fragment shader: sample the OES-external texture (driver
        // converts YUV→RGB at sample time) + optionally apply the
        // Scouter red filter. The matrix matches what the EE1/EE2
        // TextureView path applies via ColorMatrixColorFilter.
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform int uRedFilter;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                if (uRedFilter == 1) {
                    // R = 1.1*R + 15/255, G = 0.2*G, B = 0.15*B
                    // — Vegeta-style red Scouter aesthetic.
                    float r = clamp(color.r * 1.1 + 0.0588, 0.0, 1.0);
                    float g = clamp(color.g * 0.2,           0.0, 1.0);
                    float b = clamp(color.b * 0.15,          0.0, 1.0);
                    gl_FragColor = vec4(r, g, b, color.a);
                } else {
                    gl_FragColor = color;
                }
            }
        """
    }
}
