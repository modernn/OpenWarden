package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-041 §5.1 monotonic-clock estimator (issue #90). Pure JVM — no Android, no Robolectric.
 * monotonic_now = parent_anchor + (elapsedRealtime_now − elapsed_at_anchor), Unusable on a missing
 * anchor or an elapsed regression (reboot).
 */
class FreshnessClockTest {

    private fun anchor(parent: Long?, elapsed: Long?, watermark: Long? = null) =
        FreshnessClock.Anchor(parent, elapsed, watermark)

    @Test
    fun usableEstimateAddsTheElapsedDeltaToTheParentAnchor() {
        // Anchored at parent t=1000 when elapsed was 500; now elapsed is 800 => 300ms have passed.
        val now = FreshnessClock.estimate(anchor(parent = 1000L, elapsed = 500L), nowElapsedMs = 800L)
        assertTrue(now is FreshnessClock.Now.Usable)
        assertEquals(1300L, (now as FreshnessClock.Now.Usable).monotonicNowMs)
    }

    @Test
    fun sameInstantAsAnchorEstimatesTheAnchorTimeExactly() {
        val now = FreshnessClock.estimate(anchor(parent = 1000L, elapsed = 500L), nowElapsedMs = 500L)
        assertEquals(1000L, (now as FreshnessClock.Now.Usable).monotonicNowMs)
    }

    @Test
    fun noParentAnchorIsUnusable() {
        assertTrue(FreshnessClock.estimate(anchor(parent = null, elapsed = 500L), 800L) is FreshnessClock.Now.Unusable)
    }

    @Test
    fun noElapsedAnchorIsUnusable() {
        assertTrue(FreshnessClock.estimate(anchor(parent = 1000L, elapsed = null), 800L) is FreshnessClock.Now.Unusable)
    }

    @Test
    fun elapsedRegressionBelowTheAnchorIsUnusableReboot() {
        // After a reboot elapsedRealtime resets toward 0, below the stored anchor elapsed => the
        // pair is from a prior boot and MUST NOT be extrapolated (would under-count real time).
        val now = FreshnessClock.estimate(anchor(parent = 1000L, elapsed = 5_000L), nowElapsedMs = 10L)
        assertTrue(now is FreshnessClock.Now.Unusable, "elapsed below the anchor (reboot) must be Unusable")
    }

    @Test
    fun emptyAnchorIsUnusable() {
        assertTrue(FreshnessClock.estimate(anchor(null, null, null), 0L) is FreshnessClock.Now.Unusable)
    }

    // ---- nextAnchor: monotonic-on-write (ADR-041 D4) -----------------------

    @Test
    fun firstAnchorSeedsBothPairAndWatermark() {
        val w = FreshnessClock.nextAnchor(anchor(null, null, null), candidateParentMs = 1000L, candidateElapsedMs = 50L, candidateNotAfterMs = 2000L)
        assertEquals(FreshnessClock.AnchorWrite(1000L, 50L, 2000L), w)
    }

    @Test
    fun newerParentTimeAdvancesThePair() {
        val w = FreshnessClock.nextAnchor(anchor(1000L, 50L, 2000L), candidateParentMs = 1500L, candidateElapsedMs = 80L, candidateNotAfterMs = 2500L)
        assertEquals(FreshnessClock.AnchorWrite(1500L, 80L, 2500L), w)
    }

    @Test
    fun olderParentTimeNeverWindsTheAnchorBack() {
        // A candidate older than the stored anchor must NOT lower the §5.1 clock (anti-rollback).
        val w = FreshnessClock.nextAnchor(anchor(1000L, 50L, 2000L), candidateParentMs = 900L, candidateElapsedMs = 80L, candidateNotAfterMs = 1800L)
        assertEquals(1000L, w.parentMs, "parent time must not decrease")
        assertEquals(50L, w.elapsedMs, "the paired elapsed must not change when the anchor is kept")
        assertEquals(2000L, w.watermarkMs, "watermark only rises (1800 < 2000 ignored)")
    }

    @Test
    fun watermarkOnlyRisesAndHeartbeatNullLeavesItUntouched() {
        // A bundle with a higher not_after raises the watermark.
        assertEquals(3000L, FreshnessClock.nextAnchor(anchor(1000L, 50L, 2000L), 1500L, 80L, 3000L).watermarkMs)
        // A heartbeat (null not_after) re-anchors the pair but leaves the watermark.
        val hb = FreshnessClock.nextAnchor(anchor(1000L, 50L, 2000L), 1500L, 80L, null)
        assertEquals(1500L, hb.parentMs)
        assertEquals(2000L, hb.watermarkMs, "a heartbeat must not touch the not_after watermark")
    }
}
