package com.dukangalex.angelacam

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageZoomMathTest {
    @Test
    fun zoomKeepsPointUnderGestureFocusAnchored() {
        assertEquals(-50f, ImageZoomMath.offsetAfterZoom(0f, 0f, 50f, 2f), 0.001f)
    }

    @Test
    fun panIsClampedToImageEdges() {
        assertEquals(0f, ImageZoomMath.clampOffset(30f, 100f, 100f, 0f, 2f), 0.001f)
        assertEquals(-100f, ImageZoomMath.clampOffset(-180f, 100f, 100f, 0f, 2f), 0.001f)
    }

    @Test
    fun fitSizedContentStaysCentered() {
        assertEquals(0f, ImageZoomMath.clampOffset(24f, 100f, 60f, 20f, 1f), 0.001f)
    }
}
