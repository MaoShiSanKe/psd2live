package io.github.psd2live.core

import kotlin.test.*

class PreviewTrackingResponseTest {
    @Test fun headRespondsQuicklyWhileBodyRetainsAShortSoftLag() {
        val target = 1f to -1f
        val head = PreviewAnimationClock().advanceTracking(0.15f, target)
        assertTrue(head.followX > 0.9f)
        assertTrue(head.bodyX in 0.5f..head.followX)
        val body = PreviewAnimationClock().advanceTracking(0.35f, target)
        assertTrue(body.bodyX > 0.9f)
        assertEquals(body.followX, body.followY)
        assertEquals(body.bodyX, body.bodyY)
    }

    @Test fun equalElapsedTimeGivesTheSameResponseAtDifferentFrameRates() {
        val target = 0.8f to 0.6f
        val expected = PreviewAnimationClock().advanceTracking(0.5f, target)
        for (fps in listOf(30, 60, 144)) {
            var clock = PreviewAnimationClock()
            repeat(fps / 2) { clock = clock.advanceTracking(1f / fps, target) }
            assertEquals(expected.followX, clock.followX, 1e-5f)
            assertEquals(expected.followY, clock.followY, 1e-5f)
            assertEquals(expected.bodyX, clock.bodyX, 1e-5f)
            assertEquals(expected.bodyY, clock.bodyY, 1e-5f)
        }
    }

    @Test fun zeroDeltaAndReversalsStayContinuousAndBounded() {
        var clock = PreviewAnimationClock().advanceTracking(0.2f, 1f to -1f)
        assertEquals(clock, clock.advanceTracking(0f, -1f to 1f))
        repeat(120) {
            clock = clock.advanceTracking(1f / 144f, -1f to 1f)
            assertTrue(clock.followX in -1f..1f)
            assertTrue(clock.bodyX in -1f..1f)
        }
        assertTrue(clock.followX < -0.99f)
        assertTrue(clock.bodyX < -0.99f)
    }
}
