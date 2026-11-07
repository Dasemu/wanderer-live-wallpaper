package es.dasemu.wanderer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.util.Log
import es.dasemu.wanderer.R
import es.dasemu.wanderer.sky.Palette
import es.dasemu.wanderer.sky.SkyState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Per-frame inputs that do not come from the sky model. */
class FrameInput {
    var deltaSeconds = 0f
    var parallax = 0.5f
    var zoom = 0f
    var cloudSpeed = 1f
    var lowPower = false
}

/**
 * Draws the scene with two passes: procedural fog into a small FBO, then one full-screen composite.
 * Must only be used on the thread that owns the GL context.
 */
class SceneRenderer(private val context: Context, private val config: SceneConfig) {

    private var fogProgram = 0
    private var sceneProgram = 0
    private var texBase = 0
    private var texPlate = 0
    private var texMask = 0
    private var texFog = 0
    private var fbo = 0
    private var fogW = 0
    private var fogH = 0
    private var fogLowPower = false
    private var width = 0
    private var height = 0
    private var loadedForWidth = 0
    private var paintAspect = 0.78f

    private val rectPlate = FloatArray(4)
    private val rectMid = FloatArray(4)
    private val rectFg = FloatArray(4)

    // Animation clocks, wrapped so float precision never degrades.
    private var time = 0f
    private var flowTime = 0f
    private val offHaze = FloatArray(2)
    private val offSea = FloatArray(2)
    private val offWisps = FloatArray(2)
    private val offWarp = FloatArray(2)

    private lateinit var fogU: Map<String, Int>
    private lateinit var sceneU: Map<String, Int>

    var screenHorizon = 0.4f
        private set
    var screenMoonFloor = 0.6f
        private set

    fun onSurfaceCreated() {
        val vs = asset("shaders/fullscreen.vert")
        fogProgram = GlUtil.program(vs, asset("shaders/fog.frag"))
        sceneProgram = GlUtil.program(vs, asset("shaders/scene.frag"))
        fogU = GlUtil.uniforms(
            fogProgram, "uRect", "uAspect", "uHorizon", "uOctaves",
            "uOffset0", "uOffset1", "uOffset2", "uWarp", "uParallax", "uFogDepth",
        )
        sceneU = GlUtil.uniforms(
            sceneProgram, "uBase", "uPlate", "uMask", "uFog", "uRectPlate", "uRectMid", "uRectFg",
            "uSkyTop", "uSkyHorizon", "uSun", "uAmbient", "uFogLit", "uFogShadow", "uLightColor",
            "uGlow", "uStars", "uHaze", "uSunPos", "uMoonPos", "uLightPos", "uSunVis", "uMoonVis",
            "uMoonPhase", "uScreenAspect", "uPaintAspect", "uScreenHorizon", "uHorizon", "uSkyRef",
            "uTime", "uFlowTime", "uFlowFog", "uFlowSky", "uZoom", "uSkyline",
        )
        // Called once per GL context (first surface, or after a context loss): any old handles
        // belong to a dead context, so forget them and reload.
        loadedForWidth = 0
        fbo = 0
        texFog = 0
        texBase = 0
        texPlate = 0
        texMask = 0
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        width = w
        height = h
        val neededWidth = (w / config.visibleWidth).toInt()
        if (loadedForWidth == 0 || neededWidth > loadedForWidth * 1.1f || neededWidth < loadedForWidth * 0.6f) {
            loadTextures(neededWidth)
            loadedForWidth = neededWidth
        }
        allocateFog(fogLowPower)
    }

    /** Fills the layer rects for the current parallax and returns the horizon height on screen. */
    fun updateLayout(parallax: Float) {
        LayerMapping.rect(rectPlate, width, height, paintAspect, config.visibleWidth, config.cropAnchorV, parallax, config.parallaxPlate)
        LayerMapping.rect(rectMid, width, height, paintAspect, config.visibleWidth, config.cropAnchorV, parallax, config.parallaxMid)
        LayerMapping.rect(rectFg, width, height, paintAspect, config.visibleWidth, config.cropAnchorV, parallax, config.parallaxFg)
        screenHorizon = LayerMapping.screenY(rectPlate, config.horizon)
        screenMoonFloor = LayerMapping.screenY(rectPlate, config.moonFloor)
    }

