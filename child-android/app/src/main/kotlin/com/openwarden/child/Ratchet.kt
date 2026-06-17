package com.openwarden.child

/**
 * Pure no-contact ratchet math (ADR-024, implementing ADR-017 §7). No Android, no I/O — the
 * silence calculation and tier mapping are deterministic and JVM-unit-testable.
 *
 * Fail-closed (ADR-024 D2): every ambiguous or anomalous input resolves to MORE silence (a
 * higher/stricter tier), never less. Clock tampering can therefore only ACCELERATE the ratchet,
 * never delay it.
 */
object Ratchet {

    /** Silence ≥ this ⇒ STALE (deny-all launch; bundle still trusted for its DNS resolver). 24h. */
    const val RATCHET_STALE_MS = 24L * 60 * 60 * 1000

    /** Silence ≥ this ⇒ STRICT (distrust the frozen bundle entirely; hard default floor). 48h. */
    const val RATCHET_STRICT_MS = 48L * 60 * 60 * 1000

    /** A silence value that is unconditionally STRICT — every fail-closed/anomaly path returns it. */
    const val STRICT_SILENCE = Long.MAX_VALUE

    enum class Tier { FRESH, STALE, STRICT }

    /** Map a measured silence duration (ms) to a ratchet tier. */
    fun tierFor(silenceMs: Long): Tier = when {
        silenceMs >= RATCHET_STRICT_MS -> Tier.STRICT
        silenceMs >= RATCHET_STALE_MS -> Tier.STALE
        else -> Tier.FRESH
    }

    /** Persisted contact markers needed to compute silence; null = "never recorded". */
    data class Markers(
        val lastContactWallMs: Long?,
        val lastContactElapsedMs: Long?,
        val wallHighWaterMs: Long?,
    )

    /**
     * Compute silence (ms) since the last authenticated parent contact, fail-closed (ADR-024 D2).
     *
     * @param markers persisted contact markers.
     * @param provisioned whether the child is provisioned. A provisioned child with no contact
     *   marker is an anomaly ⇒ STRICT. An un-provisioned child has no parent to be silent yet
     *   (pre-genesis), and the Missing-bundle deny-all floor already covers it ⇒ FRESH (silence 0).
     * @param nowWallMs `System.currentTimeMillis()` — user-settable, not trusted on its own.
     * @param nowElapsedMs `SystemClock.elapsedRealtime()` — kernel-monotonic, not user-settable.
     */
    fun silenceMs(
        markers: Markers,
        provisioned: Boolean,
        nowWallMs: Long,
        nowElapsedMs: Long,
    ): Long {
        val lastWall = markers.lastContactWallMs
        val lastElapsed = markers.lastContactElapsedMs
        if (lastWall == null || lastElapsed == null) {
            // No contact recorded: provisioned ⇒ anomaly ⇒ STRICT; pre-provision ⇒ nothing to ratchet.
            return if (provisioned) STRICT_SILENCE else 0L
        }

        if (nowElapsedMs >= lastElapsed) {
            // Same boot session: elapsedRealtime is monotonic and authoritative. But a forward
            // elapsed with a wall clock pushed BELOW the high-water is wall tampering ⇒ STRICT.
            val hw = markers.wallHighWaterMs
            if (hw != null && nowWallMs < hw) return STRICT_SILENCE
            return nowElapsedMs - lastElapsed
        }

        // elapsedRealtime regressed ⇒ a reboot happened (or elapsed tamper). Fall back to the wall
        // delta, gated by the high-water: any wall reading below it is a backward roll ⇒ STRICT.
        val hw = markers.wallHighWaterMs ?: lastWall
        if (nowWallMs < hw) return STRICT_SILENCE
        val wallDelta = nowWallMs - lastWall
        // Defensive: the guard above (nowWall >= hw >= lastWall) makes wallDelta >= 0 unreachable
        // today. Kept as a belt-and-suspenders fail-closed floor so a future refactor that weakens
        // the high-water invariant degrades to STRICT, not to a negative/garbage silence.
        return if (wallDelta < 0) STRICT_SILENCE else wallDelta
    }
}
