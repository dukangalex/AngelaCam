package com.dukangalex.angelacam

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Conservative adaptive exposure bias for CameraX's existing automatic exposure.
 * Low-light scenes stay at neutral compensation so the camera's native AE can
 * preserve its normal night behavior; only bright scenes receive modest negative
 * compensation to protect highlights.
 */
internal object AdaptiveExposurePolicy {
    private const val LN_2 = 0.6931471805599453
    private const val NEUTRAL_THROUGH_EV100 = 8.0
    private const val FULL_HIGHLIGHT_PROTECTION_EV100 = 12.0
    private const val MAX_BRIGHT_SCENE_COMPENSATION_EV = -0.5

    /**
     * Estimate scene EV100 from Camera2 sensor metadata and undo the currently
     * applied EV compensation, preventing the policy from feeding back on itself.
     */
    fun sceneEv100(
        aperture: Double,
        exposureTimeNanos: Long,
        sensitivityIso: Int,
        appliedCompensationEv: Double
    ): Double? {
        if (!aperture.isFinite() || aperture <= 0.0 || exposureTimeNanos <= 0L ||
            sensitivityIso <= 0 || !appliedCompensationEv.isFinite()
        ) return null

        val exposureSeconds = exposureTimeNanos / 1_000_000_000.0
        val baseEv100 = log2((aperture * aperture) / exposureSeconds) -
            log2(sensitivityIso / 100.0)
        val sceneEv100 = baseEv100 + appliedCompensationEv
        return sceneEv100.takeIf { it.isFinite() }
    }

    /** Returns 0 EV through EV100 8, then eases to at most -0.5 EV by EV100 12. */
    fun targetCompensationEv(sceneEv100: Double): Double {
        if (!sceneEv100.isFinite() || sceneEv100 <= NEUTRAL_THROUGH_EV100) return 0.0
        val progress = ((sceneEv100 - NEUTRAL_THROUGH_EV100) /
            (FULL_HIGHLIGHT_PROTECTION_EV100 - NEUTRAL_THROUGH_EV100)).coerceIn(0.0, 1.0)
        return MAX_BRIGHT_SCENE_COMPENSATION_EV * progress
    }

    fun targetCompensationIndex(
        sceneEv100: Double,
        stepEv: Double,
        minIndex: Int,
        maxIndex: Int
    ): Int {
        if (minIndex > maxIndex || !stepEv.isFinite() || stepEv <= 0.0) return 0
        return (targetCompensationEv(sceneEv100) / stepEv)
            .roundToInt()
            .coerceIn(minIndex, maxIndex)
    }

    private fun log2(value: Double): Double = ln(value) / LN_2
}
