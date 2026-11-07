package es.dasemu.wanderer.sky

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

class SkyModelTest {
    private val palettes = Palette.parseAll(File("src/main/assets/palettes.json").readText())
    private val model = SkyModel(palettes)
    private val madrid = Place.MADRID

    private fun at(h: Int, m: Int) =
        ZonedDateTime.of(2026, 11, 7, h, m, 0, 0, madrid.zone).toInstant().toEpochMilli()

    private fun eval(h: Int, m: Int) = SkyState().also { model.evaluate(at(h, m), madrid, it) }

    @Test
    fun deepNightIsTheNightPalette() {
        assertArrayEquals(palettes.getValue("night").values, eval(2, 0).palette.values, 1e-6f)
        assertArrayEquals(palettes.getValue("night").values, eval(23, 30).palette.values, 1e-6f)
    }

    @Test
    fun solarNoonIsTheNoonPalette() {
        val noon = model.also { eval(12, 0) }.sunTimes().solarNoon
        val s = eval((noon / 60).toInt(), (noon % 60).toInt())
        assertArrayEquals(palettes.getValue("noon").values, s.palette.values, 0.01f)
    }

    @Test
    fun blendingIsContinuousAcrossTheDay() {
        var prev = eval(0, 0).palette.values.copyOf()
        for (minute in 1 until 1440) {
            val cur = eval(minute / 60, minute % 60).palette.values
            for (i in cur.indices) assertEquals("minute $minute index $i", prev[i], cur[i], 0.06f)
            prev = cur.copyOf()
        }
    }

    @Test
    fun blendingIsContinuousWhenKeysCrossMidnight() {
        // Sunrise around 23:30 local the previous day: the dawn ramp spills before midnight.
        val place = Place(65.0, 0.0, java.time.ZoneId.of("Etc/GMT+2"))
        val m = SkyModel(palettes)
        fun at(minute: Int) = SkyState().also {
            val t = ZonedDateTime.of(2026, 6, 10, minute / 60, minute % 60, 0, 0, place.zone)
            m.evaluate(t.toInstant().toEpochMilli(), place, it)
        }.palette.values
        val beforeMidnight = SkyState().also {
            m.evaluate(ZonedDateTime.of(2026, 6, 9, 23, 59, 0, 0, place.zone).toInstant().toEpochMilli(), place, it)
        }.palette.values
        val afterMidnight = at(0)
        for (i in afterMidnight.indices) assertEquals(beforeMidnight[i], afterMidnight[i], 0.06f)
    }

    @Test
    fun sunIsUpAtNoonAndMoonAtNight() {
        val day = eval(13, 0)
        assertEquals(1f, day.sunVisibility, 1e-3f)
        assertTrue(day.sunY > model.horizon)
        val night = eval(23, 0)
        assertEquals(0f, night.sunVisibility, 1e-3f)
        assertTrue(night.moonVisibility > 0.5f)
    }

    @Test
    fun moonMovesSmoothlyThroughMidnight() {
        val before = SkyState().also { model.evaluate(at(23, 59), madrid, it) }
        val after = SkyState().also {
            model.evaluate(ZonedDateTime.of(2026, 11, 8, 0, 0, 0, 0, madrid.zone).toInstant().toEpochMilli(), madrid, it)
        }
        assertEquals(before.moonX, after.moonX, 0.01f)
        assertEquals(before.moonY, after.moonY, 0.01f)
        assertEquals(before.moonVisibility, after.moonVisibility, 0.01f)
        assertTrue(before.moonVisibility > 0.5f)
    }

    @Test
    fun keyframeInstantsAreOrderedAndInsideTheDayAroundSun() {
        val day = java.time.LocalDate.of(2026, 11, 7)
        val keys = SkyModel(palettes).keyframeInstants(day, madrid)
        assertEquals(8, keys.size)
        assertTrue(keys.zipWithNext().all { (a, b) -> a < b })
        val sunrise = ZonedDateTime.of(2026, 11, 7, 7, 52, 0, 0, madrid.zone).toInstant().toEpochMilli()
        // Second key is sunrise itself (dawn, sunrise, ...). Reference: 07:52 CET.
        assertEquals(sunrise.toDouble(), keys[1].toDouble(), 3 * 60_000.0)
    }

    @Test
    fun paletteParsingConvertsSrgbToLinear() {
        val white = Palette.srgbToLinear(1f)
        val mid = Palette.srgbToLinear(0.5f)
        assertEquals(1f, white, 1e-6f)
        assertEquals(0.214f, mid, 1e-3f)
    }
}
