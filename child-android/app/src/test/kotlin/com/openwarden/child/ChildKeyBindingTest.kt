package com.openwarden.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Deterministic, seam-injected coverage for the [ChildKeyBinding] sign/verify path (ADR-032 D2/D4,
 * PROTOCOL §7.3 check 4b). The StrongBox keygen itself is bench-only ([KeystoreChildKeys]); here the
 * binding logic is proven against synthetic keys via [FakeChildKeyStore], the repo idiom.
 */
class ChildKeyBindingTest {

    private val nonce = ByteArray(32) { (it + 7).toByte() }

    private fun provisionedStore() = FakeChildKeyStore().also { it.provision(nonce) }

    private fun b64(x: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(x)

    @Test
    fun `signer produces a binding the verifier accepts`() {
        val store = provisionedStore()
        val binding = ChildKeyBindingSigner.bindingFor(nonce, store)!!
        assertTrue(ChildKeyBindingVerifier.verify(binding, store.bindingSpki(), nonce))
    }

    @Test
    fun `pre-provision signer returns null (fail-closed)`() {
        assertNull(ChildKeyBindingSigner.bindingFor(nonce, FakeChildKeyStore()))
    }

    @Test
    fun `null binding key rejects (no anchor)`() {
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!
        assertFalse(ChildKeyBindingVerifier.verify(b, null, nonce))
    }

    @Test
    fun `wrong K_bind key rejects`() {
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!
        val attackerKBind = P256TestSigner.newKeypair().spkiDer
        assertFalse(ChildKeyBindingVerifier.verify(b, attackerKBind, nonce))
    }

    @Test
    fun `substituted child_ed25519_pub rejects (sig covers it)`() {
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!
        val swapped = b.copy(child_ed25519_pub = b64(ByteArray(32) { 9 }))
        assertFalse(ChildKeyBindingVerifier.verify(swapped, store.bindingSpki(), nonce))
    }

    @Test
    fun `substituted child_x25519_pub rejects (sig covers it)`() {
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!
        val swapped = b.copy(child_x25519_pub = b64(ByteArray(32) { 3 }))
        assertFalse(ChildKeyBindingVerifier.verify(swapped, store.bindingSpki(), nonce))
    }

    @Test
    fun `replayed binding with stale nonce rejects (freshness)`() {
        // A self-consistent binding from a prior attempt (signed over `nonce`) is presented in a new
        // attempt where the parent issued a different nonce ⇒ freshness check rejects.
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!
        val newAttemptNonce = ByteArray(32) { 1 }
        assertFalse(ChildKeyBindingVerifier.verify(b, store.bindingSpki(), newAttemptNonce))
    }

    @Test
    fun `tampered nonce field rejects (sig no longer covers body)`() {
        // Attacker rewrites the nonce field to the value the parent issued this attempt, but the sig
        // was made over the original nonce ⇒ canonical body differs ⇒ sig fails.
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!
        val parentIssued = ByteArray(32) { 5 }
        val tampered = b.copy(provisioning_nonce = b64(parentIssued))
        assertFalse(ChildKeyBindingVerifier.verify(tampered, store.bindingSpki(), parentIssued))
    }

    @Test
    fun `v != 1 rejects`() {
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!.copy(v = 2)
        assertFalse(ChildKeyBindingVerifier.verify(b, store.bindingSpki(), nonce))
    }

    @Test
    fun `empty sig rejects`() {
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!.copy(sig = "")
        assertFalse(ChildKeyBindingVerifier.verify(b, store.bindingSpki(), nonce))
    }

    @Test
    fun `short ed pub field rejects (ADR-025 D6 byte validation)`() {
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!.copy(child_ed25519_pub = b64(ByteArray(31)))
        assertFalse(ChildKeyBindingVerifier.verify(b, store.bindingSpki(), nonce))
    }

    @Test
    fun `malformed base64 ed pub rejects`() {
        val store = provisionedStore()
        val b = ChildKeyBindingSigner.bindingFor(nonce, store)!!.copy(child_ed25519_pub = "!!!not-base64!!!")
        assertFalse(ChildKeyBindingVerifier.verify(b, store.bindingSpki(), nonce))
    }
}
