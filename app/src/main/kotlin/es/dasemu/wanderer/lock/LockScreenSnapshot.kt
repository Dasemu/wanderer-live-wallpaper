package es.dasemu.wanderer.lock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glFinish
import android.opengl.GLES30.glReadPixels
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import es.dasemu.wanderer.SceneController
import es.dasemu.wanderer.render.EglCore
import es.dasemu.wanderer.render.FrameInput
import es.dasemu.wanderer.render.SceneRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders one still frame of the scene at the current time, at the screen's size, off screen. */
object LockScreenSnapshot {

    /** Blocking; call from a background thread. */
    fun render(context: Context): Bitmap {
        val (w, h) = screenSize(context)
        val controller = SceneController(context).apply { applyPrefs() }
        val renderer = SceneRenderer(context, controller.config)
        val egl = EglCore()
        try {
            egl.init(offscreen = true)
            egl.createOffscreenSurface(w, h)
            renderer.onSurfaceCreated()
            renderer.onSurfaceChanged(w, h)
            controller.onLayout(renderer)
            val input = FrameInput()
            controller.prepareFrame(0f, input, renderer)
            renderer.draw(controller.sky, input)
            glFinish()
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            renderer.release()
            buf.rewind()
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buf)
            // GL rows start at the bottom.
            val flipped = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false)
            raw.recycle()
            return flipped
        } finally {
            egl.release()
        }
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            minOf(b.width(), b.height()) to maxOf(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            minOf(m.widthPixels, m.heightPixels) to maxOf(m.widthPixels, m.heightPixels)
        }
    }
}
