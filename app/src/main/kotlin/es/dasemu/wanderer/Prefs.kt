package es.dasemu.wanderer

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FPS = "fps"
    const val CLOUD_SPEED = "cloud_speed"
    const val HOURS_MODE = "hours_mode"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val LOCATION_TIME = "location_time"
    const val LOCK_SCREEN = "lock_screen"

    const val MODE_FIXED = "fixed"
    const val MODE_LOCATION = "location"
    const val DEFAULT_FPS = 30

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("wanderer", Context.MODE_PRIVATE)
}
