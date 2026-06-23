package com.openwarden.child

/**
 * The child's own Ed25519 **identity** key (ADR-031 D5) — the key the parent pins at pairing and the
 * key that signs a [SpkiAssertion] vouching for the LAN TLS cert.
 *
 * This is a **seam**. The real identity keypair is generated during pairing (issue #22,
 * [KeystoreChildKeys] behind [KeystoreIdentityKeyProvider]); before pairing
 * [NotProvisionedIdentityKeyProvider] returns `null` everywhere, so [SpkiBindingSigner] produces no
 * assertion (fail-closed: the child vouches for nothing it cannot sign) and [SpkiBinding.verify]
 * rejects (no trust anchor). Injecting the provider keeps the binding logic testable with a synthetic
 * key and lets #22 drop in the real implementation without touching the transport-confidentiality code.
 */
interface IdentityKeyProvider {
    /** The child's Ed25519 identity public key (32 raw bytes), or `null` before pairing (#22). */
    fun identityPublicKey(): ByteArray?

    /**
     * Ed25519-sign [message] with the child identity private key; `null` before pairing. The real
     * implementation (#22) signs with the **TEE-resident** child identity key — attestation-bound to a
     * StrongBox device-binding key, since StrongBox cannot hold Curve25519 (ADR-032); the private key
     * never leaves the keystore.
     */
    fun sign(message: ByteArray): ByteArray?
}

/**
 * Default until issue #22 lands the child identity key: there is no identity key yet, so
 * everything is `null` — fail-closed. With this provider the child advertises over mDNS but vouches
 * for no TLS cert, and any [SpkiBinding.verify] against a `null` pinned identity rejects.
 */
object NotProvisionedIdentityKeyProvider : IdentityKeyProvider {
    override fun identityPublicKey(): ByteArray? = null
    override fun sign(message: ByteArray): ByteArray? = null
}
