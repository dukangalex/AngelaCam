package com.dukangalex.angelacam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveExposurePolicyTest {
    @Test
    fun sceneEv100AddsBackCurrentCompensationToAvoidFeedback() {
        // f/2, 1/100s, ISO 100 is EV100 ~= 8.64 before compensation.
        val neutral = AdaptiveExposurePolicy.sceneEv100(2.0, 10_000_000, 100, 0.0)
        // A +1 EV compensation doubles exposure time; restoring the compensation recovers scene EV.
        val withPositiveCompensation = AdaptiveExposurePolicy.sceneEv100(2.0, 20_000_000, 100, 1.0)

        assertEquals(8.643856, neutral!!, 0.00001)
        assertEquals(neutral, withPositiveCompensation!!, 0.00001)
    }

    @Test
    fun invalidSensorMetadataDoesNotProduceAnExposureEstimate() {
        assertNull(AdaptiveExposurePolicy.sceneEv100(0.0, 10_000_000, 100, 0.0))
        assertNull(AdaptiveExposurePolicy.sceneEv100(2.0, 0, 100, 0.0))
        assertNull(AdaptiveExposurePolicy.sceneEv100(2.0, 10_000_000, 0, 0.0))
    }

    @Test
    fun nightAndIndoorScenesRemainAtNeutralCompensation() {
        assertEquals(0.0, AdaptiveExposurePolicy.targetCompensationEv(0.3), 0.0)
        assertEquals(0.0, AdaptiveExposurePolicy.targetCompensationEv(6.0), 0.0)
        assertEquals(0.0, AdaptiveExposurePolicy.targetCompensationEv(8.0), 0.0)
    }

    @Test
    fun brightSceneProtectionIsGradualAndCapped() {
        assertEquals(-0.25, AdaptiveExposurePolicy.targetCompensationEv(10.0), 0.000001)
        assertEquals(-0.5, AdaptiveExposurePolicy.targetCompensationEv(14.0), 0.000001)
        assertEquals(0.0, AdaptiveExposurePolicy.targetCompensationEv(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun targetIndexRespectsCameraRangeAndStep() {
        assertEquals(-1, AdaptiveExposurePolicy.targetCompensationIndex(10.0, 0.25, -4, 4))
        assertEquals(-1, AdaptiveExposurePolicy.targetCompensationIndex(14.0, 0.25, -1, 4))
        assertEquals(0, AdaptiveExposurePolicy.targetCompensationIndex(14.0, 0.0, -4, 4))
    }
}
