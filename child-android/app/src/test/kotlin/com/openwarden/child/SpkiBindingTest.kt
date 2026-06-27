package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TLS SPKI ↔ child-identity binding (ADR-031 D2). This is the logic the PARENT runs to accept-or-reject
 * a presented TLS cert; proving it deterministically here is the **verifier-logic** acceptance for
 * issue #21 ("a spoofed `_openwarden._tcp` responder without the pinned SPKI/identity is rejected") —
 * the *live* spoof-rejection E2E is gated on the deferred parent-side socket (ADR-031 D5). Fail-closed
 * on every missing / mismatched / malformed input.
 */
class SpkiBindingTest {
    // Two distinct "TLS leaf cert SubjectPublicKeyInfo DER" blobs. Opaque bytes — the real cert lands
    // with the deferred TLS socket (ADR-031 D5); the binding only ever hashes the presented SPKI.
    private val certA = "leaf-cert-A-spki-der".toByteArray()
    private val certB = "leaf-cert-B-spki-der".toByteArray()

    private fun assertionFor(
        provider: IdentityKeyProvider,
        spki: ByteArray,
    ) = SpkiBindingSigner.assertFor(spki, provider)

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
    fun `non-base64 or wrong-length spki hash rejects (ADR-025 D6 byte validation)`() {
        val p = FakeIdentityKeyProvider.withNewKey()
        val a = assertionFor(p, certA)!!
        // Not base64url at all → decode throws → reject (before the signature check).
        assertFalse(SpkiBinding.verify(a.copy(spki_sha256 = "!!! not base64 !!!"), certA, p.identityPublicKey()))
        // Valid base64url but not a 32-byte digest (3 bytes) → reject on the length check.
        val shortPin =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(byteArrayOf(1, 2, 3))
        assertFalse(SpkiBinding.verify(a.copy(spki_sha256 = shortPin), certA, p.identityPublicKey()))
    }

    @Test
    fun `spki hash is the RFC 7469 base64url SHA-256 of the input (golden vector)`() {
        // Golden = base64url-no-pad(SHA-256("leaf-cert-A-spki-der")). Hardcoded so a regression in the
        // encoding (e.g. switching to standard base64 or hex) or digest is caught — not a tautology.
        val h = SpkiBinding.spkiSha256(certA)
        assertEquals("uVeFXpFKx6o3rRkj0XMLu4S2n-ZwKkywc64azFXdb-0", h)
        assertEquals(43, h.length) // 32-byte digest, base64url no padding
        assertFalse(h.contains('=')) // no padding
        assertFalse(h.contains('+') || h.contains('/')) // url-safe alphabet
    }

    @Test
    fun `spki assertion canonical body is disjoint from the parent-signed wire objects (domain separation)`() {
        // ADR-031 D2: separation is by disjoint JCS object shape (and, independently, by signing key —
        // child vs parent). Lock the shape: the SpkiAssertion canonical body has key-set
        // {spki_sha256, v} and never collides with a SignedCommand body, so an Ed25519 signature over
        // one can never canonicalize to — and thus verify as — the other. Guards crypto-review F2: if a
        // future child-key-signed object collides with this shape, this test fails.
        val a = SpkiAssertion(v = 1, spki_sha256 = SpkiBinding.spkiSha256(certA))
        val aBody = String(SpkiBinding.canonicalBody(a))
        assertEquals("{\"spki_sha256\":\"${a.spki_sha256}\",\"v\":1}", aBody)
        val cmdBody =
            String(
                CommandVerifier.canonicalBody(
                    SignedCommand(v = 1, type = "lock", child_device_id = "child-abcd", issued_at = 1),
                ),
            )
        assertFalse(aBody == cmdBody)
    }
}
