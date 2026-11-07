package es.dasemu.wanderer.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.util.Log
import es.dasemu.wanderer.LocationHelper
import es.dasemu.wanderer.Prefs
import es.dasemu.wanderer.sky.Palette
import es.dasemu.wanderer.sky.SkyModel
import java.time.LocalDate

/**
 * Keeps a static lock-screen picture in step with the time of day, for systems (e.g. MagicOS) that
 * don't allow third-party live wallpapers on the lock screen. It re-renders at the start of each light
 * phase (dawn, sunrise, morning, noon, golden hour, sunset, dusk, night): a handful of times per day.
 */
object LockScreenUpdater {
    private const val TAG = "LockScreenUpdater"
    const val ACTION_UPDATE = "es.dasemu.wanderer.action.UPDATE_LOCK_SCREEN"

    /** Inexact alarms may run a little late (Doze); this window lets the system batch them. */
    private const val WINDOW_MS = 10 * 60 * 1000L

    fun isEnabled(context: Context) = Prefs.get(context).getBoolean(Prefs.LOCK_SCREEN, false)

    /** Renders now and sets the lock screen. Blocking: call from a background thread. */
    fun updateNow(context: Context) {
        try {
            val bitmap = LockScreenSnapshot.render(context)
            WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            bitmap.recycle()
        } catch (e: Exception) {
            // IOException from the wallpaper service, or a GL failure: try again at the next phase.
            Log.e(TAG, "Lock screen update failed", e)
        }
    }

    fun scheduleNext(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        if (!isEnabled(context)) {
            alarms.cancel(pending)
            return
        }
        val now = System.currentTimeMillis()
        val place = LocationHelper.placeFromPrefs(context)
        val model = SkyModel(Palette.parseAll(context.assets.open("palettes.json").bufferedReader().use { it.readText() }))
        val today = LocalDate.now(place.zone)
        val next = (model.keyframeInstants(today, place) + model.keyframeInstants(today.plusDays(1), place))
            .firstOrNull { it > now + 60_000 } ?: (now + 3 * 60 * 60 * 1000L)
        alarms.setWindow(AlarmManager.RTC, next, WINDOW_MS, pending)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, LockScreenReceiver::class.java).setAction(ACTION_UPDATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
