package com.dukangalex.angelacam

/** Pure geometry used by the gallery zoom gesture and its regression tests. */
internal object ImageZoomMath {
    /** Keeps the pixel under [focus] in place while the image is scaled. */
    fun offsetAfterZoom(baseOrigin: Float, offset: Float, focus: Float, factor: Float): Float =
        (baseOrigin + offset - focus) * factor + focus - baseOrigin

    /** Keeps a zoomed image covering the viewport; fit-sized content remains centered. */
    fun clampOffset(
        offset: Float,
        viewportSize: Float,
        fittedImageSize: Float,
        baseOrigin: Float,
        scale: Float
    ): Float {
        val scaledSize = fittedImageSize * scale
        if (scaledSize <= viewportSize) return 0f
        val minOffset = viewportSize - scaledSize - baseOrigin
        val maxOffset = -baseOrigin
        return offset.coerceIn(minOffset, maxOffset)
    }
}
