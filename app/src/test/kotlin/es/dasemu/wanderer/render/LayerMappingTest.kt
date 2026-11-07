package es.dasemu.wanderer.render

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerMappingTest {
    private val rect = FloatArray(4)

    @Test
    fun portraitExtendsSkyAboveAndKeepsBottomAnchored() {
        LayerMapping.rect(rect, 1080, 2400, 0.78f, 0.8f, 0.6f, 0.5f, 1f)
        // Bottom of the screen (v_screen = 1) maps to the bottom of the painting.
        assertEquals(1f, rect[1] + rect[3], 1e-4f)
        // Top of the screen lies above the painting.
        assertEquals(true, rect[3] < 0f)
        // 80% of the width is visible, centred when parallax is in the middle.
        assertEquals(0.8f, rect[0], 1e-4f)
        assertEquals(0.1f, rect[2], 1e-4f)
    }

    @Test
    fun parallaxStaysInsideThePainting() {
        for (p in listOf(0f, 1f)) {
            LayerMapping.rect(rect, 1080, 2400, 0.78f, 0.8f, 0.6f, p, 1f)
            assertEquals(true, rect[2] >= -1e-4f && rect[2] + rect[0] <= 1f + 1e-4f)
        }
    }

    @Test
    fun landscapeCropsVertically() {
        LayerMapping.rect(rect, 2400, 1080, 0.78f, 0.8f, 0.6f, 0.5f, 1f)
        assertEquals(true, rect[3] >= 0f && rect[3] + rect[1] <= 1f)
    }
}
