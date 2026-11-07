package es.dasemu.wanderer.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/** Minimal EGL 1.4 setup for an OpenGL ES 3.0 context rendering to a window surface. */
class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    val hasContext get() = context != EGL14.EGL_NO_CONTEXT
    val hasSurface get() = surface != EGL14.EGL_NO_SURFACE

    fun init() {
        if (hasContext) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No OpenGL ES 3.0 config"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }
    }

    /** [nativeWindow] is a Surface, SurfaceHolder or SurfaceTexture. */
    fun createSurface(nativeWindow: Any) {
        releaseSurface()
        surface = EGL14.eglCreateWindowSurface(display, config, nativeWindow, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    /** Returns false when the context was lost and everything must be recreated. */
    fun swap(): Boolean = EGL14.eglSwapBuffers(display, surface) || EGL14.eglGetError() != EGL14.EGL_CONTEXT_LOST

    fun releaseSurface() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        surface = EGL14.EGL_NO_SURFACE
    }

    fun release() {
        releaseSurface()
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }
}
