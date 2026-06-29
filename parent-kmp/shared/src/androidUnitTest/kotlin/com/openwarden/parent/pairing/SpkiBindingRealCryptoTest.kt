package com.openwarden.parent.pairing

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real-crypto proof for the parent-side ADR-031 D7 verifier: a [SpkiAssertion] signed with a genuine
 * RFC 8032 **Bouncy Castle** Ed25519 key is accepted by [SpkiBindingVerifier] + [BouncyCastleEd25519Verifier],
 * and the spoof / wrong-key / tampered-signature cases are rejected with real crypto. Because the child
 * signs the byte-identical JCS body with i2p/libsodium Ed25519 (BC ≡ that, ADR-033 KATs), a genuine
 * child-emitted assertion verifies here — this is the parent↔child interop the port exists to guarantee.
 *
 * Keys are derived from fixed bytes (no RNG) so the test is fully deterministic.
 */
class SpkiBindingRealCryptoTest {
    private val certA = "leaf-cert-A-spki-der".encodeToByteArray()
    private val certB = "leaf-cert-B-spki-der".encodeToByteArray()

    private val childPriv = Ed25519PrivateKeyParameters(ByteArray(32) { 0x11 }, 0)
    private val childPub = childPriv.generatePublicKey().encoded // raw 32 bytes — the pinned identity key
    private val otherPub = Ed25519PrivateKeyParameters(ByteArray(32) { 0x22 }, 0).generatePublicKey().encoded

    private val verifier = SpkiBindingVerifier(BouncyCastleEd25519Verifier())

    /** Sign the JCS canonical body of [assertion] with the child key and return it with `sig` populated. */
    private fun signedFor(spki: ByteArray): SpkiAssertion {
        val unsigned = SpkiAssertion(v = 1, spkiSha256 = SpkiBindingVerifier.spkiSha256(spki))
        val sig =
            Ed25519Signer()
                .apply {
                    init(true, childPriv)
                    val body = SpkiBindingVerifier.canonicalBody(unsigned)
                    update(body, 0, body.size)
                }.generateSignature()
        return unsigned.copy(sig = sig.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun genuineRealSignatureVerifies() {
        assertTrue(
            verifier.verify(signedFor(certA), certA, childPub),
            "a real Ed25519-signed assertion for the presented cert verifies against the pinned key",
        )
    }

    @Test
    fun spoofedResponderPresentingADifferentCertRejects() {
        // The assertion is genuinely signed (for certA), but the wire presents certB ⇒ reject (check 3).
        assertFalse(verifier.verify(signedFor(certA), certB, childPub))
    }

    @Test
    fun assertionSignedByADifferentKeyRejects() {
        // The signature is real but by the child key; the parent pins a DIFFERENT identity key ⇒ reject.
        assertFalse(verifier.verify(signedFor(certA), certA, otherPub))
    }

    @Test
    fun tamperedSignatureRejects() {
        val good = signedFor(certA)
        // Flip the last hex nibble of the signature — still well-formed 64-byte hex, but no longer valid.
        val bytes = good.sig.dropLast(1) + if (good.sig.last() == '0') '1' else '0'
        assertFalse(verifier.verify(good.copy(sig = bytes), certA, childPub))
    }
}
