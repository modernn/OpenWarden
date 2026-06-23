package com.openwarden.child

/**
 * Pure PROTOCOL §5.1 monotonic-clock estimator for the policy-bundle freshness window (ADR-041).
 *
 * The freshness deadline (`not_before`/`not_after`) is a parent wall-clock time, but the child's own
 * wall clock is kid-settable (`DISALLOW_CONFIG_DATE_TIME` is NOT a day-one restriction), so it can
 * never be the time source. Instead the child estimates the parent's "now" from a SIGNED parent time
 * anchored to the kernel-monotonic boot clock:
 *
 *     monotonic_now_ms = parent_anchor + (elapsedRealtime_now − elapsed_at_anchor)
 *
 * where `parent_anchor` is the `issued_at` of the last *applied* bundle or *admitted* heartbeat (a
 * value the parent signed), captured together with `SystemClock.elapsedRealtime()` at that instant.
 *
 * `elapsedRealtime()` is monotonic and survives wall-clock edits, but it **resets to 0 on reboot**.
 * So an anchor whose stored `elapsed_at` is GREATER than the current reading is from a prior boot and
 * is **unusable** — we never extrapolate across a reboot (that would under-count real elapsed time
 * and could revive an expired bundle). No wall clock is ever consulted here.
 *
 * Pure (no Android) → fully host-testable. The stateful anchor persistence lives in
 * [PolicyAdmission.FreshnessAnchorStore] (backed by [ReplayFloorStore]); the device clock seam is
 * [ContactClock.Clock.elapsedMs].
 */
object FreshnessClock {

    /**
     * The persisted freshness anchor: a signed parent time paired with the kernel-monotonic
     * `elapsedRealtime()` captured at the same instant, plus the highest `not_after` ever applied
     * (the monotonic-on-write watermark — ADR-041 D4). Any field `null` ⇒ never seeded.
     */
    data class Anchor(
        val parentAnchorMs: Long?,
        val elapsedAtAnchorMs: Long?,
        val notAfterWatermarkMs: Long?,
    )

    /** The estimate of the parent's current time, or [Unusable] when it cannot be trusted. */
    sealed interface Now {
        /** A trustworthy monotonic estimate of the parent's current time, in ms. */
        data class Usable(val monotonicNowMs: Long) : Now

        /**
         * No anchor seeded yet, or `elapsedRealtime` regressed below the anchor (a reboot, or elapsed
         * tampering). The window cannot be evaluated — callers MUST fail closed (admission re-anchors
         * on the next signed bundle; the watchdog holds the stale baseline meanwhile). ADR-041 D2/D3.
         */
        object Unusable : Now
    }

    /**
     * Estimate the parent's current time from [anchor] and the current kernel-monotonic
     * [nowElapsedMs] (`SystemClock.elapsedRealtime()`). [Now.Unusable] when there is no anchor or
     * [nowElapsedMs] is below the stored elapsed (reboot / regression) — never extrapolate then.
     */
    fun estimate(anchor: Anchor, nowElapsedMs: Long): Now {
        val parentAnchor = anchor.parentAnchorMs ?: return Now.Unusable
        val elapsedAtAnchor = anchor.elapsedAtAnchorMs ?: return Now.Unusable
        if (nowElapsedMs < elapsedAtAnchor) return Now.Unusable // elapsed regressed ⇒ reboot ⇒ unusable
        val now = parentAnchor + (nowElapsedMs - elapsedAtAnchor)
        // Defense-in-depth: JC1 already bounds parentAnchor to ≤ 2^53−1 so this cannot overflow in
        // practice, but a wrapped (negative) estimate would compare as "before not_before" and could
        // mis-defer or, worse, look in-window — fail closed instead. The delta is ≥ 0 (checked above),
        // so a result below the anchor means Long overflow.
        if (now < parentAnchor) return Now.Unusable
        return Now.Usable(now)
    }

    /** The anchor values to persist for a new signed parent time (ADR-041 D4). */
    data class AnchorWrite(val parentMs: Long, val elapsedMs: Long, val watermarkMs: Long?)

    /**
     * Pure ADR-041 D4 **monotonic-on-write** decision: given the [current] stored anchor and a new
     * signed parent time ([candidateParentMs], captured with [candidateElapsedMs]), compute what to
     * persist. The anchor's parent time (and its paired elapsed) **never decrease** — a candidate
     * older than the stored anchor is ignored for the anchor, so a local edit cannot wind the §5.1
     * clock backward to revive an expired bundle (the replay floor already blocks older bundles). The
     * `not_after` watermark rises to `max(current, candidate)` when [candidateNotAfterMs] is non-null
     * (bundles); a heartbeat passes `null` and leaves the watermark untouched. Extracted here so the
     * security-relevant decision is host-testable; [ReplayFloorStore.advanceFreshnessAnchor] applies it.
     */
    fun nextAnchor(
        current: Anchor,
        candidateParentMs: Long,
        candidateElapsedMs: Long,
        candidateNotAfterMs: Long?,
    ): AnchorWrite {
        val curParent = current.parentAnchorMs
        val curElapsed = current.elapsedAtAnchorMs
        val keepCurrent = curParent != null && curElapsed != null && candidateParentMs < curParent
        val parent = if (keepCurrent) curParent!! else candidateParentMs
        val elapsed = if (keepCurrent) curElapsed!! else candidateElapsedMs
        val watermark = when {
            candidateNotAfterMs == null -> current.notAfterWatermarkMs
            current.notAfterWatermarkMs == null -> candidateNotAfterMs
            else -> maxOf(current.notAfterWatermarkMs, candidateNotAfterMs)
        }
        return AnchorWrite(parent, elapsed, watermark)
    }
}
