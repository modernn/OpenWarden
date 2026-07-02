package com.openwarden.child

import org.junit.Test
import java.security.cert.X509Certificate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The ephemeral self-signed TLS leaf minted for the child's LAN HTTPS control channel (ADR-031 D8).
 * Pure JVM (BouncyCastle is host-portable) — no device / Robolectric needed, which is the whole reason
 * D8 chose a BC-software leaf over an AndroidKeyStore one.
 */
class TlsLeafTest {
    @Test
    fun `generate produces a keystore entry whose SPKI matches the advertised spkiDer`() {
        val leaf = TlsLeaf.generate()
        val cert = leaf.keyStore.getCertificate(leaf.alias) as X509Certificate
        // spkiDer is exactly the DER SubjectPublicKeyInfo — what the parent pins and what it extracts
        // from the negotiated leaf of a completed handshake (ADR-031 D2 / D5 carry-forward).
        assertTrue(leaf.spkiDer.isNotEmpty())
        assertTrue(cert.publicKey.encoded.contentEquals(leaf.spkiDer))
        // The keystore holds a usable private-key entry for the leaf (so sslConnector can serve it).
        assertTrue(leaf.keyStore.isKeyEntry(leaf.alias))
        assertNotEquals(null, leaf.keyStore.getKey(leaf.alias, leaf.password))
    }

    @Test
    fun `spkiDer is a real SPKI the pin primitive can hash`() {
        val leaf = TlsLeaf.generate()
        // DER SubjectPublicKeyInfo starts with a SEQUENCE tag (0x30).
        assertEquals(0x30.toByte(), leaf.spkiDer[0])
        // The RFC 7469 pin over it is a 43-char base64url-no-pad SHA-256.
        val pin = SpkiBinding.spkiSha256(leaf.spkiDer)
        assertEquals(43, pin.length)
        assertFalse(pin.contains('='))
    }

    @Test
    fun `each leaf is ephemeral - two generations differ`() {
        // The pin's trust anchor is the identity key, never the leaf, so a fresh per-start leaf is fine.
        assertFalse(TlsLeaf.generate().spkiDer.contentEquals(TlsLeaf.generate().spkiDer))
    }
}
