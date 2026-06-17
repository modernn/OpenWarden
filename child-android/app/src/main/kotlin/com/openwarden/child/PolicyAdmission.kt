package com.openwarden.child

/**
 * The ADR-017 child admission pipeline for a [SignedBundle].
 *
 * Enforces the exact fail-closed ordering from ADR-017 §"Commit ordering"
 * (verify_bundle), then the two-phase durable commit:
 *
 *   1. v == 1                                            else MALFORMED
 *   2. canonical size <= 65536                           else MALFORMED
 *   3. any int/ts field outside 0..2^53-1               else MALFORMED   (JC1, BEFORE sig)
 *   4. child_device_id == my id                          else MALFORMED   (audience, BEFORE sig)
 *   5. body := canonicalize(bundle without "sig")
 *   6. Ed25519.verify(sig, body, pinned_parent_pub)      else SIG_FAIL    (fail-closed)
 *   --- genesis vs floor (ADR-017 part 4), then monotonic/jump (ReplayFloor) ---
 *   7. floor read = max(at_rest, chain) (ReplayFloorStore.effectiveFloor)
 *   8. genesis gate:
 *        - never provisioned (no marker, no pinned key, no floor) + seq >= 1
 *            => TOFU accept, seed floor (handled by caller's two-phase commit)
 *        - provisioned but floor missing/lower => ANOMALY => strict baseline
 *   9. monotonic: seq > floor                            else REGRESSION (strict)
 *  10. jump: seq <= floor + MAX_SEQ_JUMP                 else MALFORMED  (floor-poison)
 *   --- two-phase commit (caller): stage -> apply+fsync -> advance floor -> ack ---
 *
 * The DECISION ([decide]) is a pure function (no Android, no I/O, no crypto state
 * beyond the supplied [pinnedParentPubkey]) so it is fully JVM-unit-testable. The
 * STATEFUL entry point ([admit]) reads the floor/marker/id from [ReplayFloorStore],
 * runs [decide], and on accept performs the two-phase commit via an injected
 * [Applier] so the floor advances LAST, never before durable apply (crash-safe,
 * idempotent — ADR-017 commit safety).
 *
 * Every non-accept outcome is fail-closed: the caller MUST NOT apply the bundle
 * and MUST drop to / remain on the strict baseline.
 */
object PolicyAdmission {

    /** Max canonical bundle size (PROTOCOL.md §2.1 step 2 / ADR-017 verify step 2). */
    const val MAX_CANONICAL_SIZE = 65536

    /** Pure decision result. */
    sealed interface Outcome {
        /**
         * Bundle is admissible. Caller MUST run the two-phase commit:
         * stage -> apply+fsync -> advance floor to [policySeq] -> ack.
         * [genesis] is true when this is a never-provisioned TOFU accept (the
         * caller must also pin the parent key + write the provisioning marker).
         */
        data class Accept(val policySeq: Long, val genesis: Boolean) : Outcome

        /** Structurally invalid (JC1, audience, version, size, jump). Reject; no apply. */
        data class RejectMalformed(val reason: String) : Outcome

        /**
         * Signature failure or replay/rollback/anomaly. Fail closed to the strict
         * baseline (ADR-017 part 3). Never "keep current permissive policy".
         */
        data class RejectStrict(val reason: String) : Outcome
    }

