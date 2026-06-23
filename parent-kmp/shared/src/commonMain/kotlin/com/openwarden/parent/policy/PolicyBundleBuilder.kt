package com.openwarden.parent.policy

import com.openwarden.proto.Policy
import com.openwarden.proto.PolicyBundle

/**
 * Assembles an UNSIGNED [PolicyBundle] (ADR-034 D1 / PROTOCOL §2-§3). Pure: `nowMs` and `nonceHex`
 * are injected by the caller so the result is deterministic in tests. `issued_at == not_before ==
 * nowMs`; `not_after = nowMs + freshnessWindowMs` (short window, PROTOCOL §5).
 */
object PolicyBundleBuilder {
    /** PROTOCOL §2: nonce is 16 bytes = 32 lowercase hex chars. */
    const val NONCE_HEX_LEN = 32

    fun build(
        policy: Policy,
        childDeviceId: String,
        policySeq: Long,
        nowMs: Long,
        freshnessWindowMs: Long,
        nonceHex: String,
    ): PolicyBundle {
        // Enforce the nonce contract at assembly so a malformed nonce fails fast here, not silently
        // at the child's verify.
        require(nonceHex.length == NONCE_HEX_LEN && nonceHex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "nonce must be $NONCE_HEX_LEN lowercase hex chars"
        }
        require(freshnessWindowMs > 0) { "freshnessWindowMs must be positive" }
        return PolicyBundle(
            policySeq = policySeq,
            childDeviceId = childDeviceId,
            issuedAt = nowMs,
            notBefore = nowMs,
            notAfter = nowMs + freshnessWindowMs,
            nonce = nonceHex,
            policy = policy,
        )
    }
}
