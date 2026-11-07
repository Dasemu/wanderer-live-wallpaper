package es.dasemu.wanderer.sky

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Sun events for one local day, in minutes from local midnight. Null sunrise/sunset = polar day or night. */
data class SunTimes(val sunrise: Double?, val solarNoon: Double, val sunset: Double?)

/**
 * NOAA solar position algorithm (accurate to about a minute for sunrise/sunset between ±72° latitude).
 * Pure math, no Android dependencies, so it is unit-testable on the JVM.
 */
object Solar {
    private const val DEG = PI / 180.0

    /** Zenith for sunrise/sunset: 90° + refraction + solar disc radius. */
    private const val SUNRISE_ZENITH = 90.833

    private class Params(val declination: Double, val eqTimeMinutes: Double)

    private fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2_440_587.5

    private fun params(julianDay: Double): Params {
        val t = (julianDay - 2_451_545.0) / 36_525.0
        val l0 = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(m * DEG) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m * DEG) * (0.019993 - 0.000101 * t) +
            sin(3 * m * DEG) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(omega * DEG)
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(omega * DEG)
        val decl = asin(sin(eps * DEG) * sin(lambda * DEG))
        val y = tan(eps * DEG / 2).let { it * it }
        val l0r = l0 * DEG
        val mr = m * DEG
        val eqTime = 4.0 / DEG * (
            y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
                0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr)
            )
        return Params(decl, eqTime)
    }

    /** Solar elevation above the horizon in degrees (no refraction correction). */
    fun elevation(epochMillis: Long, latitude: Double, longitude: Double): Double {
        val p = params(julianDay(epochMillis))
        val utcMinutes = (epochMillis.mod(86_400_000L)) / 60_000.0
        val trueSolarTime = (utcMinutes + p.eqTimeMinutes + 4 * longitude).mod(1440.0)
        val hourAngle = (trueSolarTime / 4.0 - 180.0) * DEG
        val lat = latitude * DEG
        val cosZenith = (sin(lat) * sin(p.declination) + cos(lat) * cos(p.declination) * cos(hourAngle))
            .coerceIn(-1.0, 1.0)
        return 90.0 - acos(cosZenith) / DEG
    }

    fun sunTimes(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): SunTimes {
        val localNoon = ZonedDateTime.of(date.atTime(12, 0), zone)
        val offsetMinutes = localNoon.offset.totalSeconds / 60.0
        val p = params(julianDay(localNoon.toInstant().toEpochMilli()))
        val noonUtc = 720.0 - 4 * longitude - p.eqTimeMinutes
        val lat = latitude * DEG
        val cosHa = cos(SUNRISE_ZENITH * DEG) / (cos(lat) * cos(p.declination)) - tan(lat) * tan(p.declination)
        val noonLocal = noonUtc + offsetMinutes
        if (cosHa > 1.0 || cosHa < -1.0) return SunTimes(null, noonLocal, null)
        val haMinutes = 4.0 * acos(cosHa) / DEG
        return SunTimes(noonLocal - haMinutes, noonLocal, noonLocal + haMinutes)
    }

    /** Moon phase in [0, 1): 0 = new, 0.5 = full. Mean synodic month, good to within a few hours. */
    fun moonPhase(epochMillis: Long): Double {
        val days = julianDay(epochMillis) - 2_451_550.1
        val cycles = days / 29.530588853
        return cycles - floor(cycles)
    }
}
