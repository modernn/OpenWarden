package com.openwarden.child

import android.content.Context
import android.os.SystemClock

/**
 * Stateful no-contact ratchet clock (ADR-024). Composes the durable contact markers
 * ([ContactStore]) with the real device clocks ([Clock]) and the pure [Ratchet] math to answer
 * "what tier are we in?" and "reset the silence timer on authenticated contact."
 *
 * Both the store and the clock are injected as seams so the whole thing is unit-testable with
 * fakes (the issue's clock-driven ratchet test + sync-resume reset test).
 */
class ContactClock(
    private val store: ContactStore,
    private val clock: Clock = SystemClockSource,
) {
    /** Real-clock seam. [wallMs] = wall-clock (user-settable); [elapsedMs] = kernel-monotonic. */
    interface Clock {
        fun wallMs(): Long

        fun elapsedMs(): Long
    }

    /**
     * The current ratchet tier. Advances the persisted wall high-water as a side effect so a later
     * backward clock roll is detectable (ADR-024 D2).
     *
     * **Fail-closed on ANY error (ADR-024 D2/D5):** a clock the watchdog cannot read, or a marker it
     * cannot persist (`advanceWallHighWater` throws on a failed/divergent commit), is itself an
     * anomaly ⇒ [Ratchet.Tier.STRICT]. We must NOT let the exception propagate: in the watchdog this
     * runs inside a `runCatching` seam, so a throw would be swallowed and the *prior* (possibly
     * looser) tier's enforcement would stay live until a later tick — exactly the fail-OPEN window
     * D5 forbids. Returning STRICT makes the seam apply the hard floor (deny-all + default DNS) now.
     */
    fun currentTier(): Ratchet.Tier =
        try {
            val nowWall = clock.wallMs()
            val nowElapsed = clock.elapsedMs()
            store.advanceWallHighWater(nowWall)
            val markers =
                Ratchet.Markers(
                    lastContactWallMs = store.lastContactWallMs(),
                    lastContactElapsedMs = store.lastContactElapsedMs(),
                    wallHighWaterMs = store.wallHighWaterMs(),
                )
            Ratchet.tierFor(Ratchet.silenceMs(markers, store.isProvisioned(), nowWall, nowElapsed))
        } catch (e: Exception) {
            Ratchet.Tier.STRICT
        }

    /** Record an authenticated parent contact NOW — resets the silence timer (ADR-024 D3). */
    fun recordContact() {
        store.recordContact(clock.wallMs(), clock.elapsedMs())
    }

    /**
     * Verify + admit a [SignedHeartbeat] (ADR-024 D4). On a valid, fresh, audience-matched
     * heartbeat: advance the replay floor FIRST (so a replay can never slip in), then record
     * contact. Returns true iff admitted. Fail-closed: any rejection leaves all state untouched.
     */
    fun admitHeartbeat(
        hb: SignedHeartbeat,
        pinnedParentPubkey: ByteArray?,
    ): Boolean =
        when (HeartbeatAdmission.decide(hb, store.childDeviceId(), pinnedParentPubkey, store.heartbeatFloor())) {
            is HeartbeatAdmission.Outcome.Accept -> {
                // Capture the clock pair ONCE so the contact marker and the freshness anchor agree.
                val nowWall = clock.wallMs()
                val nowElapsed = clock.elapsedMs()
                // Atomic: advance the replay floor AND record contact in one durable commit, so a
                // crash can never leave the floor advanced (heartbeat consumed) but the timer unreset,
                // nor the timer reset without consuming the replay floor.
                store.admitHeartbeatContact(hb.issued_at, nowWall, nowElapsed)
                // ADR-041 D6: a signed heartbeat re-establishes the §5.1 freshness anchor (issued_at is
                // a signed parent time). Best-effort + monotonic-on-write; a heartbeat carries no
                // not_after, so the watermark is untouched. A failed re-anchor only leaves the clock
                // staler (more restriction), never looser, so it must not fail the durable admission.
                runCatching { store.advanceFreshnessAnchor(hb.issued_at, nowElapsed, null) }
                true
            }

            is HeartbeatAdmission.Outcome.Reject -> {
                false
            }
        }

    object SystemClockSource : Clock {
        override fun wallMs(): Long = System.currentTimeMillis()

        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    }

    companion object {
        /** Wire to the real device clocks + the durable [ReplayFloorStore]-backed contact store. */
        fun forContext(context: Context): ContactClock = ContactClock(store = ReplayFloorStore(context))
    }
}

/**
 * Durable contact-marker + heartbeat-replay-floor persistence (ADR-024 D6). [ReplayFloorStore]
 * implements it against the same TEE/StrongBox-bound EncryptedSharedPreferences as the replay
 * floor. Every write is fail-closed (commit()-checked + readback), matching the floor's contract.
 */
interface ContactStore {
    fun childDeviceId(): String

    fun isProvisioned(): Boolean

    fun lastContactWallMs(): Long?

    fun lastContactElapsedMs(): Long?

    fun wallHighWaterMs(): Long?

    fun recordContact(
        wallMs: Long,
        elapsedMs: Long,
    )

    fun advanceWallHighWater(wallMs: Long)

    fun heartbeatFloor(): Long?

    /**
     * Atomically (one durable commit) advance the heartbeat replay floor to [issuedAt] AND record
     * an authenticated contact at ([wallMs], [elapsedMs]). Fail-closed: throws on a failed/divergent
     * write, leaving prior state intact (the heartbeat is then treated as not-admitted).
     */
    fun admitHeartbeatContact(
        issuedAt: Long,
        wallMs: Long,
        elapsedMs: Long,
    )

    /**
     * Re-establish the ADR-041 §5.1 freshness anchor from a signed parent time (heartbeat
     * `issued_at`), paired with [nowElapsedMs]; [notAfterMs] is null for heartbeats (no window).
     * Monotonic-on-write. Defaulted to a no-op so non-freshness fakes need not implement it;
     * [ReplayFloorStore] satisfies it via its [PolicyAdmission.FloorState] implementation.
     */
    fun advanceFreshnessAnchor(
        parentIssuedAtMs: Long,
        nowElapsedMs: Long,
        notAfterMs: Long?,
    ) {}
}
