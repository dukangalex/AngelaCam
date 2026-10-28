package com.dukangalex.angelacam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UpdateChecker.isNewer 是纯函数，先给它一张回归网（审计 A-6 的起步） */
class UpdateCheckerTest {

    @Test
    fun newerMajor() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.6.0"))
    }

    @Test
    fun newerMinorAndPatch() {
        assertTrue(UpdateChecker.isNewer("1.7.0", "1.6.0"))
        assertTrue(UpdateChecker.isNewer("1.6.1", "1.6.0"))
    }

    @Test
    fun sameAndOlder() {
        assertFalse(UpdateChecker.isNewer("1.6.0", "1.6.0"))
        assertFalse(UpdateChecker.isNewer("1.5.9", "1.6.0"))
    }

    @Test
    fun differentSegmentCounts() {
        assertTrue(UpdateChecker.isNewer("1.6.1", "1.6"))
        assertFalse(UpdateChecker.isNewer("1.6", "1.6.1"))
    }

    @Test
    fun nonNumericSegmentsTreatedAsZero() {
        assertFalse(UpdateChecker.isNewer("1.6.x", "1.6.0"))
        assertTrue(UpdateChecker.isNewer("1.7.beta", "1.6.9"))
    }
}
