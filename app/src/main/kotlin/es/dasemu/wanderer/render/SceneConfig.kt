package es.dasemu.wanderer.render

import org.json.JSONObject

/** Layout and motion tuning from assets/scene.json (see the comment in that file). */
class SceneConfig(
    val horizon: Float,
    val moonFloor: Float,
    val skyRef: Float,
    val visibleWidth: Float,
    val parallaxPlate: Float,
    val parallaxMid: Float,
    val parallaxFg: Float,
    val parallaxFogSea: Float,
    val parallaxFogWisps: Float,
    val flowFog: Float,
    val flowSky: Float,
    val flowPeriodSeconds: Float,
    val driftHaze: FloatArray,
    val driftSea: FloatArray,
    val driftWisps: FloatArray,
    val driftWarp: FloatArray,
    val cropAnchorV: Float,
    /** Painting v of the highest land per column slice; sun, moon and stars are drawn only above it. */
    val skyline: FloatArray,
) {
    companion object {
        fun parse(json: String): SceneConfig {
            val o = JSONObject(json)
            val parallax = o.getJSONObject("parallax")
            val flow = o.getJSONObject("flow")
            val drift = o.getJSONObject("drift")
            fun pair(key: String) = drift.getJSONArray(key).let {
                floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
            }
            return SceneConfig(
                horizon = o.getDouble("horizon").toFloat(),
                moonFloor = o.getDouble("moonFloor").toFloat(),
                skyRef = o.getDouble("skyRef").toFloat(),
                visibleWidth = o.getDouble("visibleWidth").toFloat(),
                parallaxPlate = parallax.getDouble("plate").toFloat(),
                parallaxMid = parallax.getDouble("mid").toFloat(),
                parallaxFg = parallax.getDouble("fg").toFloat(),
                parallaxFogSea = parallax.getDouble("fogSea").toFloat(),
                parallaxFogWisps = parallax.getDouble("fogWisps").toFloat(),
                flowFog = flow.getDouble("fog").toFloat(),
                flowSky = flow.getDouble("sky").toFloat(),
                flowPeriodSeconds = flow.getDouble("periodSeconds").toFloat(),
                driftHaze = pair("haze"),
                driftSea = pair("sea"),
                driftWisps = pair("wisps"),
                driftWarp = pair("warp"),
                cropAnchorV = o.getDouble("cropAnchorV").toFloat(),
                skyline = o.getJSONArray("skyline").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                    .also { require(it.size == 128) { "skyline must have 128 values, got ${it.size}" } },
            )
        }
    }
}

/**
 * Maps screen uv (x right, y down) to painting uv for one parallax layer: painting = screen * scale + offset.
 * Portrait screens show [visibleWidth] of the painting's width and extend the sky above it;
 * wider screens crop the painting vertically around [SceneConfig.cropAnchorV].
 */
object LayerMapping {
    fun rect(
        out: FloatArray,
        screenW: Int,
        screenH: Int,
        paintAspect: Float,
        visibleWidth: Float,
        cropAnchorV: Float,
        parallax: Float,
        depth: Float,
    ) {
        val paintW = screenW / visibleWidth
        val paintH = paintW / paintAspect
        val sx = screenW / paintW
        val sy = screenH / paintH
        val oy = if (paintH < screenH) {
            -(screenH - paintH) / paintH
        } else {
            (cropAnchorV - 0.5f * sy).coerceIn(0f, 1f - sy)
        }
        val margin = 1f - sx
        out[0] = sx
        out[1] = sy
        out[2] = margin / 2f + (parallax - 0.5f) * margin * depth
        out[3] = oy
    }

    /** Screen uv height (y up) at which painting row [v] appears. */
    fun screenY(rect: FloatArray, v: Float): Float = 1f - (v - rect[3]) / rect[1]
}
