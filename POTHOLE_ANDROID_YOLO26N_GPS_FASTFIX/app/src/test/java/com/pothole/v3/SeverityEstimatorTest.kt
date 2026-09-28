package com.pothole.v3

import org.junit.Assert.assertTrue
import org.junit.Test

class SeverityEstimatorTest {
    @Test
    fun perspectiveCompensationReducesNearInflation() {
        SeverityEstimator.configure(RoadCalibration(horizonFraction = 0.32f, phoneHeightCm = 120))
        val far = SeverityEstimator.measure(0.008f, 0.45f)
        val near = SeverityEstimator.measure(0.016f, 0.88f)
        // Aunque el área observada cercana duplica a la lejana, la compensación reduce el salto.
        assertTrue(near.compensatedAreaRatio < near.rawAreaRatio)
        assertTrue(far.perspectiveFactor <= near.perspectiveFactor)
    }
}