    /**
     * Pure admission decision. No I/O.
     *
     * @param bundle the candidate bundle.
     * @param myChildDeviceId this child's own id (audience target).
     * @param pinnedParentPubkey the pinned parent Ed25519 pubkey, or `null` if no
     *   key is pinned yet (genesis candidate).
     * @param provisioned whether the provisioning marker is set.
     * @param effectiveFloor `max(at_rest, chain)` from [ReplayFloorStore], or
     *   `null` if no floor is seeded.
     * @param floorAnomaly true if [ReplayFloorStore] detected at-rest-below-chain
     *   (ADR-017 part 1/3). Inert until the chain-mirror follow-up lands.
     */
    fun decide(
        bundle: SignedBundle,
        myChildDeviceId: String,
        pinnedParentPubkey: ByteArray?,
        provisioned: Boolean,
        effectiveFloor: Long?,
        floorAnomaly: Boolean = false,
    ): Outcome {
        // Step 1: version.
        if (bundle.v != 1) return Outcome.RejectMalformed("unsupported bundle version ${bundle.v}")

        // Step 2: canonical size bound.
        val body: ByteArray = try {
            BundleVerifier.canonicalBody(bundle)
        } catch (e: Exception) {
            // Canonicalization failure (e.g. a float snuck into policy) is malformed, fail-closed.
            return Outcome.RejectMalformed("canonicalization failed: ${e.message}")
        }
        if (body.size > MAX_CANONICAL_SIZE) {
            return Outcome.RejectMalformed("canonical size ${body.size} > $MAX_CANONICAL_SIZE")
        }

        // Step 3: JC1 integer bound, BEFORE signature (ADR-017 verify step 3).
        // policy_seq is the canonical integer field present in the child schema today;
        // when integer-ms timestamps land they MUST be bounds-checked here too.
        if (!Canonical.isJcsSafe(bundle.policy_seq)) {
            return Outcome.RejectMalformed(
                "policy_seq ${bundle.policy_seq} outside JCS-safe range 0..${Canonical.MAX_JCS_SAFE_INTEGER} (ADR-017 JC1)",
            )
        }

        // Step 4: audience binding, BEFORE signature (ADR-017 §6 / verify step 4).
        // An empty id can never be a real child id, so legacy/unaddressed bundles fail here.
        if (bundle.child_device_id.isEmpty() || bundle.child_device_id != myChildDeviceId) {
            return Outcome.RejectMalformed(
                "child_device_id '${bundle.child_device_id}' != my id (audience mismatch, ADR-017 §6)",
            )
        }

        // Steps 5-6: signature over canonical body. Fail-closed.
        if (pinnedParentPubkey == null) {
            // No pinned key. Only legitimate in the never-provisioned genesis path,
            // where the bundle is self-asserting its first key (TOFU). We still verify
            // the bundle's signature against its OWN claimed-but-not-yet-pinned key is
            // out of scope for the pure decision (no key material here); the genesis
            // accept below is gated on the caller having verified the sig against the
            // key it is about to pin. For provisioned children a null pinned key is an
            // anomaly => strict.
            if (provisioned) {
                return Outcome.RejectStrict("provisioned child with no pinned parent key — anomaly (ADR-017 part 4)")
            }
            // No pinned key + not provisioned. ONLY legitimate as the clean
            // never-provisioned genesis state (no floor either). If a floor is already
            // seeded with no key and no marker, that is an inconsistent/partial state —
            // an anomaly, fail-closed — never a genesis accept that would apply WITHOUT
            // any signature verification.
            if (effectiveFloor != null) {
                return Outcome.RejectStrict(
                    "no pinned key + not provisioned but floor present — inconsistent state, anomaly (ADR-017 part 4)",
                )
            }
            // Clean never-provisioned genesis candidate: defer signature to admit(), which
            // pins+verifies atomically against genesisPubkey. Fall through to the genesis gate.
        } else {
            if (!BundleVerifier.verify(bundle, pinnedParentPubkey)) {
                return Outcome.RejectStrict("Ed25519 signature verification failed (SIG_FAIL, fail-closed)")
            }
        }

        // Local floor anomaly (ADR-017 part 1/3): at-rest read below the chain witness.
        // Inert today (chainFloor() == null), wired for the chain-mirror follow-up.
        if (floorAnomaly) {
            return Outcome.RejectStrict("at-rest floor below chain witness — rollback anomaly (ADR-017 part 1)")
        }

        // Step 8: genesis (TOFU) vs anomaly (ADR-017 part 4).
        val neverProvisioned = !provisioned && pinnedParentPubkey == null && effectiveFloor == null
        if (neverProvisioned) {
            // TOFU: accept the first valid bundle with policy_seq >= 1; seed the floor.
            // policy_seq = 0 is reserved and never a live policy.
            return if (bundle.policy_seq >= ReplayFloor.GENESIS_FIRST_VALID_SEQ) {
                Outcome.Accept(bundle.policy_seq, genesis = true)
            } else {
                Outcome.RejectMalformed(
                    "genesis bundle policy_seq ${bundle.policy_seq} < ${ReplayFloor.GENESIS_FIRST_VALID_SEQ} (0 is reserved, ADR-017 part 4)",
                )
            }
        }

        // Provisioned (marker or pinned key present) but floor missing/lower => anomaly.
        // ADR-017 part 4: never treated as genesis.
        if (effectiveFloor == null) {
            return Outcome.RejectStrict(
                "provisioned child with missing floor — anomaly, not genesis (ADR-017 part 4)",
            )
        }

        // Steps 9-10: monotonic + jump, via the ported pure ReplayFloor decision.
        return when (val d = ReplayFloor.admit(effectiveFloor, bundle.policy_seq)) {
            is ReplayFloor.Decision.Accept -> Outcome.Accept(d.newFloor, genesis = false)
            is ReplayFloor.Decision.RejectStrict -> {
                // ReplayFloor folds JC1/jump (structural) and rollback (strict) into one
                // RejectStrict; classify by reason so the wire surfaces MALFORMED for the
                // structural cases (ADR-017 verify steps 3/8) and strict for rollback (step 7).
                if (d.reason.contains("MAX_SEQ_JUMP") || d.reason.contains("JCS-safe")) {
                    Outcome.RejectMalformed(d.reason)
                } else {
                    Outcome.RejectStrict(d.reason)
                }
            }
        }
    }