    fun draw(sky: SkyState, input: FrameInput) {
        if (width == 0) return
        if (input.lowPower != fogLowPower) allocateFog(input.lowPower)
        // Battery saver: everything stands still (fog, flow, twinkle); only the light changes.
        advanceClocks(input.deltaSeconds * input.cloudSpeed, if (input.lowPower) 0f else input.deltaSeconds)
        updateLayout(input.parallax)

        // Pass 1: fog densities at low resolution.
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, fogW, fogH)
        glUseProgram(fogProgram)
        glUniform4fv(fogU.getValue("uRect"), 1, rectPlate, 0)
        glUniform1f(fogU.getValue("uAspect"), paintAspect)
        glUniform1f(fogU.getValue("uHorizon"), config.horizon)
        glUniform1i(fogU.getValue("uOctaves"), if (input.lowPower) 3 else 5)
        glUniform2fv(fogU.getValue("uOffset0"), 1, offHaze, 0)
        glUniform2fv(fogU.getValue("uOffset1"), 1, offSea, 0)
        glUniform2fv(fogU.getValue("uOffset2"), 1, offWisps, 0)
        glUniform2fv(fogU.getValue("uWarp"), 1, offWarp, 0)
        glUniform1f(fogU.getValue("uParallax"), input.parallax - 0.5f)
        glUniform2f(fogU.getValue("uFogDepth"), config.parallaxFogSea, config.parallaxFogWisps)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        // Pass 2: composite at full resolution.
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        glUseProgram(sceneProgram)
        bind(0, texBase, "uBase")
        bind(1, texPlate, "uPlate")
        bind(2, texMask, "uMask")
        bind(3, texFog, "uFog")
        glUniform4fv(sceneU.getValue("uRectPlate"), 1, rectPlate, 0)
        glUniform4fv(sceneU.getValue("uRectMid"), 1, rectMid, 0)
        glUniform4fv(sceneU.getValue("uRectFg"), 1, rectFg, 0)

        val p = sky.palette.values
        glUniform3fv(sceneU.getValue("uSkyTop"), 1, p, Palette.SKY_TOP)
        glUniform3fv(sceneU.getValue("uSkyHorizon"), 1, p, Palette.SKY_HORIZON)
        glUniform3fv(sceneU.getValue("uSun"), 1, p, Palette.SUN)
        glUniform3fv(sceneU.getValue("uAmbient"), 1, p, Palette.AMBIENT)
        glUniform3fv(sceneU.getValue("uFogLit"), 1, p, Palette.FOG_LIT)
        glUniform3fv(sceneU.getValue("uFogShadow"), 1, p, Palette.FOG_SHADOW)
        glUniform3fv(sceneU.getValue("uLightColor"), 1, sky.lightColor, 0)
        glUniform1f(sceneU.getValue("uGlow"), p[Palette.GLOW])
        glUniform1f(sceneU.getValue("uStars"), p[Palette.STARS])
        glUniform1f(sceneU.getValue("uHaze"), p[Palette.HAZE])
        glUniform2f(sceneU.getValue("uSunPos"), sky.sunX, sky.sunY)
        glUniform2f(sceneU.getValue("uMoonPos"), sky.moonX, sky.moonY)
        glUniform2f(sceneU.getValue("uLightPos"), sky.lightX, sky.lightY)
        glUniform1f(sceneU.getValue("uSunVis"), sky.sunVisibility)
        glUniform1f(sceneU.getValue("uMoonVis"), sky.moonVisibility)
        glUniform1f(sceneU.getValue("uMoonPhase"), sky.moonPhase)
        glUniform1f(sceneU.getValue("uScreenAspect"), width.toFloat() / height)
        glUniform1f(sceneU.getValue("uPaintAspect"), paintAspect)
        glUniform1f(sceneU.getValue("uScreenHorizon"), screenHorizon)
        glUniform1f(sceneU.getValue("uHorizon"), config.horizon)
        glUniform1f(sceneU.getValue("uSkyRef"), config.skyRef)
        glUniform1f(sceneU.getValue("uTime"), time)
        glUniform1f(sceneU.getValue("uFlowTime"), flowTime)
        glUniform1f(sceneU.getValue("uFlowFog"), config.flowFog)
        glUniform1f(sceneU.getValue("uFlowSky"), config.flowSky)
        glUniform1f(sceneU.getValue("uZoom"), input.zoom)
        glUniform4fv(sceneU.getValue("uSkyline"), config.skyline.size / 4, config.skyline, 0)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    fun release() {
        // Called while the context is still current; a lost context frees everything anyway.
        glDeleteTextures(4, intArrayOf(texBase, texPlate, texMask, texFog), 0)
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteProgram(fogProgram)
        glDeleteProgram(sceneProgram)
        texBase = 0; texPlate = 0; texMask = 0; texFog = 0; fbo = 0
        loadedForWidth = 0
    }

