package es.dasemu.wanderer.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Phase alarms, plus boot / clock / time-zone changes, which invalidate the schedule. */
class LockScreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LockScreenUpdater.ACTION_UPDATE, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (!LockScreenUpdater.isEnabled(context)) return
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                LockScreenUpdater.updateNow(app)
                LockScreenUpdater.scheduleNext(app)
            } finally {
                result.finish()
            }
        }.start()
    }
}
