package com.openwarden.child

import android.content.Context
import android.util.Log

/**
 * Concrete [PolicyAdmission.Applier] wiring the two-phase commit to the real
 * child storage + enforcement (ADR-017 commit ordering).
 *
 *   stage         -> persist the bundle to [PolicyStore] (atomic temp->rename)
 *   applyAndFsync -> apply the allowlist via [PolicyEnforcer]; the persist in
 *                    stage() is the durable record (rename(2) is the fsync point)
 *   ack           -> AckPolicy chain witness
 *
 * The floor advance (step 14) is owned by [PolicyAdmission.admit] and happens
 * AFTER applyAndFsync returns, so a crash before durable apply leaves the old
 * floor and the same bundle re-applies idempotently.
 *
 *   >>> FOLLOW-UP (tracked): [ack] is a log-only stub — the append-only
 *   >>> hash-chained event log (AckPolicy entries / chain witness, ADR-017
 *   >>> part 1) does not exist in the child yet. When it lands, ack MUST append
 *   >>> AckPolicy{policy_seq,"applied"} to the chain so ReplayFloorStore.chainFloor()
 *   >>> has a real witness. NOT stubbed as a security control — it is the audit
 *   >>> mirror, and its absence is the same gap noted on ReplayFloorStore.chainFloor.
 */
class DefaultPolicyApplier(private val context: Context) : PolicyAdmission.Applier {

    private val store = PolicyStore(context)

    override fun stage(bundle: SignedBundle) {
        // PolicyStore.persist is an atomic temp-write + rename(2); the rename is the
        // durability point. We persist here (stage) and treat applyAndFsync as the
        // enforcement step; if enforcement throws, the floor is not advanced.
        store.persist(bundle)
    }

    override fun applyAndFsync(bundle: SignedBundle) {
        // Throws on DPM failure (not Device Owner, etc.) => admit() catches => fail-closed,
        // floor not advanced. Allowlist enforcement is the live policy apply.
        PolicyEnforcer(context).applyAllowlist(bundle.policy.allowlist.toSet())
    }

    override fun ack(policySeq: Long) {
        // FOLLOW-UP: append AckPolicy{policySeq,"applied"} to the hash-chained event log
        // when it exists. Log-only today.
        Log.i(TAG, "AckPolicy applied policy_seq=$policySeq (chain-mirror follow-up pending)")
    }

    companion object {
        const val TAG = "OpenWardenAdmission"
    }
}
