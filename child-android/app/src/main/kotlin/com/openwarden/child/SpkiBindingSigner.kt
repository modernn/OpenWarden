package com.openwarden.child

/**
 * Produces the child's [SpkiAssertion] for a given TLS leaf SPKI, signed by the child identity key
 * via the [IdentityKeyProvider] seam (ADR-031 D2/D5). The parent verifies it with [SpkiBinding.verify].
 *
 * Fail-closed: before pairing there is no identity key ([NotProvisionedIdentityKeyProvider]), so this
 * returns `null` — the child vouches for nothing it cannot sign. The real StrongBox-backed provider
 * arrives with issue #22; this signer is wired and tested now (with a synthetic provider) so that
 * landing #22 needs no change here.
 */
object SpkiBindingSigner {

    /**
     * @return a verifiable [SpkiAssertion] over `base64url(SHA-256([spkiDer]))`, or `null` if [provider]
     *   has no identity key yet (pre-pairing) or signing fails.
     */
    fun assertFor(spkiDer: ByteArray, provider: IdentityKeyProvider): SpkiAssertion? {
        provider.identityPublicKey() ?: return null
        val unsigned = SpkiAssertion(v = 1, spki_sha256 = SpkiBinding.spkiSha256(spkiDer))
        val sigBytes = provider.sign(SpkiBinding.canonicalBody(unsigned)) ?: return null
        return unsigned.copy(sig = sigBytes.joinToString("") { "%02x".format(it) })
    }
}
