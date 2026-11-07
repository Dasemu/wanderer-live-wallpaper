package es.dasemu.wanderer.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SolarTest {
    private val madrid = Place.MADRID

    private fun minutes(h: Int, m: Int) = h * 60.0 + m

    @Test
    fun madridSummerSolstice() {
        // Reference (timeanddate.com): sunrise 06:45, sunset 21:48 CEST.
        val t = Solar.sunTimes(LocalDate.of(2026, 6, 21), madrid.latitude, madrid.longitude, madrid.zone)
        assertEquals(minutes(6, 45), t.sunrise!!, 3.0)
        assertEquals(minutes(21, 48), t.sunset!!, 3.0)
    }

    @Test
    fun madridWinterSolstice() {
        // Reference: sunrise 08:33, sunset 17:51 CET.
        val t = Solar.sunTimes(LocalDate.of(2026, 12, 21), madrid.latitude, madrid.longitude, madrid.zone)
        assertEquals(minutes(8, 33), t.sunrise!!, 3.0)
        assertEquals(minutes(17, 51), t.sunset!!, 3.0)
    }

    @Test
    fun daylightSavingShiftsLocalSunTimesByAnHour() {
        // Spain moves to CEST on 2026-03-29 and back to CET on 2026-10-25.
        fun t(y: Int, m: Int, d: Int) = Solar.sunTimes(LocalDate.of(y, m, d), madrid.latitude, madrid.longitude, madrid.zone)
        val springDelta = t(2026, 3, 29).sunrise!! - t(2026, 3, 28).sunrise!!
        val autumnDelta = t(2026, 10, 25).sunset!! - t(2026, 10, 24).sunset!!
        assertEquals(60.0 - 1.5, springDelta, 1.0)   // +1 h, minus the ~1.5 min/day of spring
        assertEquals(-60.0 - 1.5, autumnDelta, 1.0)  // -1 h, minus the ~1.5 min/day of autumn
    }

    @Test
    fun polarNightHasNoSunrise() {
        val t = Solar.sunTimes(LocalDate.of(2026, 12, 21), 78.2, 15.6, ZoneId.of("Arctic/Longyearbyen"))
        assertNull(t.sunrise)
        assertNull(t.sunset)
    }

    @Test
    fun elevationIsHighAtNoonAndNegativeAtMidnight() {
        val noon = ZonedDateTime.of(2026, 6, 21, 14, 15, 0, 0, madrid.zone).toInstant().toEpochMilli()
        val midnight = ZonedDateTime.of(2026, 6, 21, 2, 0, 0, 0, madrid.zone).toInstant().toEpochMilli()
        // Max elevation in Madrid at the solstice is 90 - 40.4 + 23.44 ≈ 73°.
        assertEquals(73.0, Solar.elevation(noon, madrid.latitude, madrid.longitude), 1.0)
        assertTrue(Solar.elevation(midnight, madrid.latitude, madrid.longitude) < -15)
    }

    @Test
    fun moonPhaseMatchesKnownFullMoon() {
        // Full moon on 2026-11-24 14:53 UTC.
        val full = ZonedDateTime.of(2026, 11, 24, 14, 53, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(0.5, Solar.moonPhase(full), 0.03)
    }
}