    private fun advanceClocks(cloudDt: Float, realDt: Float) {
        time = (time + realDt) % 628.3185f
        flowTime = (flowTime + cloudDt / config.flowPeriodSeconds) % 1f
        // Noise units per second, matching tools/preview (drift * 10).
        drift(offHaze, config.driftHaze, cloudDt)
        drift(offSea, config.driftSea, cloudDt)
        drift(offWisps, config.driftWisps, cloudDt)
        drift(offWarp, config.driftWarp, cloudDt)
    }

    private fun drift(off: FloatArray, speed: FloatArray, dt: Float) {
        for (i in 0..1) off[i] = ((off[i] + speed[i] * 10f * dt) % NOISE_PERIOD + NOISE_PERIOD) % NOISE_PERIOD
    }

    private fun bind(unit: Int, tex: Int, name: String) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(sceneU.getValue(name), unit)
    }

    private fun allocateFog(lowPower: Boolean) {
        fogLowPower = lowPower
        if (width == 0) return
        val div = if (lowPower) 5 else 3
        fogW = max(1, width / div)
        fogH = max(1, height / div)
        if (texFog == 0) texFog = GlUtil.genTexture(GL_LINEAR, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, texFog)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fogW, fogH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        if (fbo == 0) {
            val ids = IntArray(1)
            glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
        }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texFog, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun loadTextures(neededWidth: Int) {
        if (texBase != 0) glDeleteTextures(3, intArrayOf(texBase, texPlate, texMask), 0)
        texBase = uploadColor(R.drawable.scene_base, neededWidth)
        texPlate = uploadColor(R.drawable.scene_plate, neededWidth)
        texMask = uploadMask(R.drawable.scene_mask)
        Log.i(TAG, "textures loaded for painting width $neededWidth")
    }

    /** Opaque colour layer, scaled on the CPU to the size it is displayed at. */
    private fun uploadColor(res: Int, neededWidth: Int): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(context.resources, res, bounds)
        paintAspect = bounds.outWidth.toFloat() / bounds.outHeight
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= neededWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inScaled = false; inSampleSize = sample }
        var bmp = BitmapFactory.decodeResource(context.resources, res, opts)
        if (bmp.width > neededWidth * 1.15f) {
            val scaled = Bitmap.createScaledBitmap(bmp, neededWidth, (neededWidth / paintAspect).toInt(), true)
            bmp.recycle()
            bmp = scaled
        }
        val tex = GlUtil.genTexture(GL_LINEAR, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return tex
    }

    /** RGBA mask: decoded unpremultiplied (alpha is data, not coverage) and uploaded byte for byte. */
    private fun uploadMask(res: Int): Int {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeResource(context.resources, res, opts)
        val buf = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)
        buf.position(0)
        val tex = GlUtil.genTexture(GL_LINEAR, GL_CLAMP_TO_EDGE)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, bmp.width, bmp.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        bmp.recycle()
        return tex
    }

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        private const val TAG = "SceneRenderer"
        private const val NOISE_PERIOD = 32f
    }
}
