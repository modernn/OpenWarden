package com.openwarden.proto

/**
 * Parent-anchored, rollback-resistant replay floor — the **pure monotonic
 * decision function** for `policy_seq` admission (ADR-017, issue #5).
 *
 * This is *only* the decision logic, NOT storage. It takes the current floor
 * value and an incoming `policySeq` and returns whether the bundle may advance
 * the floor or must be rejected (caller then drops to the strict baseline). It
 * deliberately holds no state, no I/O, and no crypto: persistence of the
 * watermark (the TEE/StrongBox-bound at-rest floor + the append-only event-log
 * chain mirror, ADR-017 part 1) is a **separate child-side follow-up**, as is
 * signature verification and audience binding. Keeping this layer dependency-free
 * makes it JVM-testable (no libsodium) and keeps the safety-critical comparison
 * in one auditable place.
 *
 * ADR-017 mapping:
 *
 *  - **K1 (rollback ⇒ strict, detection ≠ prevention):** an incoming seq that is
 *    equal-or-lower than the floor is a detected rollback/replay; we
 *    [Decision.RejectStrict] so the caller applies the strict baseline
 *    immediately, never an "accept now, alert later" branch (ADR-017 part 3).
 *
 *  - **K2 (fail-closed genesis):** a `null` floor models an absent / unreadable
 *    persisted counter. ADR-017 part 4 distinguishes a *never-provisioned* child
 *    (genesis TOFU, allowed) from a *provisioned* child whose floor is now
 *    missing/lower (an anomaly ⇒ strict baseline). That distinction requires the
 *    TEE-bound provisioning marker + pinned-key state, which this pure function
 *    intentionally does not see. With only `(floor, seq)` in hand, the
 *    fail-closed reading is the only safe one: a `null` floor is treated as the
 *    anomaly case ⇒ [Decision.RejectStrict]. It is NEVER treated as
 *    "fresh / accept-anything". Genesis TOFU admission lives one layer up, where
 *    the provisioning marker is readable; that layer seeds this floor only after
 *    it has confirmed the never-provisioned state and an incoming `policy_seq ≥
 *    [GENESIS_FIRST_VALID_SEQ]`.
 *
 *  - **Monotonic:** accept only if `incomingPolicySeq > currentFloor`
 *    (ADR-017 §Carried-forward: device-global `policy_seq`, verify step 7
 *    `policy_seq <= floor ⇒ REGRESSION`).
 *
 *  - **Rotation preserves the floor:** the floor is a *single device-global
 *    counter*, NOT keyed by parent pubkey (ADR-017 K2 + §Carried-forward
 *    "Rotation carries the floor forward; rotation never lowers it"). This
 *    function takes ONLY the floor + seq and never a pubkey, so a key rotation is
 *    structurally incapable of resetting/lowering the floor — there is no
 *    per-key counter to read as zero. A `RotateKey` bundle is just another
 *    bundle carrying a higher `policy_seq` admitted through this same path.
 *
 *  - **JC1 (JCS integer bound):** `incomingPolicySeq` must be in the JCS-safe
 *    range `0..2^53-1` ([Canonical.MAX_JCS_SAFE_INTEGER]) or signer/verifier
 *    canonical bytes diverge and a high seq can poison the floor. Checked
 *    *before* the monotonic comparison (matches ADR-017 verify step 3, which
 *    runs before step 7).
 *
 *  - **MAX_SEQ_JUMP floor-poison bound:** ADR-017 §Carried-forward specifies
 *    `MAX_SEQ_JUMP = 1024` — reject any bundle with `policy_seq > floor +
 *    [MAX_SEQ_JUMP]` so a single malicious/buggy bundle cannot ratchet the floor
 *    to an unreachable value and permanently freeze updates (verify step 8).
 *    Applied against an established floor; genesis admission (the `null` path)
 *    is handled one layer up and is not subject to the jump bound here.
 *
 * Every rejection is fail-closed: the caller MUST treat any [Decision.RejectStrict]
 * as "drop to the strict baseline now" (ADR-017 part 3, CLAUDE.md fail-closed
 * invariant), never as "ignore and keep the current permissive policy".
 */
