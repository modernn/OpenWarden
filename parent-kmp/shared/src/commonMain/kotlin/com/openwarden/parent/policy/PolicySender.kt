package com.openwarden.parent.policy

import com.openwarden.parent.crypto.RootKeyProvider
import com.openwarden.proto.Policy
import com.openwarden.proto.PolicyBundle
import kotlinx.serialization.json.Json

/**
 * Assembles → signs → POSTs a policy bundle (ADR-034 D6). Fail-closed: refuses to send unless a root
 * key is provisioned AND a child is paired, never emits an unsigned/unaddressed bundle, and never
 * lowers or reuses a `policy_seq`. A reserved seq binds to one immutable signed bundle; a transport
 * failure returns that bundle for an idempotent [resend] (same seq).
 */
class PolicySender(
    private val rootKeyProvider: RootKeyProvider,
    private val seqStore: PolicySeqStore,
    private val pairedChildStore: PairedChildStore,
    private val transport: PolicyTransport,
    private val nonceGenerator: NonceGenerator,
    private val clockMs: () -> Long,
    private val freshnessWindowMs: Long = DEFAULT_FRESHNESS_WINDOW_MS,
) {
    // Matches PolicySigner's wire config so the transmitted JSON is the PROTOCOL §2 shape:
    // defaults included, null optionals omitted (§3.1 rule 6 forbids `null` on the wire).
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** Build + sign + send [policy] under a fresh strictly-greater seq. */
    suspend fun send(policy: Policy): SendResult {
        // Pre-checks BEFORE reserving a seq, so a fail-closed refusal never burns a seq.
        val childId = pairedChildStore.pairedChildId() ?: return SendResult.NotPaired
        if (rootKeyProvider.rootPublicKey() == null) return SendResult.NotProvisioned

        val seq = seqStore.reserveNext()
        val unsigned = PolicyBundleBuilder.build(
            policy = policy,
            childDeviceId = childId,
            policySeq = seq,
            nowMs = clockMs(),
            freshnessWindowMs = freshnessWindowMs,
            nonceHex = nonceGenerator.newNonceHex(),
        )
        val signed = SignedBundleAssembler.assemble(unsigned, rootKeyProvider)
            ?: return SendResult.NotProvisioned // key vanished between check and sign — fail closed
        return post(signed)
    }

    /** Re-POST an already-signed bundle (idempotent retry after a transport failure; same seq). */
    suspend fun resend(signed: PolicyBundle): SendResult {
        require(signed.sig != null) { "resend requires a signed bundle" }
        return post(signed)
    }

    private suspend fun post(signed: PolicyBundle): SendResult {
        val body = json.encodeToString(PolicyBundle.serializer(), signed)
        return when (val result = transport.postPolicy(body)) {
            is PolicyPostResult.Applied -> SendResult.Sent(result.policySeq, signed)
            is PolicyPostResult.Rejected -> SendResult.Rejected(result.reason, signed)
            is PolicyPostResult.TransportError -> SendResult.TransportFailed(result.message, signed)
        }
    }

    companion object {
        /** Short freshness window (PROTOCOL §5 "hours, not weeks"); 24h default, configurable (ADR-034). */
        const val DEFAULT_FRESHNESS_WINDOW_MS: Long = 24L * 60 * 60 * 1000
    }
}

sealed interface SendResult {
    /** Child applied the bundle. */
    data class Sent(val policySeq: Long, val bundle: PolicyBundle) : SendResult

    /** Child rejected (e.g. REGRESSION/EXPIRED/SIG_FAIL) — [reason] is the child's. */
    data class Rejected(val reason: String, val bundle: PolicyBundle) : SendResult

    /** Transport failed — retry the SAME [bundle] via [PolicySender.resend] (ADR-034 D3). */
    data class TransportFailed(val message: String, val bundle: PolicyBundle) : SendResult

    /** No root key — onboarding (#24) not complete. */
    data object NotProvisioned : SendResult

    /** No paired child (#23 not done / not yet paired). */
    data object NotPaired : SendResult
}
