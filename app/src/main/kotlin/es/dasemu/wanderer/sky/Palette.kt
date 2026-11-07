package es.dasemu.wanderer.sky

import org.json.JSONObject
import kotlin.math.pow

/**
 * One time-of-day colour keyframe, stored flat in linear RGB so that blending allocates nothing.
 * Layout: see the offsets below; every colour takes 3 floats, scalars take 1.
 */
class Palette(val values: FloatArray = FloatArray(SIZE)) {
    companion object {
        const val SKY_TOP = 0
        const val SKY_HORIZON = 3
        const val SUN = 6
        const val AMBIENT = 9
        const val FOG_LIT = 12
        const val FOG_SHADOW = 15
        const val GLOW = 18
        const val STARS = 19
        const val HAZE = 20
        const val SIZE = 21

        private val COLOR_KEYS = listOf(
            "skyTop" to SKY_TOP, "skyHorizon" to SKY_HORIZON, "sun" to SUN,
            "ambient" to AMBIENT, "fogLit" to FOG_LIT, "fogShadow" to FOG_SHADOW,
        )
        private val SCALAR_KEYS = listOf("glow" to GLOW, "stars" to STARS, "haze" to HAZE)

        fun srgbToLinear(c: Float): Float =
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

        fun fromJson(o: JSONObject): Palette {
            val p = Palette()
            for ((key, off) in COLOR_KEYS) {
                val hex = o.getString(key).removePrefix("#")
                require(hex.length == 6) { "Colour '$key' must be #RRGGBB, got '$hex'" }
                val rgb = hex.toInt(16)
                p.values[off] = srgbToLinear(((rgb shr 16) and 0xFF) / 255f)
                p.values[off + 1] = srgbToLinear(((rgb shr 8) and 0xFF) / 255f)
                p.values[off + 2] = srgbToLinear((rgb and 0xFF) / 255f)
            }
            for ((key, off) in SCALAR_KEYS) p.values[off] = o.getDouble(key).toFloat()
            return p
        }

        /** Parses the `palettes` object of palettes.json into keyframe name -> palette. */
        fun parseAll(json: String): Map<String, Palette> {
            val root = JSONObject(json).getJSONObject("palettes")
            return root.keys().asSequence().associateWith { fromJson(root.getJSONObject(it)) }
        }
    }

    fun setLerp(a: Palette, b: Palette, t: Float) {
        for (i in 0 until SIZE) values[i] = a.values[i] + (b.values[i] - a.values[i]) * t
    }

    fun copyFrom(other: Palette) = other.values.copyInto(values)
}