    /**
     * Durable two-phase apply, run by the caller ONLY on an [Outcome.Accept].
     * stage -> apply+fsync -> advance floor -> ack, in that order (ADR-017 commit
     * ordering). The floor advances LAST so a crash before durable apply leaves the
     * old floor in force and the same bundle re-applies idempotently on restart.
     */
    interface Applier {
        /** Stage the policy to a temp record (not yet live). */
        fun stage(bundle: SignedBundle)

        /** Apply the policy and fsync (durable). MUST throw on any failure (fail-closed). */
        fun applyAndFsync(bundle: SignedBundle)

        /** Emit AckPolicy{policy_seq,"applied"} (chain witness, ADR-017 part 1). */
        fun ack(policySeq: Long)
    }

    /** Final wire/control result of [admit]. */
    sealed interface Result {
        data class Applied(val policySeq: Long, val genesis: Boolean) : Result
        data class Rejected(val malformed: Boolean, val reason: String) : Result
    }

    /**
     * The persisted floor/marker/id state [admit] needs. [ReplayFloorStore]
     * implements this against EncryptedSharedPreferences; tests supply an
     * in-memory fake so the two-phase commit is JVM-unit-testable without the
     * EncryptedSharedPreferences / Robolectric dependency.
     */
    interface FloorState {
        fun childDeviceId(): String
        fun isProvisioned(): Boolean
        fun markProvisioned()
        fun atRestFloor(): Long?
        fun chainFloor(): Long?
        fun effectiveFloor(): Long?
        fun advanceFloor(policySeq: Long)

        /**
         * In-memory witness (R7) of the highest `policy_seq` this process has *applied*, even when
         * the durable [advanceFloor] for it failed. [admit] folds this into the admission floor so a
         * partial transaction — applied but un-floored (R6 made it Rejected, but the apply already
         * landed) — cannot be rolled back by a lower *valid* bundle in the same process. It survives
         * a failed floor write (its whole point); it does NOT survive a process restart — the durable
         * cross-restart witness is the not-yet-built event-log chain mirror ([chainFloor] / ADR-017
         * part 1), which is also why a whole-snapshot rollback is caught by the parent on next sync
         * (ADR-017 part 2), not locally. Defaulted so non-witness fakes need not implement it.
         */
        fun appliedHighWater(): Long? = null
        fun noteApplied(policySeq: Long) {}
    }

    /**
     * Process-wide lock serializing the WHOLE admission transaction (R5). `/policy` admissions on
     * different Ktor threads must not interleave: the floor is read inside this lock and advanced
     * inside it, so a second concurrent admit re-reads the just-advanced floor and `decide()`
     * rejects an out-of-order (older-seq) bundle as a replay — instead of applying it after a newer
     * one and re-opening a freshly-denied app. (The watchdog takes only `PolicyEnforcer.APPLY_LOCK`,
     * never this one, so there is no lock-ordering cycle.)
     */
    private val ADMIT_LOCK = Any()

