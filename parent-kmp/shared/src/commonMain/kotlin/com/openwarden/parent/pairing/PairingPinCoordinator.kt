package com.openwarden.parent.pairing

/**
 * Slice (e) of the parent pairing flow (ADR-025 D5e / §7.5, ADR-039): the production caller that
 * **drives [PairingSasStage.confirm]** — the coordinator ADR-038 D4a disclosed was missing — and, on a
 * human Match, commits the pairing **trust anchor** by pinning `(child_ed25519_pub, child_x25519_pub)`.
 *
 * [confirmAndPin] is the one entry point the parent UI / transport will call once the §7.4 emojis are
 * on screen and the human has tapped:
 *  - **Match** → pin both keys atomically (write-once) then burn the single-use session ([PinOutcome.Pinned]).
 *  - **Mismatch** → no pin; `confirm()` already burned the nonce (ADR-036 D4) ([PinOutcome.Aborted]).
 *  - **Stale** → the challenge's attempt is no longer live; no pin, nothing burned ([PinOutcome.Stale]).
 *  - already paired → refuse, never overwrite; the spent attempt is burned ([PinOutcome.AlreadyPaired]).
 *
 * Pure `commonMain` over three seams ([PairingSasStage], [PinnedChildStore], [PairingSessionConsumer])
 * so the whole Match → pin → burn lifecycle, the write-once refusal, and the half-pin impossibility are
 * host-deterministic (`commonTest`). Fail-closed: a pin happens **only** after attestation (c) Accepted
 * and SAS Match (d); every other path pins nothing.
 *
 * Threading (ADR-039 D5a, ADR-036 D5): not thread-safe. The Android coordinator serializes
 * [confirmAndPin] and every session/store touch under the same `sessionLock` as the endpoint; host
 * tests drive it single-threaded. The Compose UI that surfaces the emojis + captures the tap and the
 * transport wiring that reaches this entry point are the disclosed D5a residual — future work.
 */
class PairingPinCoordinator(
    private val stage: PairingSasStage,
    private val store: PinnedChildStore,
    private val sessions: PairingSessionConsumer,
) {
    /**
     * Record the human's §7.4 verdict for [challenge] and, on Match, pin the child. See [PinOutcome].
     * Delegates the live-session / stale check to [PairingSasStage.confirm] (ADR-038 session-bound
     * confirm), so a stale tap can never pin or burn a different attempt.
     */
    fun confirmAndPin(
        challenge: SasChallenge,
        matched: Boolean,
    ): PinOutcome =
        when (val outcome = stage.confirm(challenge, matched)) {
            is SasOutcome.Match -> pinMatched(outcome)

            // confirm() already burned the single-use nonce on Mismatch (ADR-036 D4).
            SasOutcome.Mismatch -> PinOutcome.Aborted

            // The challenge's attempt is no longer live: nothing to pin or burn (ADR-038 session-bound confirm).
            SasOutcome.Stale -> PinOutcome.Stale
        }

    private fun pinMatched(match: SasOutcome.Match): PinOutcome {
        // Write-once (ADR-039 D3): a child is already pinned ⇒ refuse, never overwrite. Re-pair/rotation
        // is recovery-gated (§7.5/D8), not a fresh-pairing overwrite. Burn the now-spent attempt either
        // way (single-use). The store's own pin() is the hard-floor backstop for this same rule.
        if (store.pinnedChild() != null) {
            sessions.consume()
            return PinOutcome.AlreadyPaired
        }

        // Pin BOTH keys atomically (ADR-039 D1/D2). A write failure throws out of pin() — we then do NOT
        // consume, so nothing is pinned and the session resolves unused: fail-closed → unpaired, never a
        // half-pin or a burned-without-pin attempt.
        store.pin(PinnedChild(match.childEd25519Pub, match.childX25519Pub))

        // Burn the single-use session only AFTER a durable pin (ADR-039 D4).
        sessions.consume()
        return PinOutcome.Pinned(match.childEd25519Pub, match.childX25519Pub)
    }
}

/** Outcome of [PairingPinCoordinator.confirmAndPin] (ADR-039 D5). */
sealed interface PinOutcome {
    /**
     * The human confirmed Match and the child was pinned (ADR-025 D5e). [childEd25519Pub] /
     * [childX25519Pub] are the now-pinned keys (defensive copies). The session has been burned.
     */
    class Pinned(
        childEd25519Pub: ByteArray,
        childX25519Pub: ByteArray,
    ) : PinOutcome {
        private val ed = childEd25519Pub.copyOf()
        private val x = childX25519Pub.copyOf()

        val childEd25519Pub: ByteArray get() = ed.copyOf()
        val childX25519Pub: ByteArray get() = x.copyOf()
    }

    /** SAS mismatch — nothing pinned, MITM warning surfaced upstream, the single-use nonce already burned. */
    data object Aborted : PinOutcome

    /** The challenge's originating attempt is no longer live — nothing pinned, nothing burned (ADR-038). */
    data object Stale : PinOutcome

    /**
     * A child is already pinned — refused without overwrite (ADR-039 D3). Replacing a pinned child is a
     * recovery-gated rotation (§7.5/D8), not offered here. The spent attempt's session has been burned.
     */
    data object AlreadyPaired : PinOutcome
}
