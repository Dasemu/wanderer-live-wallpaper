package es.dasemu.wanderer.render

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import es.dasemu.wanderer.SceneController
import java.util.concurrent.CountDownLatch

/**
 * Dedicated GL thread with frame pacing. Frames are only scheduled while the surface is visible,
 * and the Choreographer callback is posted with a delay so the thread sleeps between frames
 * instead of waking on every vsync.
 */
class RenderLoop(private val controller: SceneController) {

    private val thread = HandlerThread("wanderer-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val egl = EglCore()
    private val renderer = SceneRenderer(controller.context, controller.config)
    private val input = FrameInput()
    private lateinit var choreographer: Choreographer

    private var rendererReady = false
    private var nativeWindow: Any? = null
    private var visible = false
    private var frameScheduled = false
    private var forceNextFrame = false
    private var lastFrameNanos = 0L
    private var width = 0
    private var height = 0

    private val frameCallback = Choreographer.FrameCallback { now ->
        frameScheduled = false
        onFrame(now)
    }

    init {
        handler.post { choreographer = Choreographer.getInstance() }
    }

    fun surfaceCreated(window: Any) = runBlocking {
        nativeWindow = window
        egl.init()
        egl.createSurface(window)
        if (!rendererReady) {
            renderer.onSurfaceCreated()
            rendererReady = true
        }
    }

    fun surfaceChanged(w: Int, h: Int) = handler.post {
        width = w
        height = h
        if (!egl.hasSurface || !rendererReady) return@post
        renderer.onSurfaceChanged(w, h)
        controller.onLayout(renderer)
        lastFrameNanos = 0
        renderFrame(0f)
        scheduleFrame(0)
    }

    /** Blocks: the caller's surface must not be torn down before EGL lets go of it. */
    fun surfaceDestroyed() = runBlocking {
        cancelFrame()
        egl.releaseSurface()
        nativeWindow = null
    }

    fun setVisible(v: Boolean) = handler.post {
        visible = v
        if (v) {
            lastFrameNanos = 0
            scheduleFrame(0)
        } else {
            cancelFrame()
        }
    }

    /** Draw now, even mid-way through a long battery-saver wait (settings or power mode changed). */
    fun requestRender() = handler.post {
        forceNextFrame = true
        cancelFrame()
        scheduleFrame(0)
    }

    fun quit() = handler.post {
        cancelFrame()
        if (rendererReady && egl.hasSurface) renderer.release()
        rendererReady = false
        egl.release()
        thread.quitSafely()
    }

    private fun onFrame(now: Long) {
        if (!visible || !egl.hasSurface || !rendererReady || width == 0) return
        val interval = controller.frameIntervalNanos
        val elapsed = now - lastFrameNanos
        val forced = forceNextFrame
        forceNextFrame = false
        if (!forced && lastFrameNanos != 0L && elapsed < interval - VSYNC_SLACK_NANOS) {
            scheduleFrame((interval - elapsed) / 1_000_000 - 2)
            return
        }
        val dt = if (lastFrameNanos == 0L) 0f else (elapsed / 1e9f).coerceAtMost(0.25f)
        lastFrameNanos = now
        renderFrame(dt)
        val spent = System.nanoTime() - now
        // Wake a few ms early: the callback still lands on the next vsync after the delay.
        scheduleFrame((interval - spent) / 1_000_000 - VSYNC_SLACK_NANOS / 1_000_000)
    }

    private fun renderFrame(dt: Float) {
        controller.prepareFrame(dt, input, renderer)
        renderer.draw(controller.sky, input)
        if (!egl.swap()) recreateAfterContextLoss()
    }

    /** GPU reset or driver context loss: rebuild EGL and every GL object on the same window. */
    private fun recreateAfterContextLoss() {
        Log.w(TAG, "EGL context lost, recreating")
        rendererReady = false
        egl.release()
        val window = nativeWindow ?: return
        try {
            egl.init()
            egl.createSurface(window)
            renderer.onSurfaceCreated()
            rendererReady = true
            renderer.onSurfaceChanged(width, height)
            controller.onLayout(renderer)
            lastFrameNanos = 0
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not recreate the GL context", e)
        }
    }

    private fun scheduleFrame(delayMs: Long) {
        if (frameScheduled || !::choreographer.isInitialized) return
        frameScheduled = true
        choreographer.postFrameCallbackDelayed(frameCallback, delayMs.coerceAtLeast(0))
    }

    private fun cancelFrame() {
        if (!frameScheduled) return
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    private fun runBlocking(block: () -> Unit) {
        val latch = CountDownLatch(1)
        // After quit() the looper is gone and post() fails; waiting would hang the caller forever.
        val posted = handler.post {
            try {
                block()
            } catch (e: RuntimeException) {
                Log.e(TAG, "GL thread error", e)
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await()
    }

    companion object {
        private const val TAG = "RenderLoop"
        private const val VSYNC_SLACK_NANOS = 4_000_000L
    }
}
