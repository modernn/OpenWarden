package com.openwarden.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestration coverage for [ChildKeyManager] (issue #22, ADR-032), including the key acceptance:
 * provisioning supplies the real [IdentityKeyProvider] that **unblocks #21's [SpkiBindingSigner]**
 * (it returned `null` while the provider was [NotProvisionedIdentityKeyProvider]).
 */
class ChildKeyManagerTest {
    private val nonce = ByteArray(32) { (it * 3 + 1).toByte() }

    @Test
    fun `provisionAndBind yields a verifiable binding plus attestation chain`() {
        val store = FakeChildKeyStore()
        val result = ChildKeyManager(store).provisionAndBind(nonce)!!
        assertTrue(store.isProvisioned())
        assertTrue(result.attestationChain.isNotEmpty())
        assertTrue(ChildKeyBindingVerifier.verify(result.binding, store.bindingSpki(), nonce))
    }

    @Test
    fun `identityProvider is fail-closed before provisioning`() {
        val mgr = ChildKeyManager(FakeChildKeyStore())
        assertNull(mgr.identityProvider().identityPublicKey())
        // #21 signer still vouches for nothing pre-pairing.
        assertNull(SpkiBindingSigner.assertFor("leaf-spki".toByteArray(), mgr.identityProvider()))
    }

    @Test
    fun `provisioning unblocks the issue 21 SpkiBindingSigner`() {
        val store = FakeChildKeyStore()
        val mgr = ChildKeyManager(store)
        mgr.provisionAndBind(nonce)

        val provider = mgr.identityProvider()
        assertNotNull(provider.identityPublicKey())

        // The real identity key now produces an SpkiAssertion the parent-side verifier accepts.
        val spkiDer = "tls-leaf-spki-der".toByteArray()
        val assertion = SpkiBindingSigner.assertFor(spkiDer, provider)!!
        assertTrue(SpkiBinding.verify(assertion, spkiDer, provider.identityPublicKey()))
        // ...and rejects when a different cert is presented (the binding is to THIS spki).
        assertFalse(SpkiBinding.verify(assertion, "other-spki".toByteArray(), provider.identityPublicKey()))
    }
}
