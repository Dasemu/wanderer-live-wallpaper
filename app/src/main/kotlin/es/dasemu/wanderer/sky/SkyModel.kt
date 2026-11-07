package es.dasemu.wanderer.sky

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where the day is happening: coordinates plus the zone used for local clock time. */
data class Place(val latitude: Double, val longitude: Double, val zone: ZoneId) {
    companion object {
        /** Fallback when location is off or unknown: Madrid. */
        val MADRID = Place(40.4168, -3.7038, ZoneId.of("Europe/Madrid"))
    }
}

/** Everything the renderer needs about the sky at one instant. Mutable and reused to avoid allocations. */
class SkyState {
    val palette = Palette()
    var sunX = 0.5f; var sunY = 0.5f; var sunVisibility = 0f
    var moonX = 0.5f; var moonY = 0.5f; var moonVisibility = 0f
    /** 0 = new moon, 0.5 = full. */
    var moonPhase = 0.5f
    var lightX = 0.5f; var lightY = 0.5f
    val lightColor = FloatArray(3)
    var minuteOfDay = 0.0
}

/**
 * Time-of-day model: builds keyframes around the real sunrise, solar noon and sunset of the place,
 * then blends between the palettes of palettes.json with a smoothstep.
 */
class SkyModel(palettes: Map<String, Palette>) {

    /** Screen-space (uv, y up) height of the horizon; the renderer updates it on resize. */
    var horizon = 0.4f

    private class Key(var minute: Double, val palette: Palette)

    private val night = palettes.getValue("night")
    private val keys = listOf(
        Key(0.0, night),
        Key(0.0, palettes.getValue("dawn")),
        Key(0.0, palettes.getValue("sunrise")),
        Key(0.0, palettes.getValue("morning")),
        Key(0.0, palettes.getValue("noon")),
        Key(0.0, palettes.getValue("golden")),
        Key(0.0, palettes.getValue("sunset")),
        Key(0.0, palettes.getValue("dusk")),
        Key(0.0, night),
    )

    private var cachedDate: LocalDate? = null
    private var cachedPlace: Place? = null
    private var times = SunTimes(420.0, 840.0, 1260.0)

    fun sunTimes(): SunTimes = times

    fun evaluate(epochMillis: Long, place: Place, out: SkyState) {
        val local = Instant.ofEpochMilli(epochMillis).atZone(place.zone)
        val date = local.toLocalDate()
        if (date != cachedDate || place != cachedPlace) {
            times = resolveTimes(date, place)
            layoutKeys(times)
            cachedDate = date
            cachedPlace = place
        }
        val minute = local.hour * 60 + local.minute + local.second / 60.0 + local.nano / 6e10
        out.minuteOfDay = minute
        blend(minute, out.palette)

        val sunrise = times.sunrise!!
        val sunset = times.sunset!!
        val elevation = Solar.elevation(epochMillis, place.latitude, place.longitude)
        val elevationRad = elevation * PI / 180.0

        // Sun travels left to right over the day; its height follows the real elevation.
        val dayProgress = (minute - sunrise) / (sunset - sunrise)
        out.sunX = (0.5 + (dayProgress - 0.5) * 0.9).toFloat()
        out.sunY = (horizon + 0.42 * sin(elevationRad)).toFloat()
        out.sunVisibility = smoothstep(-7.0, 1.0, elevation).toFloat()

        // Moon crosses the sky during the night with a simple arc (artistic, not astronomical).
        val nightLength = 1440.0 - (sunset - sunrise)
        val nightProgress = ((minute - sunset).mod(1440.0)) / nightLength
        val moonArc = if (nightProgress in 0.0..1.0) sin(PI * nightProgress) else -0.3
        out.moonX = (0.15 + 0.7 * nightProgress.coerceIn(0.0, 1.0)).toFloat()
        out.moonY = (horizon - 0.04 + 0.42 * moonArc).toFloat()
        out.moonPhase = Solar.moonPhase(epochMillis).toFloat()
        out.moonVisibility = (smoothstep(-3.0, -9.0, elevation) * smoothstep(-0.1, 0.15, moonArc)).toFloat()

        // Key light for rim lighting and fog scattering: the sun by day, the moon by night.
        val illum = 0.5 * (1 - cos(2 * PI * out.moonPhase))
        val sunWeight = out.sunVisibility
        val moonWeight = (out.moonVisibility * (0.25 + 0.75 * illum)).toFloat()
        val total = max(sunWeight + moonWeight, 1e-4f)
        out.lightX = (out.sunX * sunWeight + out.moonX * moonWeight) / total
        out.lightY = (out.sunY * sunWeight + out.moonY * moonWeight) / total
        val sun = out.palette.values
        for (i in 0..2) {
            out.lightColor[i] = sun[Palette.SUN + i] * sunWeight + MOONLIGHT[i] * moonWeight * 0.45f
        }
    }

    private fun resolveTimes(date: LocalDate, place: Place): SunTimes {
        val t = Solar.sunTimes(date, place.latitude, place.longitude, place.zone)
        if (t.sunrise != null && t.sunset != null) return t
        // Polar day or night: use Madrid's schedule so the wallpaper still has a day cycle.
        val m = Solar.sunTimes(date, Place.MADRID.latitude, Place.MADRID.longitude, Place.MADRID.zone)
        return SunTimes(m.sunrise, m.solarNoon, m.sunset)
    }

    private fun layoutKeys(t: SunTimes) {
        val r = t.sunrise!!
        val n = t.solarNoon
        val s = t.sunset!!
        val minutes = doubleArrayOf(
            r - 100, r - 40, r, min(r + 120, (r + n) / 2),
            n, max(s - 70, (n + s) / 2), s, s + 40, s + 100,
        )
        // Keep strictly increasing even on extreme latitudes.
        for (i in 1 until minutes.size) minutes[i] = max(minutes[i], minutes[i - 1] + 1)
        for (i in keys.indices) keys[i].minute = minutes[i]
    }

    private fun blend(minute: Double, out: Palette) {
        // Keys can spill past either midnight (e.g. sunrise before 01:40 or dusk after 22:20),
        // so try the minute in yesterday's, today's and tomorrow's frame.
        for (m in doubleArrayOf(minute, minute + 1440.0, minute - 1440.0)) {
            if (blendAt(m, out)) return
        }
        out.copyFrom(night)
    }

    private fun blendAt(m: Double, out: Palette): Boolean {
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (m >= a.minute && m < b.minute) {
                val t = smoothstep(a.minute, b.minute, m).toFloat()
                out.setLerp(a.palette, b.palette, t)
                return true
            }
        }
        return false
    }

    companion object {
        private val MOONLIGHT = floatArrayOf(0.55f, 0.65f, 0.9f)

        fun smoothstep(e0: Double, e1: Double, x: Double): Double {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }
}