object ReplayFloor {
    /**
     * Largest delta a single bundle may advance the floor (ADR-017
     * §Carried-forward, "MAX_SEQ_JUMP = 1024 against floor-poison DoS"). An
     * incoming `policy_seq > floor + MAX_SEQ_JUMP` is rejected so one bundle can
     * never ratchet the floor to an unreachable value (a permanent update-freeze
     * DoS); the worst case becomes a bounded, recoverable skip.
     */
    const val MAX_SEQ_JUMP: Long = 1024L

    /**
     * The reserved genesis-log `policy_seq`. ADR-017 part 4: `policy_seq = 0` is
     * reserved and "never trusted as a live policy" (preserves PROTOCOL.md §1.1
     * where the genesis *log entry* `seq` starts at 0). The genesis floor is
     * conceptually 0; the first admissible *live* policy seq is therefore
     * [GENESIS_FIRST_VALID_SEQ].
     */
    const val GENESIS_FLOOR: Long = 0L

    /**
     * The smallest `policy_seq` a never-provisioned child may TOFU-accept at
     * genesis (ADR-017 part 4: "the first valid signed bundle whose
     * `policy_seq ≥ 1`"). Exposed for the genesis-admission layer that owns the
     * provisioning marker; this pure function does not perform genesis admission.
     */
    const val GENESIS_FIRST_VALID_SEQ: Long = 1L

    /** Outcome of a [admit] decision. */
    sealed interface Decision {
        /**
         * The bundle advances the floor. [newFloor] is the value the caller must
         * persist as the new replay floor *after* the policy is durably applied
         * (ADR-017 commit ordering: floor advances LAST, never before durable
         * commit). [newFloor] always equals the admitted `incomingPolicySeq`.
         */
        data class Accept(
            val newFloor: Long,
        ) : Decision

        /**
         * The bundle is rejected. The caller MUST fail closed and apply the
         * strict baseline immediately (ADR-017 part 3) — this is never a
         * "keep current policy and alert later" outcome. [reason] is a stable
         * diagnostic label, not a control-flow signal.
         */
        data class RejectStrict(
            val reason: String,
        ) : Decision
    }

    /**
     * Decide whether [incomingPolicySeq] may advance the replay floor.
     *
     * @param currentFloor the established device-global replay floor, or `null`
     *   if absent/unreadable. `null` is treated as the fail-closed anomaly case
     *   per ADR-017 K2 (see class kdoc) and is NEVER accepted here — genesis TOFU
     *   admission is the caller's responsibility one layer up, where the
     *   provisioning marker is readable.
     * @param incomingPolicySeq the `policy_seq` carried by the candidate bundle.
     * @return [Decision.Accept] with the new floor, or [Decision.RejectStrict].
     */
    fun admit(
        currentFloor: Long?,
        incomingPolicySeq: Long,
    ): Decision {
        // JC1 first (ADR-017 verify step 3, before the monotonic comparison):
        // a seq outside 0..2^53-1 cannot round-trip JCS and can poison the floor.
        if (incomingPolicySeq < 0 || incomingPolicySeq > Canonical.MAX_JCS_SAFE_INTEGER) {
            return Decision.RejectStrict(
                "policy_seq $incomingPolicySeq outside JCS-safe range 0..${Canonical.MAX_JCS_SAFE_INTEGER} (ADR-017 JC1)",
            )
        }

        // K2 fail-closed genesis: an absent/unreadable floor is the anomaly case
        // here, never "fresh/accept-anything". Strict baseline until anchored.
        if (currentFloor == null) {
            return Decision.RejectStrict(
                "absent replay floor — strict baseline until parent-anchored (ADR-017 K2 fail-closed genesis)",
            )
        }

        // Monotonic / K1 rollback: equal-or-lower is a detected rollback/replay.
        if (incomingPolicySeq <= currentFloor) {
            return Decision.RejectStrict(
                "policy_seq $incomingPolicySeq <= floor $currentFloor — rollback/replay (ADR-017 K1, REGRESSION)",
            )
        }

        // Floor-poison DoS bound (ADR-017 verify step 8): bound the forward jump.
        if (incomingPolicySeq > currentFloor + MAX_SEQ_JUMP) {
            return Decision.RejectStrict(
                "policy_seq $incomingPolicySeq > floor $currentFloor + MAX_SEQ_JUMP $MAX_SEQ_JUMP — floor-poison bound (ADR-017)",
            )
        }

        return Decision.Accept(incomingPolicySeq)
    }
}
