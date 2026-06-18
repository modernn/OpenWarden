package com.openwarden.child

/**
 * Pure admission decision for a [SignedCommand] (ADR-030). No I/O.
 *
 * Order mirrors [HeartbeatAdmission.decide] — version → JC1 integer bound → type → audience →
 * signature → monotonic replay floor — and then adds the command-specific **freshness window**.
 * Every non-accept is fail-closed: the caller MUST advance no floor and change no lock state on a
 * [Outcome.Reject].
 *
 * The lock and unlock verbs share ONE monotonic [commandFloor] so that, once any command has been
 * admitted, every earlier-stamped command (of either verb) is dead. The freshness window exists
 * because the floor alone cannot bound a command captured on the wire but never delivered (ADR-030
 * D4): such a command keeps `issued_at > floor` indefinitely, so without the window a sniffed
 * `unlock` would stay replayable until some later command happened to advance the floor past it.
 */
object CommandAdmission {

    /** A captured command is admissible only within this wall-clock window of the child (ADR-030 D4). */
    const val FRESHNESS_MS: Long = 5 * 60 * 1000L // 5 minutes

    sealed interface Outcome {
        data class Accept(val type: String) : Outcome
        data class Reject(val reason: String) : Outcome
    }

    /**
     * @param expectedType the verb the receiving endpoint binds to ([SignedCommand.TYPE_LOCK] for
     *   `/lock`, [SignedCommand.TYPE_UNLOCK] for `/unlock`). Checked against the SIGNED `type` BEFORE
     *   any store write, so a valid lock command POSTed to `/unlock` is rejected without consuming
     *   the replay floor or touching the lock flag.
     * @param nowMs the child's current wall-clock ms (injected as a seam for deterministic tests).
     *   Trustworthy because `DISALLOW_CONFIG_DATE_TIME` is in the Day-One baseline (ADR-030 D4).
     */
    fun decide(
        cmd: SignedCommand,
        expectedType: String,
        myChildDeviceId: String,
        pinnedParentPubkey: ByteArray?,
        commandFloor: Long?,
        nowMs: Long,
    ): Outcome {
        if (cmd.v != 1) return Outcome.Reject("unsupported command version ${cmd.v}")

        // JC1 integer bound, BEFORE signature (consistent with ADR-017 verify step 3 / the heartbeat path).
        if (!Canonical.isJcsSafe(cmd.issued_at)) {
            return Outcome.Reject("issued_at ${cmd.issued_at} outside JCS-safe range (ADR-017 JC1)")
        }

        // Endpoint↔signed-type binding, BEFORE signature: the signed `type` must be the verb the
        // endpoint expects (and therefore a known verb, since expectedType is always lock/unlock).
        if (cmd.type != expectedType) {
            return Outcome.Reject("command type '${cmd.type}' != expected '$expectedType'")
        }

        // Audience binding, BEFORE signature: a command for child A must not act on child B.
        if (cmd.child_device_id.isEmpty() || cmd.child_device_id != myChildDeviceId) {
            return Outcome.Reject("child_device_id '${cmd.child_device_id}' != my id (audience mismatch)")
        }

        // A command is only meaningful for a provisioned child with a pinned parent key; without one
        // there is nothing to verify against ⇒ fail-closed reject (never changes lock state).
        if (pinnedParentPubkey == null) {
            return Outcome.Reject("no pinned parent key — command not admissible pre-provision")
        }
        if (!CommandVerifier.verify(cmd, pinnedParentPubkey)) {
            return Outcome.Reject("Ed25519 signature verification failed (SIG_FAIL, fail-closed)")
        }

        // Monotonic replay floor (shared lock/unlock): a captured command, once any later command has
        // been admitted, is below the floor and dies here.
        if (commandFloor != null && cmd.issued_at <= commandFloor) {
            return Outcome.Reject("replayed command: issued_at ${cmd.issued_at} <= floor $commandFloor")
        }

        // Bound nowMs to the JCS-safe range BEFORE the freshness subtraction. issued_at is already
        // bounded (above); guarding nowMs too keeps both operands in 0..2^53-1, so neither difference
        // can overflow Long and wrap a wildly-out-of-window command back inside it (fail-OPEN). nowMs
        // is a caller-injected seam, so it must be validated, not trusted. Fail-closed: an unreadable/
        // absurd clock rejects the command rather than admitting it.
        if (!Canonical.isJcsSafe(nowMs)) {
            return Outcome.Reject("nowMs $nowMs outside JCS-safe range — clock unusable, fail-closed")
        }

        // Freshness window (ADR-030 D4): bounds a captured-but-undelivered command. Fail-closed both
        // ways — a stale OR a future-skewed command is rejected (the parent retries) rather than admitted.
        if (nowMs - cmd.issued_at > FRESHNESS_MS) {
            return Outcome.Reject("stale command: issued_at ${cmd.issued_at} older than ${FRESHNESS_MS}ms before now $nowMs")
        }
        if (cmd.issued_at - nowMs > FRESHNESS_MS) {
            return Outcome.Reject("future command: issued_at ${cmd.issued_at} more than ${FRESHNESS_MS}ms ahead of now $nowMs")
        }

        return Outcome.Accept(cmd.type)
    }
}
