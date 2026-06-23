package com.openwarden.child

/**
 * The real [IdentityKeyProvider] (issue #22) — backs the child Ed25519 **identity** key (`K_id`) with
 * the hardware-resident key held by a [ChildKeyStore], replacing [NotProvisionedIdentityKeyProvider]
 * once the child is provisioned. This is what lets #21's [SpkiBindingSigner] actually produce an
 * assertion (it returned `null` while the provider was the not-provisioned stub).
 *
 * Thin by design: it forwards to the [ChildKeyStore] seam, so the Ed25519 private key never leaves
 * the keystore (TEE on a Pixel — StrongBox cannot hold Curve25519; ADR-032) and the binding logic
 * stays testable with a fake store. Fail-closed: a not-yet-provisioned store returns `null`, so the
 * signer still vouches for nothing.
 */
class KeystoreIdentityKeyProvider(private val store: ChildKeyStore) : IdentityKeyProvider {

    override fun identityPublicKey(): ByteArray? = store.identityPublicKey()

    override fun sign(message: ByteArray): ByteArray? = store.signIdentity(message)
}
