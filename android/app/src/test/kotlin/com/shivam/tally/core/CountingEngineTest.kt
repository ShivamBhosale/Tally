package com.shivam.tally.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CountingEngineTest {
    private class Rec : CountingEngine.Listener {
        val counted = mutableListOf<Pair<TrackedApp, String?>>()
        val ended = mutableListOf<Triple<TrackedApp, Long, Boolean>>()
        override fun onViewCounted(view: CountingEngine.View, atMs: Long) { counted += view.app to view.key }
        override fun onViewEnded(view: CountingEngine.View, dwellMs: Long, counted: Boolean) { ended += Triple(view.app, dwellMs, counted) }
    }

    private lateinit var rec: Rec
    private lateinit var e: CountingEngine
    private val ig = TrackedApp.INSTAGRAM
    private val yt = TrackedApp.YOUTUBE

    @Before fun setUp() { rec = Rec(); e = CountingEngine(rec) }

    @Test fun countsAfterMinDwell() {
        e.onVideoShown(ig, "a", 0)
        e.tick(1_999); assertEquals(0, rec.counted.size)
        e.tick(2_000); assertEquals(1, rec.counted.size)
    }

    @Test fun doesNotCountBelowDwellWhenSwipingAway() {
        e.onVideoShown(ig, "a", 0)
        e.onVideoShown(ig, "b", 1_000)
        e.onPlayerExited(1_500)
        assertEquals(0, rec.counted.size)
        assertEquals(listOf(false, false), rec.ended.map { it.third })
    }

    @Test fun countsOnlyOnceWhileSameKeyStaysVisible() {
        e.onVideoShown(ig, "a", 0)
        e.onVideoShown(ig, "a", 500)
        e.tick(2_000)
        e.onVideoShown(ig, "a", 2_500)
        e.tick(9_000)
        assertEquals(1, rec.counted.size)
    }

    @Test fun lateTickStillCountsBeforeSwitching() {
        e.onVideoShown(ig, "a", 0)
        e.onVideoShown(ig, "b", 3_000) // no tick in between: engine ticks itself
        assertEquals(listOf<Pair<TrackedApp, String?>>(ig to "a"), rec.counted)
    }

    @Test fun backSwipeToRecentlyCountedKeyIsNotRecounted() {
        e.onVideoShown(ig, "a", 0); e.tick(2_000)
        e.onVideoShown(ig, "b", 3_000); e.tick(5_000)
        e.onVideoShown(ig, "a", 6_000); e.tick(9_000)
        assertEquals(listOf("a", "b"), rec.counted.map { it.second })
    }

    @Test fun sameKeyCountsAgainAfterDedupeWindow() {
        e.onVideoShown(ig, "a", 0); e.tick(2_000)
        e.onPlayerExited(3_000)
        e.onVideoShown(ig, "a", 40_000); e.tick(42_000)
        assertEquals(2, rec.counted.size)
    }

    @Test fun suppressedDuplicateReportsNotCountedOnEnd() {
        e.onVideoShown(ig, "a", 0); e.tick(2_000)
        e.onVideoShown(ig, "a", 5_000) // same key, no-op
        e.onVideoShown(ig, "b", 6_000)
        e.onVideoShown(ig, "a", 7_000) // suppressed duplicate
        assertNull(e.nextDeadlineMs())
        e.onPlayerExited(10_000)
        assertFalse(rec.ended.last().third)
    }

    @Test fun anonymousPageChangesAreDebounced() {
        e.onVideoShown(ig, null, 0)
        e.onVideoShown(ig, null, 100)
        e.onVideoShown(ig, null, 300)
        e.tick(2_000)
        assertEquals(1, rec.counted.size)
    }

    @Test fun anonymousPageChangeAfterDebounceIsNewVideo() {
        e.onVideoShown(ig, null, 0); e.tick(2_000)
        e.onVideoShown(ig, null, 2_400); e.tick(4_400)
        assertEquals(2, rec.counted.size)
    }

    @Test fun identityArrivingJustAfterPageChangeIsSameVideo() {
        e.onVideoShown(ig, null, 0)
        e.onVideoShown(ig, "a", 200)
        e.tick(2_000)
        assertEquals(listOf<Pair<TrackedApp, String?>>(ig to "a"), rec.counted)
        assertEquals(1, rec.counted.size)
    }

    @Test fun leavingPlayerBeforeDwellDoesNotCount() {
        e.onVideoShown(yt, "s", 0)
        e.onPlayerExited(1_500)
        e.tick(5_000)
        assertTrue(rec.counted.isEmpty())
        assertNull(e.activeApp)
    }

    @Test fun screenOffEndsViewAndCountsIfDwellMet() {
        e.onVideoShown(yt, "s", 0)
        e.onScreenOff(2_500)
        assertEquals(1, rec.counted.size)
        assertNull(e.activeApp)
        e.tick(10_000)
        assertEquals(1, rec.counted.size)
    }

    @Test fun switchingAppEndsPreviousView() {
        e.onVideoShown(ig, "a", 0)
        e.onVideoShown(yt, "s", 1_000)
        assertEquals(yt, e.activeApp)
        e.tick(3_000)
        assertEquals(listOf<Pair<TrackedApp, String?>>(yt to "s"), rec.counted)
    }

    @Test fun disabledAppIsNeverCounted() {
        e.enabledApps = setOf(yt)
        e.onVideoShown(ig, "a", 0); e.tick(5_000)
        assertTrue(rec.counted.isEmpty())
        assertNull(e.activeApp)
    }

    @Test fun dwellIsClampedAndConfigurable() {
        e.minDwellMs = 100; assertEquals(1_000, e.minDwellMs)
        e.minDwellMs = 60_000; assertEquals(10_000, e.minDwellMs)
        e.minDwellMs = 5_000
        e.onVideoShown(ig, "a", 0)
        assertEquals(5_000L, e.nextDeadlineMs())
        e.tick(4_999); assertTrue(rec.counted.isEmpty())
        e.tick(5_000); assertEquals(1, rec.counted.size)
    }

    @Test fun endedReportsTotalDwell() {
        e.onVideoShown(ig, "a", 0); e.tick(2_000)
        e.onPlayerExited(7_000)
        assertEquals(Triple(ig, 7_000L, true), rec.ended.single())
    }

    private val S = CountingEngine.SEP

    @Test fun unreadablePartMidViewDoesNotSplitOrDoubleCount() {
        e.onVideoShown(yt, "@chan${S}0 minutes 45 seconds", 0)
        e.tick(2_000)
        e.onVideoShown(yt, "@chan", 3_000)                       // duration momentarily unreadable
        e.onVideoShown(yt, "chan${S}0 minutes 45 seconds", 4_000) // and back, without the '@'
        e.tick(9_000)
        assertEquals(1, rec.counted.size)
        assertEquals(0, rec.ended.size)                          // still the same single view
    }

    @Test fun differentChannelWithSameMissingPartIsStillDifferent() {
        e.onVideoShown(yt, "@a", 0); e.tick(2_000)
        e.onVideoShown(yt, "@b", 3_000); e.tick(5_000)
        assertEquals(2, rec.counted.size)
    }

    @Test fun missingChannelSlotDoesNotShiftPositions() {
        e.onVideoShown(yt, "@a${S}45s", 0); e.tick(2_000)
        e.onVideoShown(yt, "${S}28s", 3_000)       // channel unreadable, duration differs: a different video
        e.tick(5_000)
        e.onVideoShown(yt, "@b${S}28s", 5_500)     // channel resolves: same video as the previous one
        e.tick(9_000)
        assertEquals(listOf("45s", "28s"), rec.counted.map { it.second!!.split(S).last() })
    }

    @Test fun partialKeyOfCurrentVideoDoesNotRestartDwell() {
        e.onVideoShown(yt, "@a${S}45s", 0)
        e.onVideoShown(yt, "@a${S}", 1_500)
        e.tick(2_000)
        assertEquals(1, rec.counted.size)
    }
}
