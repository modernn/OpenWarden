package com.openwarden.child

/**
 * The child's own Ed25519 **identity** key (ADR-031 D5) — the key the parent pins at pairing and the
 * key that signs a [SpkiAssertion] vouching for the LAN TLS cert.
 *
 * This is a **seam**. The real, StrongBox-backed identity keypair is generated during pairing
 * (issue #22) and is NOT yet implemented on `main`; until it lands, [NotProvisionedIdentityKeyProvider]
 * returns `null` everywhere, so [SpkiBindingSigner] produces no assertion (fail-closed: the child
 * vouches for nothing it cannot sign) and [SpkiBinding.verify] rejects (no trust anchor). Injecting
 * the provider keeps the binding logic testable today with a synthetic key, and lets #22 drop in the
 * StrongBox implementation without touching the transport-confidentiality code.
 */
interface IdentityKeyProvider {
    /** The child's Ed25519 identity public key (32 raw bytes), or `null` before pairing (#22). */
    fun identityPublicKey(): ByteArray?

    /**
     * Ed25519-sign [message] with the child identity private key; `null` before pairing. The real
     * implementation signs inside StrongBox (#22) so the private key never leaves hardware.
     */
    fun sign(message: ByteArray): ByteArray?
}

/**
 * Default until issue #22 lands the StrongBox child identity key: there is no identity key yet, so
 * everything is `null` — fail-closed. With this provider the child advertises over mDNS but vouches
 * for no TLS cert, and any [SpkiBinding.verify] against a `null` pinned identity rejects.
 */
object NotProvisionedIdentityKeyProvider : IdentityKeyProvider {
    override fun identityPublicKey(): ByteArray? = null
    override fun sign(message: ByteArray): ByteArray? = null
}
