package com.openwarden.parent.policy

import com.openwarden.proto.Policy
import com.openwarden.proto.PolicyBundle

/**
 * Assembles an UNSIGNED [PolicyBundle] (ADR-034 D1 / PROTOCOL §2-§3). Pure: `nowMs` and `nonceHex`
 * are injected by the caller so the result is deterministic in tests. `issued_at == not_before ==
 * nowMs`; `not_after = nowMs + freshnessWindowMs` (short window, PROTOCOL §5).
 */
object PolicyBundleBuilder {
    fun build(
        policy: Policy,
        childDeviceId: String,
        policySeq: Long,
        nowMs: Long,
        freshnessWindowMs: Long,
        nonceHex: String,
    ): PolicyBundle = PolicyBundle(
        policySeq = policySeq,
        childDeviceId = childDeviceId,
        issuedAt = nowMs,
        notBefore = nowMs,
        notAfter = nowMs + freshnessWindowMs,
        nonce = nonceHex,
        policy = policy,
    )
}
