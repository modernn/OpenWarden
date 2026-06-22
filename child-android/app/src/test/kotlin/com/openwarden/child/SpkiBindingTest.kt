package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TLS SPKI ↔ child-identity binding (ADR-031 D2). This is the logic the PARENT runs to accept-or-reject
 * a presented TLS cert; proving it deterministically here is the acceptance for issue #21
 * ("a spoofed `_openwarden._tcp` responder without the pinned SPKI/identity is rejected"). Fail-closed
 * on every missing / mismatched / malformed input.
 */
class SpkiBindingTest {

    // Two distinct "TLS leaf cert SubjectPublicKeyInfo DER" blobs. Opaque bytes — the real cert lands
    // with the deferred TLS socket (ADR-031 D5); the binding only ever hashes the presented SPKI.
    private val certA = "leaf-cert-A-spki-der".toByteArray()
    private val certB = "leaf-cert-B-spki-der".toByteArray()

    private fun assertionFor(provider: IdentityKeyProvider, spki: ByteArray) =
        SpkiBindingSigner.assertFor(spki, provider)

    @Test
    fun `genuine assertion for the presented cert verifies`() {
        val p = FakeIdentityKeyProvider.withNewKey()
        val a = assertionFor(p, certA)!!
        assertTrue(SpkiBinding.verify(a, certA, p.identityPublicKey()))
    }

    @Test
    fun `no pinned identity key rejects (pre-pairing, no trust anchor)`() {
        val p = FakeIdentityKeyProvider.withNewKey()
        val a = assertionFor(p, certA)!!
        assertFalse(SpkiBinding.verify(a, certA, null))
    }

    @Test
    fun `wrong version rejects before signature`() {
        val p = FakeIdentityKeyProvider.withNewKey()
        val a = assertionFor(p, certA)!!
        assertFalse(SpkiBinding.verify(a.copy(v = 2), certA, p.identityPublicKey()))
    }

    @Test
    fun `empty spki hash rejects`() {
        val p = FakeIdentityKeyProvider.withNewKey()
        val a = assertionFor(p, certA)!!
        assertFalse(SpkiBinding.verify(a.copy(spki_sha256 = ""), certA, p.identityPublicKey()))
    }

    @Test
    fun `spoofed responder presenting a different cert rejects (the acceptance)`() {
        // A LAN MITM replays the genuine child's signed assertion but presents its OWN TLS cert.
        // The assertion vouches for certA; the presented cert is certB ⇒ reject (binds to the cert
        // actually on the wire, ADR-031 D2 check 3).
        val p = FakeIdentityKeyProvider.withNewKey()
        val a = assertionFor(p, certA)!!
        assertFalse(SpkiBinding.verify(a, certB, p.identityPublicKey()))
    }

    @Test
    fun `empty signature rejects`() {
        val p = FakeIdentityKeyProvider.withNewKey()
        val a = assertionFor(p, certA)!!
        assertFalse(SpkiBinding.verify(a.copy(sig = ""), certA, p.identityPublicKey()))
    }

    @Test
    fun `malformed signature hex rejects fail-closed`() {
        val p = FakeIdentityKeyProvider.withNewKey()
        val a = assertionFor(p, certA)!!
        assertFalse(SpkiBinding.verify(a.copy(sig = "zz"), certA, p.identityPublicKey()))
    }

    @Test
    fun `assertion signed by a different identity key rejects`() {
        val genuine = FakeIdentityKeyProvider.withNewKey()
        val attacker = FakeIdentityKeyProvider.withNewKey()
        // Attacker signs an assertion for certA with ITS identity key, but the parent pins the GENUINE
        // child identity key ⇒ reject (ADR-031 D2 check 4 — attacker lacks the child private key).
        val aAttacker = assertionFor(attacker, certA)!!
        assertFalse(SpkiBinding.verify(aAttacker, certA, genuine.identityPublicKey()))
    }

    @Test
    fun `cross-replay - an assertion valid for cert A is rejected when presenting cert B`() {
        val p = FakeIdentityKeyProvider.withNewKey()
        val aForA = assertionFor(p, certA)!!
        assertTrue(SpkiBinding.verify(aForA, certA, p.identityPublicKey()))
        assertFalse(SpkiBinding.verify(aForA, certB, p.identityPublicKey()))
    }

    @Test
    fun `signer produces no assertion before pairing (fail-closed)`() {
        assertNull(SpkiBindingSigner.assertFor(certA, FakeIdentityKeyProvider.notProvisioned()))
        assertNull(SpkiBindingSigner.assertFor(certA, NotProvisionedIdentityKeyProvider))
    }

    @Test
    fun `spki hash is RFC 7469 base64url SHA-256 - deterministic, unpadded, url-safe`() {
        val h = SpkiBinding.spkiSha256(certA)
        assertEquals(SpkiBinding.spkiSha256(certA), h) // deterministic
        assertEquals(43, h.length)                     // 32-byte digest, base64url no padding
        assertFalse(h.contains('='))                   // no padding
        assertFalse(h.contains('+') || h.contains('/')) // url-safe alphabet
    }
}
