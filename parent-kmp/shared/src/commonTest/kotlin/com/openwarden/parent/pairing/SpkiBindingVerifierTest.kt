package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic, seam-injected accept/reject matrix for the parent-side ADR-031 D2/D7 verifier (issue
 * #21). With a fake [Ed25519Verifier] (programmable boolean + arg capture), every branch is driven to
 * accept and to reject — no native dep, no real key. The SHA-256 / base64url / JCS canonicalization are
 * exercised for real (they are pure), so the golden vector + canonical body shape pin **byte-for-byte
 * agreement with the child** (`child-android` `SpkiBinding`), which is the whole point of porting it.
 */
class SpkiBindingVerifierTest {
    // Same opaque "TLS leaf cert SubjectPublicKeyInfo DER" blobs the child test uses, so the golden
    // SHA-256 vector below is identical on both sides. The real cert lands with the deferred socket.
    private val certA = "leaf-cert-A-spki-der".encodeToByteArray()
    private val certB = "leaf-cert-B-spki-der".encodeToByteArray()
    private val pinnedKey = ByteArray(32) { 7 }
    private val validSigHex = "ab".repeat(64) // 64-byte hex; the fake decides accept/reject, not its bytes

    private class FakeEd25519(
        private val result: Boolean,
    ) : Ed25519Verifier {
        var calls = 0
            private set
        var lastMessage: ByteArray? = null
            private set
        var lastKey: ByteArray? = null
            private set

        override fun verify(
            message: ByteArray,
            signature: ByteArray,
            publicKey: ByteArray,
        ): Boolean {
            calls += 1
            lastMessage = message
            lastKey = publicKey
            return result
        }
    }

    private fun assertion(
        v: Int = 1,
        spki: ByteArray = certA,
        sig: String = validSigHex,
    ) = SpkiAssertion(v = v, spkiSha256 = SpkiBindingVerifier.spkiSha256(spki), sig = sig)

    // ---- accept ---------------------------------------------------------------------------------

    @Test
    fun genuineAssertionForPresentedCertVerifies() {
        val fake = FakeEd25519(true)
        val ok = SpkiBindingVerifier(fake).verify(assertion(), certA, pinnedKey)
        assertTrue(ok, "genuine assertion + matching cert + good signature ⇒ accept")
        assertEquals(1, fake.calls, "the Ed25519 seam is reached for the final check")
        assertTrue(
            fake.lastMessage!!.contentEquals(SpkiBindingVerifier.canonicalBody(assertion())),
            "the seam is handed exactly the JCS canonical body",
        )
        assertTrue(fake.lastKey!!.contentEquals(pinnedKey), "the seam verifies against the pinned identity key")
    }

    // ---- reject matrix --------------------------------------------------------------------------

    @Test
    fun noPinnedIdentityKeyRejects() {
        val fake = FakeEd25519(true)
        assertFalse(SpkiBindingVerifier(fake).verify(assertion(), certA, null), "no trust anchor ⇒ reject")
        assertEquals(0, fake.calls, "rejected before the signature check")
    }

    @Test
    fun wrongVersionRejectsBeforeSignature() {
        val fake = FakeEd25519(true)
        assertFalse(SpkiBindingVerifier(fake).verify(assertion(v = 2), certA, pinnedKey))
        assertEquals(0, fake.calls)
    }

    @Test
    fun emptySpkiHashRejects() {
        val fake = FakeEd25519(true)
        assertFalse(SpkiBindingVerifier(fake).verify(assertion().copy(spkiSha256 = ""), certA, pinnedKey))
        assertEquals(0, fake.calls)
    }

    @Test
    fun spoofedResponderPresentingADifferentCertRejects() {
        // A LAN MITM replays the genuine child's signed assertion but presents its OWN TLS cert: the
        // assertion vouches for certA, the wire cert is certB ⇒ reject (binds to the presented cert).
        val fake = FakeEd25519(true)
        assertFalse(SpkiBindingVerifier(fake).verify(assertion(spki = certA), certB, pinnedKey))
        assertEquals(0, fake.calls, "cert mismatch short-circuits before the signature check")
    }

    @Test
    fun emptySignatureRejects() {
        val fake = FakeEd25519(true)
        assertFalse(SpkiBindingVerifier(fake).verify(assertion(sig = ""), certA, pinnedKey))
        assertEquals(0, fake.calls)
    }

    @Test
    fun malformedSignatureHexRejectsFailClosed() {
        val fake = FakeEd25519(true)
        assertFalse(SpkiBindingVerifier(fake).verify(assertion(sig = "zz"), certA, pinnedKey), "non-hex nibble")
        assertFalse(SpkiBindingVerifier(fake).verify(assertion(sig = "abc"), certA, pinnedKey), "odd-length hex")
        assertEquals(0, fake.calls, "a malformed signature never reaches the seam")
    }

    @Test
    fun signatureRejectedByTheSeamRejects() {
        // Everything well-formed, but the Ed25519 seam says the signature does not verify against the
        // pinned key (an attacker who signed with a different identity key).
        val fake = FakeEd25519(false)
        assertFalse(SpkiBindingVerifier(fake).verify(assertion(), certA, pinnedKey))
        assertEquals(1, fake.calls, "the seam is consulted and its `false` is honored")
    }

    @Test
    fun nonBase64OrWrongLengthPinRejects() {
        val fake = FakeEd25519(true)
        // Not base64url at all → decode fails → reject before the signature check.
        assertFalse(SpkiBindingVerifier(fake).verify(assertion().copy(spkiSha256 = "!!! not b64 !!!"), certA, pinnedKey))
        // Valid base64url but a 3-byte value, not a 32-byte SHA-256 → reject on the length check.
        val shortPin = Base64Url.encode(byteArrayOf(1, 2, 3))
        assertFalse(SpkiBindingVerifier(fake).verify(assertion().copy(spkiSha256 = shortPin), certA, pinnedKey))
        assertEquals(0, fake.calls)
    }

    // ---- parent↔child byte agreement (the reason this is ported) ---------------------------------

    @Test
    fun spkiSha256IsTheRfc7469Base64UrlVectorSharedWithTheChild() {
        // Golden = base64url-no-pad(SHA-256("leaf-cert-A-spki-der")) — the SAME literal asserted in the
        // child's SpkiBindingTest, so a divergence in digest/encoding on either side is caught here.
        val h = SpkiBindingVerifier.spkiSha256(certA)
        assertEquals("uVeFXpFKx6o3rRkj0XMLu4S2n-ZwKkywc64azFXdb-0", h)
        assertEquals(43, h.length) // 32-byte digest, base64url no padding
        assertFalse(h.contains('=') || h.contains('+') || h.contains('/'), "url-safe, unpadded")
    }

    @Test
    fun canonicalBodyShapeMatchesTheChildJcs() {
        val a = SpkiAssertion(v = 1, spkiSha256 = SpkiBindingVerifier.spkiSha256(certA))
        val body = SpkiBindingVerifier.canonicalBody(a).decodeToString()
        assertEquals("{\"spki_sha256\":\"${a.spkiSha256}\",\"v\":1}", body)
    }

    @Test
    fun parseRejectsMalformedJsonFailClosed() {
        assertEquals(null, SpkiAssertion.parse("{ not json"))
        assertEquals(null, SpkiAssertion.parse("{\"spki_sha256\":\"x\"}"), "missing required `v` ⇒ reject")
    }
}
