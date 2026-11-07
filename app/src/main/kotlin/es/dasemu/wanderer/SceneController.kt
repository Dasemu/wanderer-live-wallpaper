package es.dasemu.wanderer

import android.content.Context
import es.dasemu.wanderer.render.FrameInput
import es.dasemu.wanderer.render.SceneConfig
import es.dasemu.wanderer.render.SceneRenderer
import es.dasemu.wanderer.sky.Palette
import es.dasemu.wanderer.sky.Place
import es.dasemu.wanderer.sky.SkyModel
import es.dasemu.wanderer.sky.SkyState
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.min

/**
 * Glue between settings (written from the main thread) and the GL thread, which reads them each frame.
 * Shared by the wallpaper engine and the settings preview.
 */
class SceneController(val context: Context) {
    val config = SceneConfig.parse(asset("scene.json"))
    private val model = SkyModel(Palette.parseAll(asset("palettes.json")))
    val sky = SkyState()

    @Volatile var fps = Prefs.DEFAULT_FPS
    @Volatile var cloudSpeed = 1f
    @Volatile var lowPower = false
    @Volatile var place: Place = Place.MADRID
        set(value) { field = value; skyDirty = true }

    /** Minute of the day to show instead of the clock (settings preview only); -1 = live. */
    @Volatile var timeOverrideMinute = -1
        set(value) { field = value; skyDirty = true }
    @Volatile var parallaxTarget = 0.5f
    @Volatile var zoom = 0f

    @Volatile private var skyDirty = true
    private var parallax = 0.5f
    private var lastSkyEvalMs = 0L

    fun sunTimes() = model.sunTimes()

    fun applyPrefs() {
        val p = Prefs.get(context)
        fps = p.getInt(Prefs.FPS, Prefs.DEFAULT_FPS)
        cloudSpeed = p.getInt(Prefs.CLOUD_SPEED, 100) / 100f
        place = LocationHelper.placeFromPrefs(context)
    }

    /** GL thread: after a resize, so the sun is placed relative to the painted horizon. */
    fun onLayout(renderer: SceneRenderer) {
        renderer.updateLayout(parallax)
        model.horizon = renderer.screenHorizon
        skyDirty = true
    }

    /** GL thread, once per frame. */
    fun prepareFrame(dt: Float, input: FrameInput, renderer: SceneRenderer) {
        parallax += (parallaxTarget - parallax) * min(1f, dt * 5f)
        input.deltaSeconds = dt
        input.parallax = parallax
        input.zoom = zoom
        input.cloudSpeed = cloudSpeed
        input.lowPower = lowPower

        // Colours move over minutes, so re-evaluating the sky once a second is plenty.
        val now = System.currentTimeMillis()
        if (skyDirty || now - lastSkyEvalMs >= 1000) {
            skyDirty = false
            lastSkyEvalMs = now
            model.horizon = renderer.screenHorizon
            model.evaluate(effectiveTime(now), place, sky)
        }
    }

    private fun effectiveTime(now: Long): Long {
        val minute = timeOverrideMinute
        if (minute < 0) return now
        val today = LocalDate.now(place.zone)
        return ZonedDateTime.of(today.atTime(minute / 60, minute % 60), place.zone).toInstant().toEpochMilli()
    }

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
}
