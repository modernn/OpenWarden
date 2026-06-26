package com.openwarden.child

/**
 * Pure admission decision for a [SignedHeartbeat] (ADR-024 D4). No I/O.
 *
 * Order mirrors [PolicyAdmission.decide]: version → JC1 integer bound → audience → signature →
 * monotonic replay floor. Every non-accept is fail-closed — the caller MUST NOT reset the
 * contact clock or mutate any state on a [Outcome.Reject].
 */
object HeartbeatAdmission {
    sealed interface Outcome {
        object Accept : Outcome

        data class Reject(
            val reason: String,
        ) : Outcome
    }

    fun decide(
        hb: SignedHeartbeat,
        myChildDeviceId: String,
        pinnedParentPubkey: ByteArray?,
        heartbeatFloor: Long?,
    ): Outcome {
        if (hb.v != 1) return Outcome.Reject("unsupported heartbeat version ${hb.v}")

        // JC1 integer bound, BEFORE signature (consistent with ADR-017 verify step 3).
        if (!Canonical.isJcsSafe(hb.issued_at)) {
            return Outcome.Reject("issued_at ${hb.issued_at} outside JCS-safe range (ADR-017 JC1)")
        }

        // Audience binding, BEFORE signature: a heartbeat for child A must not refresh child B.
        if (hb.child_device_id.isEmpty() || hb.child_device_id != myChildDeviceId) {
            return Outcome.Reject("child_device_id '${hb.child_device_id}' != my id (audience mismatch)")
        }

        // A heartbeat is only meaningful for a provisioned child with a pinned parent key; without
        // one there is nothing to verify against ⇒ fail-closed reject (never resets the ratchet).
        if (pinnedParentPubkey == null) {
            return Outcome.Reject("no pinned parent key — heartbeat not admissible pre-provision")
        }
        if (!HeartbeatVerifier.verify(hb, pinnedParentPubkey)) {
            return Outcome.Reject("Ed25519 signature verification failed (SIG_FAIL, fail-closed)")
        }

        // Monotonic replay floor (ADR-024 D4.5): a captured heartbeat replayed must not reset the
        // ratchet. issued_at must strictly exceed the highest already admitted.
        if (heartbeatFloor != null && hb.issued_at <= heartbeatFloor) {
            return Outcome.Reject("replayed heartbeat: issued_at ${hb.issued_at} <= floor $heartbeatFloor")
        }

        return Outcome.Accept
    }
}
