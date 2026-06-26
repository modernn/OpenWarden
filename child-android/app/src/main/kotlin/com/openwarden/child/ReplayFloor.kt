package com.openwarden.child

/**
 * Parent-anchored, rollback-resistant replay floor — the **pure monotonic
 * decision function** for `policy_seq` admission (ADR-017, issue #5).
 *
 * PORTED, byte-rule-identical, from
 * `parent-kmp/proto/src/commonMain/kotlin/com/openwarden/proto/ReplayFloor.kt`
 * (branch `feat/proto-replay-floor`). The child cannot import the proto module
 * (composite build deferred / version skew, ADR-017 §Consequences), so the same
 * decision logic is duplicated here. **If you change one, change both.** The
 * constants (`MAX_SEQ_JUMP`, `GENESIS_FLOOR`, `GENESIS_FIRST_VALID_SEQ`,
 * `MAX_JCS_SAFE_INTEGER`) and the comparison order MUST stay identical.
 *
 * This is *only* the decision logic, NOT storage. It takes the current floor
 * value and an incoming `policySeq` and returns whether the bundle may advance
 * the floor or must be rejected (caller then drops to the strict baseline). It
 * deliberately holds no state, no I/O, and no crypto. Persistence of the
 * watermark ([ReplayFloorStore]) and signature/audience verification
 * ([BundleVerifier]) live one layer up.
 *
 * ADR-017 mapping (verify_bundle steps 3, 7, 8):
 *
 *  - **K1 (rollback ⇒ strict, detection ≠ prevention):** an incoming seq that is
 *    equal-or-lower than the floor is a detected rollback/replay; we
 *    [Decision.RejectStrict] so the caller applies the strict baseline
 *    immediately, never an "accept now, alert later" branch (ADR-017 part 3).
 *
 *  - **K2 (fail-closed null floor):** a `null` floor models an absent / unreadable
 *    persisted counter. With only `(floor, seq)` in hand, the fail-closed reading
 *    is the only safe one: a `null` floor is treated as the anomaly case ⇒
 *    [Decision.RejectStrict]. It is NEVER treated as "fresh / accept-anything".
 *    Genesis TOFU admission lives one layer up ([PolicyAdmission]), where the
 *    provisioning marker is readable; that layer decides genesis vs anomaly
 *    (ADR-017 part 4) and only then seeds this floor.
 *
 *  - **Monotonic:** accept only if `incomingPolicySeq > currentFloor`
 *    (ADR-017 verify step 7: `policy_seq <= floor ⇒ REGRESSION`).
 *
 *  - **Rotation preserves the floor:** the floor is a *single device-global
 *    counter*, NOT keyed by parent pubkey (ADR-017 K2 + §Carried-forward). This
 *    function takes ONLY the floor + seq and never a pubkey, so a key rotation is
 *    structurally incapable of resetting/lowering the floor.
 *
 *  - **JC1 (JCS integer bound):** `incomingPolicySeq` must be in the JCS-safe
 *    range `0..2^53-1` ([MAX_JCS_SAFE_INTEGER]) or signer/verifier canonical
 *    bytes diverge and a high seq can poison the floor. Checked *before* the
 *    monotonic comparison (matches ADR-017 verify step 3, before step 7).
 *
 *  - **MAX_SEQ_JUMP floor-poison bound:** ADR-017 §Carried-forward specifies
 *    `MAX_SEQ_JUMP = 1024` — reject any bundle with `policy_seq > floor +
 *    [MAX_SEQ_JUMP]` (verify step 8). Applied against an established floor;
 *    genesis admission (the `null` path) is handled one layer up and is not
 *    subject to the jump bound here.
 *
 * Every rejection is fail-closed: the caller MUST treat any [Decision.RejectStrict]
 * as "drop to the strict baseline now" (ADR-017 part 3, CLAUDE.md fail-closed
 * invariant), never as "ignore and keep the current permissive policy".
 */
object ReplayFloor {
    /** 2^53 − 1: the largest integer JCS number formatting round-trips exactly (ADR-017 JC1). */
    const val MAX_JCS_SAFE_INTEGER: Long = 9_007_199_254_740_991L

    /**
     * Largest delta a single bundle may advance the floor (ADR-017
     * §Carried-forward, "MAX_SEQ_JUMP = 1024 against floor-poison DoS").
     */
    const val MAX_SEQ_JUMP: Long = 1024L

    /**
     * The reserved genesis-log `policy_seq`. ADR-017 part 4: `policy_seq = 0` is
     * reserved and "never trusted as a live policy". The genesis floor is
     * conceptually 0; the first admissible *live* policy seq is therefore
     * [GENESIS_FIRST_VALID_SEQ].
     */
    const val GENESIS_FLOOR: Long = 0L

    /**
     * The smallest `policy_seq` a never-provisioned child may TOFU-accept at
     * genesis (ADR-017 part 4: "the first valid signed bundle whose
     * `policy_seq ≥ 1`"). Consumed by [PolicyAdmission]; this pure function does
     * not perform genesis admission.
     */
    const val GENESIS_FIRST_VALID_SEQ: Long = 1L

    /** Outcome of an [admit] decision. */
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
         * strict baseline immediately (ADR-017 part 3) — never a
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
     *   if absent/unreadable. `null` is the fail-closed anomaly case here and is
     *   NEVER accepted — genesis TOFU admission is [PolicyAdmission]'s job.
     * @param incomingPolicySeq the `policy_seq` carried by the candidate bundle.
     * @return [Decision.Accept] with the new floor, or [Decision.RejectStrict].
     */
    fun admit(
        currentFloor: Long?,
        incomingPolicySeq: Long,
    ): Decision {
        // JC1 first (ADR-017 verify step 3, before the monotonic comparison):
        // a seq outside 0..2^53-1 cannot round-trip JCS and can poison the floor.
        if (incomingPolicySeq < 0 || incomingPolicySeq > MAX_JCS_SAFE_INTEGER) {
            return Decision.RejectStrict(
                "policy_seq $incomingPolicySeq outside JCS-safe range 0..$MAX_JCS_SAFE_INTEGER (ADR-017 JC1)",
            )
        }

        // K2 fail-closed: an absent/unreadable floor is the anomaly case here,
        // never "fresh/accept-anything". Strict baseline until parent-anchored.
        if (currentFloor == null) {
            return Decision.RejectStrict(
                "absent replay floor — strict baseline until parent-anchored (ADR-017 K2 fail-closed)",
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