    /**
     * Stateful admission entry point. Reads floor/marker/id from [store], decides,
     * and on accept runs the two-phase commit via [applier], pinning the parent key
     * + writing the marker first on a genesis accept. Floor advances LAST.
     *
     * @param genesisPubkey for a genesis accept, the parent pubkey to pin
     *   (the caller has verified the bundle's sig against it). Ignored otherwise.
     */
    fun admit(
        bundle: SignedBundle,
        store: FloorState,
        applier: Applier,
        pinParentKey: (ByteArray) -> Unit,
        pinnedParentPubkey: ByteArray?,
        genesisPubkey: ByteArray? = null,
    ): Result = synchronized(ADMIT_LOCK) {
        // R5: the floor read below and the advanceFloor in the commit must be one critical section,
        // or two concurrent admits both read the same floor and apply out of order. Inside the lock,
        // the second admit re-reads the advanced floor and decide() rejects the stale (older) bundle.
        val myId = store.childDeviceId()
        val provisioned = store.isProvisioned()
        // R7/R8: fold the in-memory applied high-water into the floor, so a partial transaction
        // (applied but un-floored, after an advanceFloor failure) cannot be rolled back by a lower
        // valid bundle in the same process. It guards anything STRICTLY BELOW the applied seq (the
        // rollback, R7) — but NOT the *equal* seq, because a retry of the just-applied bundle must
        // be allowed back in to re-advance a durable floor a transient write failure left stale
        // (R8). So fold (highWater − 1): `seq > floor` then means `seq > durableFloor && seq >=
        // highWater`. Normally this equals the at-rest floor; it only bites after a failed write.
        val highWaterGuard = store.appliedHighWater()?.minus(1)
        val floor = listOfNotNull(store.effectiveFloor(), highWaterGuard).maxOrNull()
        val anomaly = store.atRestFloor()?.let { atRest ->
            store.chainFloor()?.let { chain -> atRest < chain }
        } ?: false

        val outcome = decide(
            bundle = bundle,
            myChildDeviceId = myId,
            pinnedParentPubkey = pinnedParentPubkey,
            provisioned = provisioned,
            effectiveFloor = floor,
            floorAnomaly = anomaly,
        )

        when (outcome) {
            is Outcome.RejectMalformed -> Result.Rejected(malformed = true, reason = outcome.reason)
            is Outcome.RejectStrict -> Result.Rejected(malformed = false, reason = outcome.reason)
            is Outcome.Accept -> {
                try {
                    // Two-phase commit. Order is load-bearing (ADR-017 commit ordering).
                    if (outcome.genesis) {
                        // Genesis (TOFU): the pure decide() could not verify the signature
                        // (no key material), so we MUST verify the bundle here against the
                        // key we are about to pin, BEFORE pinning/marking. A genesis accept
                        // with no pubkey, or a sig that does not verify against it, is
                        // fail-closed — never pin an unverified key.
                        val pub = genesisPubkey
                            ?: return@synchronized Result.Rejected(false, "genesis accept without a pubkey to pin (fail-closed)")
                        if (!BundleVerifier.verify(bundle, pub)) {
                            return@synchronized Result.Rejected(false, "genesis bundle signature does not verify against its pinned key (SIG_FAIL, fail-closed)")
                        }
                        // Pin the parent key + mark provisioned BEFORE seeding floor.
                        pinParentKey(pub)
                        store.markProvisioned()
                    }
                    applier.stage(bundle)             // 12. stage — the candidate is now the persisted active bundle
                    // 12b. R9: record the rollback witness AS SOON AS staged. stage() has already made
                    // this the active bundle the watchdog enforces, and applyAndFsync() can mutate
                    // live/durable state before throwing (e.g. allowlist verify -> lockNow + throw). If
                    // we waited until after apply, a throwing apply would leave the staged newer policy
                    // with no witness, and a lower valid seq could roll it back. Recording at stage
                    // covers every durable/live mutation before the floor advances.
                    store.noteApplied(outcome.policySeq)
                    applier.applyAndFsync(bundle)     // 13. apply + fsync (durable; may throw)
                    store.advanceFloor(outcome.policySeq) // 14. advance floor LAST
                    applier.ack(outcome.policySeq)    // 15. ack (chain witness)
                    Result.Applied(outcome.policySeq, outcome.genesis)
                } catch (e: Exception) {
                    // Apply/stage failure => fail-closed, floor NOT advanced => same bundle
                    // re-applies cleanly on retry (idempotent). Never a permanent REGRESSION.
                    Result.Rejected(malformed = false, reason = "durable apply failed (fail-closed): ${e.message}")
                }
            }
        }
    }
}
