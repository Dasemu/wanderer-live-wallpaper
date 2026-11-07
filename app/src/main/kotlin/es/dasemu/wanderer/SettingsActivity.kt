package es.dasemu.wanderer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.PowerManager
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import es.dasemu.wanderer.lock.LockScreenUpdater
import es.dasemu.wanderer.render.RenderLoop
import java.util.Locale

/** Settings with a live preview rendered by the same engine as the wallpaper. */
class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var controller: SceneController
    private lateinit var loop: RenderLoop
    private val prefs by lazy { Prefs.get(this) }

    private lateinit var timeLabel: TextView
    private lateinit var timeSeek: SeekBar
    private lateinit var timeSwitch: CompoundButton
    private lateinit var speedLabel: TextView
    private lateinit var sunLabel: TextView
    private lateinit var hoursGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        controller = SceneController(this)
        controller.applyPrefs()
        controller.lowPower = getSystemService(PowerManager::class.java).isPowerSaveMode
        loop = RenderLoop(controller)

        findViewById<View>(R.id.controls).setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom + v.paddingTop)
            insets
        }
        setUpPreview(findViewById(R.id.preview))
        setUpTime()
        setUpFps()
        setUpSpeed()
        setUpHours()
        setUpLockScreen()
        findViewById<Button>(R.id.set_wallpaper).setOnClickListener {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, WandererWallpaperService::class.java),
                ),
            )
        }
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpPreview(view: TextureView) {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                loop.surfaceCreated(st)
                loop.surfaceChanged(w, h)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                loop.surfaceChanged(w, h)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                loop.surfaceDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        // Dragging sideways previews the home-screen parallax.
        view.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_MOVE || e.action == MotionEvent.ACTION_DOWN) {
                controller.parallaxTarget = (e.x / v.width).coerceIn(0f, 1f)
            }
            true
        }
    }

    private fun setUpTime() {
        timeLabel = findViewById(R.id.time_label)
        timeSeek = findViewById(R.id.time_seek)
        timeSwitch = findViewById(R.id.time_switch)
        val now = java.time.LocalTime.now(controller.place.zone)
        timeSeek.progress = now.hour * 60 + now.minute
        timeSeek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) timeSwitch.isChecked = true
                updateTime()
            }
        })
        timeSwitch.setOnCheckedChangeListener { _, _ -> updateTime() }
        updateTime()
    }

    private fun updateTime() {
        val m = timeSeek.progress
        controller.timeOverrideMinute = if (timeSwitch.isChecked) m else -1
        timeLabel.text = if (timeSwitch.isChecked) {
            getString(R.string.time_preview_at, String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60))
        } else {
            getString(R.string.time_live)
        }
        loop.requestRender()
    }

    private fun setUpFps() {
        val group = findViewById<RadioGroup>(R.id.fps_group)
        group.check(if (prefs.getInt(Prefs.FPS, Prefs.DEFAULT_FPS) == 15) R.id.fps_15 else R.id.fps_30)
        group.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt(Prefs.FPS, if (id == R.id.fps_15) 15 else 30).apply()
        }
    }

    private fun setUpSpeed() {
        speedLabel = findViewById(R.id.speed_label)
        val seek = findViewById<SeekBar>(R.id.speed_seek)
        seek.progress = prefs.getInt(Prefs.CLOUD_SPEED, 100)
        speedLabel.text = getString(R.string.cloud_speed_value, seek.progress)
        seek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                speedLabel.text = getString(R.string.cloud_speed_value, value)
                if (fromUser) prefs.edit().putInt(Prefs.CLOUD_SPEED, value).apply()
            }
        })
    }

    private fun setUpHours() {
        sunLabel = findViewById(R.id.sun_label)
        hoursGroup = findViewById(R.id.hours_group)
        val location = prefs.getString(Prefs.HOURS_MODE, Prefs.MODE_FIXED) == Prefs.MODE_LOCATION
        val granted = LocationHelper.hasPermission(this)
        // Permission revoked in system settings: fall back to fixed hours so UI and wallpaper agree.
        if (location && !granted) prefs.edit().putString(Prefs.HOURS_MODE, Prefs.MODE_FIXED).apply()
        hoursGroup.check(if (location && granted) R.id.hours_location else R.id.hours_fixed)
        hoursGroup.setOnCheckedChangeListener { _, id ->
            if (id == R.id.hours_location && !LocationHelper.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            } else {
                setHoursMode(id == R.id.hours_location)
            }
        }
        updateSunLabel()
    }

    private fun setUpLockScreen() {
        val switch = findViewById<CompoundButton>(R.id.lock_switch)
        val now = findViewById<Button>(R.id.lock_now)
        switch.isChecked = LockScreenUpdater.isEnabled(this)
        now.isEnabled = switch.isChecked
        switch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(Prefs.LOCK_SCREEN, on).apply()
            now.isEnabled = on
            if (on) updateLockScreen() else LockScreenUpdater.scheduleNext(this)
        }
        now.setOnClickListener { updateLockScreen() }
    }

    private fun updateLockScreen() {
        val app = applicationContext
        Thread {
            LockScreenUpdater.updateNow(app)
            LockScreenUpdater.scheduleNext(app)
            runOnUiThread { Toast.makeText(app, R.string.lock_done, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_LOCATION) return
        val granted = LocationHelper.hasPermission(this)
        if (!granted) hoursGroup.check(R.id.hours_fixed)
        setHoursMode(granted)
    }

    private fun setHoursMode(location: Boolean) {
        prefs.edit().putString(Prefs.HOURS_MODE, if (location) Prefs.MODE_LOCATION else Prefs.MODE_FIXED).apply()
        if (location) LocationHelper.refreshIfStale(this, force = true)
    }

    private fun updateSunLabel() {
        // Same calculation the wallpaper uses, run here on the main thread for display only.
        val place = controller.place
        val t = es.dasemu.wanderer.sky.Solar.sunTimes(java.time.LocalDate.now(place.zone), place.latitude, place.longitude, place.zone)
        fun fmt(m: Double?) = m?.let { String.format(Locale.ROOT, "%02d:%02d", (it / 60).toInt() % 24, (it % 60).toInt()) } ?: "—"
        val where = if (place == es.dasemu.wanderer.sky.Place.MADRID) getString(R.string.place_madrid) else getString(R.string.place_here)
        sunLabel.text = getString(R.string.sun_times, where, fmt(t.sunrise), fmt(t.sunset))
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        controller.applyPrefs()
        updateSunLabel()
        loop.requestRender()
    }

    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            controller.lowPower = getSystemService(PowerManager::class.java).isPowerSaveMode
            loop.requestRender()
        }
    }

    override fun onResume() {
        super.onResume()
        controller.lowPower = getSystemService(PowerManager::class.java).isPowerSaveMode
        registerReceiver(powerReceiver, android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        loop.setVisible(true)
    }

    override fun onPause() {
        loop.setVisible(false)
        unregisterReceiver(powerReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        loop.quit()
        super.onDestroy()
    }

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(s: SeekBar) = Unit
        override fun onStopTrackingTouch(s: SeekBar) = Unit
    }

    companion object {
        private const val REQ_LOCATION = 1
    }
}
