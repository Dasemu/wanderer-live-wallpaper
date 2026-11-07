package es.dasemu.wanderer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import es.dasemu.wanderer.render.RenderLoop

class WandererWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WandererEngine()

    inner class WandererEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private lateinit var controller: SceneController
        private lateinit var loop: RenderLoop
        private val power by lazy { getSystemService(PowerManager::class.java) }

        private val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                controller.lowPower = power.isPowerSaveMode
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)
            setOffsetNotificationsEnabled(true)
            controller = SceneController(this@WandererWallpaperService)
            controller.applyPrefs()
            controller.lowPower = power.isPowerSaveMode
            loop = RenderLoop(controller)
            Prefs.get(this@WandererWallpaperService).registerOnSharedPreferenceChangeListener(this)
            registerReceiver(powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loop.surfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            loop.surfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            loop.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) LocationHelper.refreshIfStale(this@WandererWallpaperService)
            loop.setVisible(visible)
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int,
        ) {
            // A single home page reports offset 0 (step 0 or >= 1): centre the painting instead of
            // pinning it to the left edge.
            val singlePage = xOffsetStep <= 0f || xOffsetStep >= 1f
            controller.parallaxTarget = if (singlePage || isPreview) 0.5f else xOffset
        }

        // Called by API 30+ launchers when the home screen zooms out (e.g. opening the app drawer).
        override fun onZoomChanged(zoom: Float) {
            controller.zoom = zoom
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            controller.applyPrefs()
            loop.requestRender()
        }

        override fun onDestroy() {
            Prefs.get(this@WandererWallpaperService).unregisterOnSharedPreferenceChangeListener(this)
            unregisterReceiver(powerReceiver)
            loop.quit()
            super.onDestroy()
        }
    }
}
