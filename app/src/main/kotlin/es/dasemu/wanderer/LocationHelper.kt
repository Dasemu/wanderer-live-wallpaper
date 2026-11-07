package es.dasemu.wanderer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import es.dasemu.wanderer.sky.Place
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Coarse location for sunrise/sunset, using only the platform LocationManager (no Play Services).
 * Coordinates are rounded to ~1 km and stored locally; they never leave the device.
 */
object LocationHelper {
    private const val REFRESH_MS = 3 * 60 * 60 * 1000L

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun placeFromPrefs(context: Context): Place {
        val p = Prefs.get(context)
        if (p.getString(Prefs.HOURS_MODE, Prefs.MODE_FIXED) != Prefs.MODE_LOCATION) return Place.MADRID
        if (!p.contains(Prefs.LATITUDE)) return Place.MADRID
        return Place(
            p.getFloat(Prefs.LATITUDE, 0f).toDouble(),
            p.getFloat(Prefs.LONGITUDE, 0f).toDouble(),
            ZoneId.systemDefault(),
        )
    }

    /** Refreshes the stored location if location mode is on and the last fix is old. Safe to call often. */
    fun refreshIfStale(context: Context, force: Boolean = false) {
        val p = Prefs.get(context)
        if (p.getString(Prefs.HOURS_MODE, Prefs.MODE_FIXED) != Prefs.MODE_LOCATION) return
        if (!hasPermission(context)) return
        if (!force && System.currentTimeMillis() - p.getLong(Prefs.LOCATION_TIME, 0) < REFRESH_MS) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val providers = lm.getProviders(true)
        try {
            val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (last != null) store(context, last)
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .firstOrNull { it in providers } ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { loc -> if (loc != null) store(context, loc) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, LocationListener { store(context, it) }, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call: keep the stored place.
        }
    }

    private fun store(context: Context, loc: Location) {
        fun round(v: Double) = (v * 100).roundToInt() / 100f
        Prefs.get(context).edit()
            .putFloat(Prefs.LATITUDE, round(loc.latitude))
            .putFloat(Prefs.LONGITUDE, round(loc.longitude))
            .putLong(Prefs.LOCATION_TIME, System.currentTimeMillis())
            .apply()
    }
}
