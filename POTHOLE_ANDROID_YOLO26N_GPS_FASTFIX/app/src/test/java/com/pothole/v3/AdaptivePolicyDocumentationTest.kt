package com.pothole.v3

import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePolicyDocumentationTest {
    @Test
    fun performanceModesHaveDistinctLabels() {
        assertTrue(PerformanceMode.values().map { it.label }.distinct().size == PerformanceMode.values().size)
    }
}
