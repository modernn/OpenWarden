package com.openwarden.child

import java.util.Base64

/**
 * Produces the child's [ChildKeyBinding] for a pairing attempt: `K_bind` (ECDSA-P-256) signs over the
 * TEE-resident Ed25519/X25519 public keys + the parent's `provisioning_nonce` (ADR-032 D2). The
 * parent verifies it with [ChildKeyBindingVerifier.verify] after checking `K_bind`'s attestation.
 *
 * Mirrors [SpkiBindingSigner]. Fail-closed: returns `null` if the [ChildKeyStore] is not provisioned
 * (any of the three keys missing) or signing fails — the child emits no binding it cannot back.
 */
object ChildKeyBindingSigner {
    /**
     * @return a verifiable [ChildKeyBinding] over the store's identity + encryption public keys and
     *   [nonce], signed by `K_bind`; or `null` if [store] lacks any key (pre-provisioning) or signing
     *   fails. [nonce] is the parent's single-use `provisioning_nonce` (raw 32 bytes, §7.1).
     */
    fun bindingFor(
        nonce: ByteArray,
        store: ChildKeyStore,
    ): ChildKeyBinding? {
        val ed = store.identityPublicKey() ?: return null
        val x = store.encryptionPublicKey() ?: return null
        store.bindingPublicKey() ?: return null // need K_bind to sign; fail-closed if absent
        val unsigned =
            ChildKeyBinding(
                v = 1,
                child_ed25519_pub = b64Url(ed),
                child_x25519_pub = b64Url(x),
                provisioning_nonce = b64Url(nonce),
            )
        val sigBytes = store.signBinding(ChildKeyBindingVerifier.canonicalBody(unsigned)) ?: return null
        return unsigned.copy(sig = sigBytes.joinToString("") { "%02x".format(it) })
    }

    private fun b64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
