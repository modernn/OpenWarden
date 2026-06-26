package com.openwarden.child

/**
 * Orchestrates the child's pairing key material (issue #22, ADR-032): generate the three keys and
 * produce the [ChildKeyBinding] for the §7.2 POST, and expose the real [IdentityKeyProvider] that
 * unblocks #21's [SpkiBindingSigner] once provisioned.
 *
 * Stateless over an injected [ChildKeyStore] seam, so the orchestration is deterministically testable
 * on the JVM with a fake; the real [KeystoreChildKeys] runs only on a bench Pixel 7 (ADR-032 gate).
 *
 * NOTE (scope): there is no live pairing flow to *call* this yet — the parent-displays-QR / child-scans
 * pairing handshake is the deferred ADR-025 D5 work, and the live TLS socket that consumes
 * [identityProvider] is the deferred ADR-031 D5 sibling. This lands the material + the binding so those
 * siblings can wire it without re-touching the keystore.
 */
class ChildKeyManager(
    private val store: ChildKeyStore,
) {
    /**
     * Generate a fresh child key set (deletes any prior set — re-pair regenerates) and return the
     * signed [ChildKeyBinding] to POST, with the
     * `K_bind` attestation chain. Fail-closed: returns `null` if provisioning left any key absent or
     * binding-signing failed (the child completes no pairing it cannot vouch for).
     *
     * @param nonce the parent's single-use `provisioning_nonce` (raw 32 bytes, §7.1) — used both as
     *   `K_bind`'s `setAttestationChallenge` and inside the binding body for per-attempt freshness.
     */
    fun provisionAndBind(nonce: ByteArray): ProvisionResult? {
        store.provision(nonce)
        val binding = ChildKeyBindingSigner.bindingFor(nonce, store) ?: return null
        val chain = store.attestationChain() ?: return null
        return ProvisionResult(binding, chain)
    }

    /**
     * The identity provider backing #21's [SpkiBindingSigner]: the real [KeystoreIdentityKeyProvider]
     * once provisioned, else [NotProvisionedIdentityKeyProvider] (fail-closed — vouches for nothing).
     */
    fun identityProvider(): IdentityKeyProvider =
        if (store.isProvisioned()) KeystoreIdentityKeyProvider(store) else NotProvisionedIdentityKeyProvider

    /** The child's §7.2 response material: the signed binding + `K_bind`'s attestation cert chain (DER). */
    data class ProvisionResult(
        val binding: ChildKeyBinding,
        val attestationChain: List<ByteArray>,
    )
}
