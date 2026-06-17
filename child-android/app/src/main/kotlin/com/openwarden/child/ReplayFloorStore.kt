package com.openwarden.child

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * At-rest persistence of the ADR-017 device-global replay floor + the
 * provisioning marker that distinguishes genesis (TOFU) from a rollback anomaly.
 *
 * ADR-017 part 1 specifies the floor be persisted **twice**: (a) TEE/StrongBox-bound
 * encrypted storage, and (b) mirrored in the append-only hash-chained event log,
 * with the live read being `max(at_rest, chain)`. This module implements (a) only:
 *
 *   >>> FOLLOW-UP (tracked): no append-only hash-chained event log exists in the
 *   >>> child app yet (verified: no `prev_hash` / EventLog / AckPolicy code in
 *   >>> child-android as of this commit). The chain-mirror (b) and the
 *   >>> `max(at_rest, chain)` read are a SEPARATE follow-up — see [chainFloor].
 *   >>> We do NOT fake a chain. Until the event log lands, the floor is the
 *   >>> at-rest value alone. This is a known, deliberately-NOT-stubbed gap; it
 *   >>> weakens (does not remove) rollback detection: a whole-snapshot restore is
 *   >>> caught by the parent on next sync (ADR-017 part 2), not locally.
 *
 * The at-rest store is [EncryptedSharedPreferences] whose [MasterKey] is
 * StrongBox-backed where available and TEE-backed otherwise. Per ADR-017 this is
 * **best-effort at-rest integrity only** — it protects the bytes against in-place
 * edits without the hardware key, but does NOT make the value monotonic across a
 * whole-snapshot restore. It is explicitly NOT a hardware monotonic counter; none
 * exists on any Android tier (ADR-017 Context / Consequences).
 *
 * The floor is **device-global** and NOT keyed by parent pubkey: a `RotateKey`
 * carries it forward and never lowers it (ADR-017 K2 / §Carried-forward).
 */
class ReplayFloorStore(context: Context) : PolicyAdmission.FloorState {

    private val prefs: SharedPreferences by lazy { open(context) }

    private fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            // Best-effort hardware binding; falls back to TEE where StrongBox is absent.
            .setRequestStrongBoxBacked(true)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * The persisted at-rest replay floor, or `null` if never seeded. `null` is a
     * distinct state from `0`: a never-provisioned child has no floor at all;
     * `0` ([ReplayFloor.GENESIS_FLOOR]) would be a seeded-but-reserved value
     * (never produced by [advanceFloor], which only stores admitted seqs ≥ 1).
     */
    override fun atRestFloor(): Long? =
        if (prefs.contains(KEY_FLOOR)) prefs.getLong(KEY_FLOOR, ReplayFloor.GENESIS_FLOOR) else null

    /**
     * Highest `policy_seq` witnessed in the append-only hash-chained event log.
     *
     * >>> FOLLOW-UP (tracked): returns `null` because no event log exists yet (see
     * >>> class kdoc). When the chain lands, this MUST read the highest
     * >>> AckPolicy{policy_seq,"applied"} entry so [effectiveFloor] can enforce
     * >>> `max(at_rest, chain)` and fail closed on an at-rest-below-chain anomaly
     * >>> (ADR-017 part 1 / part 3). Returning `null` here means the max() is a
     * >>> no-op today — honest, not stubbed.
     */
    override fun chainFloor(): Long? = null

    /**
     * Effective floor = `max(at_rest, chain)` (ADR-017 part 1).
     *
     * Today, with no chain, this is the at-rest value. When the chain-mirror
     * follow-up lands, an at-rest value strictly LOWER than the chain witness is
     * a rollback anomaly that [PolicyAdmission] MUST treat as fail-closed to the
     * strict baseline (ADR-017 part 3). That anomaly check is the `floorAnomaly`
     * parameter of [PolicyAdmission.decide]; it is inert until [chainFloor]
     * returns a real value.
     */
    override fun effectiveFloor(): Long? {
        val atRest = atRestFloor()
        val chain = chainFloor()
        return when {
            atRest == null && chain == null -> null
            atRest == null -> chain
            chain == null -> atRest
            else -> maxOf(atRest, chain)
        }
    }

