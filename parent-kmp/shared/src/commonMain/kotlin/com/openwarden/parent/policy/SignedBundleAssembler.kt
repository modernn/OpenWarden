package com.openwarden.parent.policy

import com.openwarden.parent.crypto.PolicySigner
import com.openwarden.parent.crypto.RootKeyProvider
import com.openwarden.proto.PolicyBundle

/**
 * Signs an unsigned [PolicyBundle] with the parent root key (ADR-034 D2). The signature is the root
 * key's Ed25519 over [PolicySigner.signingBytes] (RFC 8785 JCS minus `sig`, ADR-015/019),
 * hex-encoded into `sig`. Uses [RootKeyProvider.sign] (Bouncy Castle, RFC 8032 — interoperable with
 * the child's libsodium verifier, proven by ADR-033's RFC KATs).
 *
 * Fail-closed: returns `null` if the provider is not provisioned (`sign` → `null`: no key, no
 * signature). The caller MUST NOT transmit an unsigned bundle.
 */
object SignedBundleAssembler {
    fun assemble(unsigned: PolicyBundle, provider: RootKeyProvider): PolicyBundle? {
        require(unsigned.sig == null) { "bundle already signed" }
        val signature = provider.sign(PolicySigner.signingBytes(unsigned)) ?: return null
        return unsigned.copy(sig = signature.toHexLower())
    }
}
