package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

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

    /**
     * Decode the received wire object into the typed model AFTER verification (ADR-040 / ADR-019 D2:
     * verify first, parse second). `ignoreUnknownKeys` so a parent-signed field the child does not
     * model is dropped from the typed view (it still counted in the verified signing bytes); the
     * other flags mirror [BundleVerifier] so the typed view round-trips the same fields.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Pure decision result. */
    sealed interface Outcome {
        /**
         * Bundle is admissible. Caller MUST run the two-phase commit:
         * stage -> apply+fsync -> advance floor to [policySeq] -> ack.
         * [genesis] is true when this is a never-provisioned TOFU accept (the
         * caller must also pin the parent key + write the provisioning marker).
         * [bundle] is the typed model decoded from the verified received document — the
         * single source of truth the caller applies (ADR-040: parsed from the same bytes
         * whose signature was verified, so there is no doc/model mismatch to exploit).
         */
        data class Accept(val policySeq: Long, val genesis: Boolean, val bundle: SignedBundle) : Outcome

        /** Structurally invalid (JC1, audience, version, size, jump). Reject; no apply. */
        data class RejectMalformed(val reason: String) : Outcome

        /**
         * Signature failure or replay/rollback/anomaly. Fail closed to the strict
         * baseline (ADR-017 part 3). Never "keep current permissive policy".
         */
        data class RejectStrict(val reason: String) : Outcome

        /**
         * Freshness step 9 (PROTOCOL §2.1 / §5, ADR-041): the bundle's window has not opened yet
         * (`monotonic_now < not_before` — CLOCK_SKEW). Do NOT apply, and do NOT tear down the policy
         * currently in force — keep it and retry on the next contact. Distinct from a rejection: a
         * not-yet-valid bundle is a deferral, not an anomaly.
         */
        data class Defer(val reason: String) : Outcome

        /**
         * Freshness step 10 (PROTOCOL §2.1 / §5, ADR-041): the bundle's window has closed
         * (`monotonic_now >= not_after` — EXPIRED). Do NOT apply; the device drops to / stays in the
         * stale baseline (the watchdog enforces it via the active-bundle freshness tier), never
         * "unrestricted".
         */
        data class RejectExpired(val reason: String) : Outcome
    }

    /**
     * Pure admission decision. No I/O.
     *
     * **ADR-040: the crypto authority is [receivedDoc] — the wire JSON object the child actually
     * received — NOT a re-serialization of the typed model.** JC1 bounds, canonical size, and the
     * Ed25519 signature are all computed over [receivedDoc]; the typed [SignedBundle] is decoded from
     * the SAME object only after verification (verify first, parse second, apply third). The decoded
     * bundle rides back in [Outcome.Accept] so the caller applies exactly the verified document.
     *
     * @param receivedDoc the wire object received on `/policy` (the signed document).
     * @param myChildDeviceId this child's own id (audience target).
     * @param pinnedParentPubkey the pinned parent Ed25519 pubkey, or `null` if no
     *   key is pinned yet (genesis candidate).
     * @param provisioned whether the provisioning marker is set.
     * @param effectiveFloor `max(at_rest, chain)` from [ReplayFloorStore], or
     *   `null` if no floor is seeded.
     * @param floorAnomaly true if [ReplayFloorStore] detected at-rest-below-chain
     *   (ADR-017 part 1/3). Inert until the chain-mirror follow-up lands.
     * @param freshnessNow the PROTOCOL §5.1 monotonic estimate of the parent's current time
     *   ([FreshnessClock.estimate]). [FreshnessClock.Now.Unusable] (no anchor / post-reboot) skips
     *   the window check and admits on signature + floor alone, re-anchoring on apply (ADR-041 D3).
     * @param notAfterWatermarkMs the highest `not_after` ever applied (the monotonic-on-write
     *   anchor watermark), or `null` if never seeded. Reserved for full rollback detection (D4/step
     *   11), which is the same tracked gap as the floor chain mirror.
     */
    fun decide(
        receivedDoc: JsonObject,
        myChildDeviceId: String,
        pinnedParentPubkey: ByteArray?,
        provisioned: Boolean,
        effectiveFloor: Long?,
        floorAnomaly: Boolean = false,
        freshnessNow: FreshnessClock.Now = FreshnessClock.Now.Unusable,
        notAfterWatermarkMs: Long? = null,
    ): Outcome {
        // ADR-019 D2 / ADR-040 ordering: verify over the RECEIVED document FIRST, then parse. The
        // pre-signature gates (size, JC1, version, audience) read the wire object directly — NOT a
        // typed re-parse — and are all reject-only, so a forged pre-verify field can only cause a
        // rejection, never an apply (the apply path is strictly gated behind a verified Accept).

        // Steps 2-3: canonical size + JC1 integer bound, over the RECEIVED document (ADR-040),
        // BEFORE signature (ADR-017 verify steps 2-3). signingBytes() asserts every integer in the
        // whole tree is JCS-safe (ADR-019 D4) and throws on a float/overflow — fail-closed MALFORMED.
        val body: ByteArray = try {
            BundleVerifier.signingBytes(receivedDoc)
        } catch (e: Exception) {
            // JC1 overflow / non-integer number / canonicalization failure — malformed, fail-closed.
            return Outcome.RejectMalformed("JC1/canonicalization failed: ${e.message}")
        }
        if (body.size > MAX_CANONICAL_SIZE) {
            return Outcome.RejectMalformed("canonical size ${body.size} > $MAX_CANONICAL_SIZE")
        }

        // Step 1: version, read from the RECEIVED document (missing / non-integer / != 1 => MALFORMED).
        val version = (receivedDoc["v"] as? JsonPrimitive)?.intOrNull
        if (version != 1) return Outcome.RejectMalformed("unsupported or missing bundle version $version")

        // Step 4: audience binding, from the RECEIVED document, BEFORE signature (ADR-017 §6 / step 4).
        // A missing/non-string/empty id can never be a real child id, so it fails closed here.
        val audience = (receivedDoc["child_device_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (audience.isNullOrEmpty() || audience != myChildDeviceId) {
            return Outcome.RejectMalformed(
                "child_device_id '$audience' != my id (audience mismatch, ADR-017 §6)",
            )
        }

        // Steps 5-6: signature over the RECEIVED document (ADR-040). Fail-closed.
        if (pinnedParentPubkey == null) {
            // No pinned key. Only legitimate in the never-provisioned genesis path, where the bundle
            // is self-asserting its first key (TOFU). The pure decision cannot verify it (no key
            // material here); the genesis accept below is gated on admit() verifying the signature
            // over this same document against the key it is about to pin. For provisioned children a
            // null pinned key is an anomaly => strict.
            if (provisioned) {
                return Outcome.RejectStrict("provisioned child with no pinned parent key — anomaly (ADR-017 part 4)")
            }
            // No pinned key + not provisioned. ONLY legitimate as the clean never-provisioned genesis
            // state (no floor either). A floor seeded with no key and no marker is an
            // inconsistent/partial state — an anomaly, fail-closed — never a genesis accept that would
            // apply WITHOUT any signature verification.
            if (effectiveFloor != null) {
                return Outcome.RejectStrict(
                    "no pinned key + not provisioned but floor present — inconsistent state, anomaly (ADR-017 part 4)",
                )
            }
            // Clean never-provisioned genesis candidate: defer signature to admit(), which
            // pins+verifies atomically against genesisPubkey. Fall through to the genesis gate.
        } else {
            if (!BundleVerifier.verifyDocument(receivedDoc, pinnedParentPubkey)) {
                return Outcome.RejectStrict("Ed25519 signature verification failed (SIG_FAIL, fail-closed)")
            }
        }

        // Verify first, PARSE SECOND (ADR-019 D2): only now decode the typed model from the SAME
        // verified document. A verified-but-unparseable body is rejected, never applied. (Genesis is
        // sig-deferred above; admit() re-verifies over this document before any pin/stage/apply, so
        // nothing is applied before verification on either path.)
        val bundle: SignedBundle = try {
            json.decodeFromJsonElement(SignedBundle.serializer(), receivedDoc)
        } catch (e: Exception) {
            return Outcome.RejectMalformed("verified-but-unparseable policy bundle: ${e.message}")
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
                Outcome.Accept(bundle.policy_seq, genesis = true, bundle = bundle)
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

        // Monotonic + jump (replay floor), via the ported pure ReplayFloor decision.
        return when (val d = ReplayFloor.admit(effectiveFloor, bundle.policy_seq)) {
            // Replay floor passed -> apply the freshness window (PROTOCOL §2.1 steps 9-11, ADR-041).
            is ReplayFloor.Decision.Accept ->
                freshnessGate(bundle, freshnessNow) ?: Outcome.Accept(d.newFloor, genesis = false, bundle = bundle)
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
     * PROTOCOL §2.1 steps 9-10 freshness window (ADR-041), evaluated against the §5.1 monotonic
     * estimate [now]. Returns a non-`Accept` [Outcome] if the window rejects the bundle, or `null`
     * to proceed to `Accept`.
     *
     * `Unusable` clock (no anchor yet, or post-reboot before re-anchor) cannot evaluate the window:
     * the bundle is admitted on its signature + monotonic `policy_seq` floor alone and re-anchors on
     * apply (the watchdog holds the stale baseline while the anchor is unusable, so nothing is
     * under-restricted in the gap — ADR-041 D3). Step 11 full anchor-rollback detection is a tracked
     * gap (same as the floor chain mirror); the monotonic-on-write anchor + reboot detection are the
     * local defenses.
     */
    private fun freshnessGate(bundle: SignedBundle, now: FreshnessClock.Now): Outcome? {
        val monotonicNow = (now as? FreshnessClock.Now.Usable)?.monotonicNowMs ?: return null
        // Step 9: window not open yet -> defer (keep current policy, retry on next contact).
        if (monotonicNow < bundle.not_before) {
            return Outcome.Defer(
                "CLOCK_SKEW: monotonic_now $monotonicNow < not_before ${bundle.not_before} (PROTOCOL §2.1 step 9)",
            )
        }
        // Step 10: window closed -> expired (drop to / stay in stale baseline).
        if (monotonicNow >= bundle.not_after) {
            return Outcome.RejectExpired(
                "EXPIRED: monotonic_now $monotonicNow >= not_after ${bundle.not_after} (PROTOCOL §2.1 step 10)",
            )
        }
        return null
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

        /**
         * Freshness CLOCK_SKEW (PROTOCOL §2.1 step 9 / ADR-041): the bundle is not yet valid. NOT
         * applied; the policy currently in force is kept (not torn down) and the parent should retry.
         */
        data class Deferred(val reason: String) : Result

        /**
         * Freshness EXPIRED (PROTOCOL §2.1 step 10 / ADR-041): the bundle's window has closed. NOT
         * applied; the watchdog holds the device in the stale baseline via the active-bundle freshness
         * tier (never "unrestricted").
         */
        data class Expired(val reason: String) : Result
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

        // ---- ADR-041 §5.1 freshness anchor (defaulted so non-freshness fakes need not implement) ----

        /** The last anchored signed-parent time (`issued_at` of the last applied bundle / heartbeat). */
        fun freshnessAnchorParentMs(): Long? = null

        /** `elapsedRealtime()` captured at the anchor instant (paired with [freshnessAnchorParentMs]). */
        fun freshnessAnchorElapsedMs(): Long? = null

        /** Highest `not_after` ever applied — the monotonic-on-write watermark (ADR-041 D4). */
        fun notAfterWatermarkMs(): Long? = null

        /**
         * Advance the freshness anchor after a durable apply (ADR-041 D4): set the signed-parent time
         * [parentIssuedAtMs] + [nowElapsedMs], and raise the not_after watermark to [notAfterMs] when
         * non-null (bundles carry one; heartbeats pass `null`). Monotonic-on-write: the anchor's
         * parent time and the watermark never decrease. Best-effort — a failed write only ever leaves
         * the estimate STALER (more restriction), never looser. Defaulted to a no-op for fakes.
         */
        fun advanceFreshnessAnchor(parentIssuedAtMs: Long, nowElapsedMs: Long, notAfterMs: Long?) {}
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
     * Stateful admission entry point. Reads floor/marker/id from [store], decides over the RECEIVED
     * document [receivedDoc] (ADR-040), and on accept runs the two-phase commit via [applier] using
     * the typed bundle [decide] decoded from that verified document, pinning the parent key + writing
     * the marker first on a genesis accept. Floor advances LAST.
     *
     * @param receivedDoc the wire JSON object received on `/policy` — the signed document the
     *   signature is verified over (never a re-serialization of a typed model).
     * @param genesisPubkey for a genesis accept, the parent pubkey to pin. The signature is verified
     *   over [receivedDoc] against it here, BEFORE pinning. Ignored otherwise.
     * @param nowElapsedMs current `SystemClock.elapsedRealtime()` (ADR-041): paired with the stored
     *   anchor to estimate the parent's monotonic "now" for the freshness window, and captured as the
     *   new anchor's elapsed component on apply. The caller (ApiServer) passes the real clock; tests
     *   pass a fixed value. Defaulted so replay-floor tests that do not exercise freshness are
     *   unaffected (no anchor seeded ⇒ Unusable ⇒ the window check is skipped).
     */
    fun admit(
        receivedDoc: JsonObject,
        store: FloorState,
        applier: Applier,
        pinParentKey: (ByteArray) -> Unit,
        pinnedParentPubkey: ByteArray?,
        genesisPubkey: ByteArray? = null,
        nowElapsedMs: Long = 0L,
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

        // ADR-041 §5.1: estimate the parent's monotonic "now" from the stored anchor + the kernel
        // clock. Unusable (no anchor / reboot) skips the window check in decide() and re-anchors on
        // apply below; the watchdog holds the stale baseline while the anchor is Unusable.
        val anchor = FreshnessClock.Anchor(
            parentAnchorMs = store.freshnessAnchorParentMs(),
            elapsedAtAnchorMs = store.freshnessAnchorElapsedMs(),
            notAfterWatermarkMs = store.notAfterWatermarkMs(),
        )
        val freshnessNow = FreshnessClock.estimate(anchor, nowElapsedMs)

        val outcome = decide(
            receivedDoc = receivedDoc,
            myChildDeviceId = myId,
            pinnedParentPubkey = pinnedParentPubkey,
            provisioned = provisioned,
            effectiveFloor = floor,
            floorAnomaly = anomaly,
            freshnessNow = freshnessNow,
            notAfterWatermarkMs = anchor.notAfterWatermarkMs,
        )

        when (outcome) {
            is Outcome.RejectMalformed -> Result.Rejected(malformed = true, reason = outcome.reason)
            is Outcome.RejectStrict -> Result.Rejected(malformed = false, reason = outcome.reason)
            is Outcome.Defer -> Result.Deferred(outcome.reason)
            is Outcome.RejectExpired -> Result.Expired(outcome.reason)
            is Outcome.Accept -> {
                // Apply EXACTLY the verified document: the typed bundle decode()d from the bytes whose
                // signature was checked (ADR-040 — no doc/model mismatch to exploit).
                val bundle = outcome.bundle
                try {
                    // Two-phase commit. Order is load-bearing (ADR-017 commit ordering).
                    // Genesis (TOFU): the pure decide() could not verify the signature (no key
                    // material), so we MUST verify the RECEIVED document here against the key we are
                    // about to pin, BEFORE pinning. A genesis accept with no pubkey, or a sig that does
                    // not verify against it, is fail-closed — never pin an unverified key.
                    val genesisPub: ByteArray? = if (outcome.genesis) {
                        genesisPubkey
                            ?.takeIf { BundleVerifier.verifyDocument(receivedDoc, it) }
                            ?: return@synchronized Result.Rejected(false, "genesis bundle signature does not verify against its pinned key (SIG_FAIL, fail-closed)")
                    } else {
                        null
                    }
                    // R9/R11/R12: record the DURABLE rollback witness FIRST — before ANY durable
                    // provisioning (pin/mark) or active-bundle state (stage). stage() makes the
                    // candidate active and applyAndFsync() can mutate live/durable state before
                    // throwing, so the witness must already be durable or a staged-but-failed apply
                    // could be rolled back (even across a restart). It is fail-closed: if it can't
                    // persist it throws here, leaving a CLEAN state — nothing pinned/marked/staged —
                    // so the same signed bundle repairs idempotently on retry (R12: a genesis whose
                    // witness failed must not strand the device pinned-but-floorless).
                    store.noteApplied(outcome.policySeq)
                    if (genesisPub != null) {
                        pinParentKey(genesisPub)      // 11c. pin the verified parent key
                        store.markProvisioned()       //      + mark provisioned, before seeding floor
                    }
                    applier.stage(bundle)             // 12. stage — the candidate is now the persisted active bundle
                    applier.applyAndFsync(bundle)     // 13. apply + fsync (durable; may throw)
                    store.advanceFloor(outcome.policySeq) // 14. advance floor LAST
                    applier.ack(outcome.policySeq)    // 15. ack (chain witness)
                    // ADR-041 D4: re-anchor the freshness clock from this applied bundle's signed
                    // issued_at, AFTER durable apply, monotonic-on-write. Best-effort: the apply +
                    // floor are already durable, and a failed anchor write only makes the next
                    // freshness estimate STALER (more restriction), never looser — so it must not undo
                    // the (successful) apply. The watchdog re-asserts regardless.
                    runCatching {
                        store.advanceFreshnessAnchor(bundle.issued_at, nowElapsedMs, bundle.not_after)
                    }
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