    /**
     * Advance the at-rest floor to [policySeq]. MUST be called only AFTER the
     * policy is durably applied (ADR-017 two-phase commit: floor advances LAST).
     * Never lowers the floor: a call with [policySeq] ≤ current is ignored
     * (idempotent re-apply after a crash must not regress — ADR-017 commit safety).
     */
    override fun advanceFloor(policySeq: Long) {
        val current = atRestFloor()
        if (current != null && policySeq <= current) return // never lower; idempotent
        // R6: commit() returns false on a failed durable write. Ignoring it would let
        // PolicyAdmission ack + report Applied while the persisted floor stayed old — so after a
        // restart a stale lower-seq bundle above the old floor could re-admit and undo a newer
        // deny, despite the R5 lock. Fail closed: throw, so admit()'s two-phase commit treats it as
        // a durable-apply failure (Rejected, no ack) and the floor is never silently behind reality.
        check(prefs.edit().putLong(KEY_FLOOR, policySeq).commit()) {
            "replay floor commit() failed for seq=$policySeq (fail-closed)"
        }
        val readback = prefs.getLong(KEY_FLOOR, ReplayFloor.GENESIS_FLOOR)
        check(readback == policySeq) {
            "replay floor readback ($readback) != $policySeq after commit (fail-closed)"
        }
    }

    /**
     * High-water of the highest applied `policy_seq` rollback-witness (R7/R10/R11), or `null` if
     * never staged. Backed by the **durable** [KEY_STAGED] field in [EncryptedSharedPreferences]:
     * `/policy` constructs a fresh [ReplayFloorStore] per request, AND the witness must survive a
     * process restart — a restart in the window between staging and the floor-advance must not
     * re-open the staged rollback — so it is persisted at-rest, not held in memory. ([noteApplied]
     * writes it before the bundle is made active, with the same commit+readback fail-closed
     * contract as [advanceFloor].)
     */
    override fun appliedHighWater(): Long? =
        if (prefs.contains(KEY_STAGED)) prefs.getLong(KEY_STAGED, ReplayFloor.GENESIS_FLOOR) else null

    override fun noteApplied(policySeq: Long) {
        val cur = appliedHighWater()
        if (cur != null && policySeq <= cur) return
        // R10/R11: the rollback witness MUST be durable BEFORE the bundle is made active (admit()
        // calls this before stage()). A failed commit is fail-closed — throw so admit() rejects and
        // never stages, rather than make the bundle active with only an in-memory witness a restart
        // would lose (which would re-open the staged-but-failed rollback). No silent memory fallback.
        check(prefs.edit().putLong(KEY_STAGED, policySeq).commit()) {
            "staged rollback-witness commit() failed for seq=$policySeq (fail-closed)"
        }
        // R10/R11: same readback authority as advanceFloor — a commit() that returns true but did
        // not durably land would let admit() stage a bundle whose rollback-witness a restart loses.
        // Verify the persisted value before treating the witness as durable; throw (fail-closed) so
        // admit() rejects and never stages on a silent write divergence.
        val readback = appliedHighWater()
        check(readback == policySeq) {
            "staged rollback-witness readback ($readback) != $policySeq after commit (fail-closed)"
        }
    }

    /** True iff this child has been provisioned (pairing marker written). ADR-017 part 4. */
    override fun isProvisioned(): Boolean = prefs.getBoolean(KEY_PROVISIONED, false)

    /**
     * Mark the child provisioned (written at the genesis TOFU accept, alongside
     * pinning the parent pubkey). After this, a missing/lower floor is an anomaly,
     * never genesis (ADR-017 part 4).
     */
    override fun markProvisioned() {
        // R6: same fail-closed contract as advanceFloor — a silently-failed provisioning write would
        // leave the child looking never-provisioned, re-opening the genesis (TOFU) path on restart.
        check(prefs.edit().putBoolean(KEY_PROVISIONED, true).commit()) {
            "provisioning marker commit() failed (fail-closed)"
        }
    }

    /**
     * The child's own stable device id used for audience binding (ADR-017 §6).
     * Generated once on first read and persisted. In v1 this is a random stable
     * id; ADR-017 allows "the child's pinned Ed25519 pubkey, or a stable id
     * derived from it." When the child key-pair lands, derive this from the
     * child pubkey instead (FOLLOW-UP — see DeviceIdentity).
     */
    override fun childDeviceId(): String {
        prefs.getString(KEY_CHILD_ID, null)?.let { return it }
        val generated = DeviceIdentity.generateStableId()
        // R6: if this write silently fails the next read regenerates a DIFFERENT id, breaking
        // audience binding. Fail closed rather than proceed with an unpersisted id.
        check(prefs.edit().putString(KEY_CHILD_ID, generated).commit()) {
            "child device id commit() failed (fail-closed)"
        }
        return generated
    }

    companion object {
        const val PREFS_NAME = "openwarden_replay_floor"
        const val KEY_FLOOR = "policy_seq_floor"
        const val KEY_PROVISIONED = "provisioned"
        const val KEY_CHILD_ID = "child_device_id"

        /** R10/R11: durable stage witness — the highest applied seq, persisted so it survives a restart. */
        const val KEY_STAGED = "staged_high_water"
    }
}
