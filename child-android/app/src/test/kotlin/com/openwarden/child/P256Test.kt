package com.openwarden.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the shared ECDSA-P-256 verify primitive [P256] (ADR-032) — round-trip plus the
 * fail-closed reject paths (empty / odd-length / garbage signature hex, wrong key, tampered message,
 * malformed SPKI). The signer is [P256TestSigner] (SunEC), matching the `K_bind` signing path.
 */
class P256Test {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `round-trip verifies`() {
        val kp = P256TestSigner.newKeypair()
        val msg = "openwarden-binding".toByteArray()
        val sigHex = P256TestSigner.signDer(msg, kp).hex()
        assertTrue(P256.verify(msg, sigHex, kp.spkiDer))
    }

    @Test
    fun `wrong key rejects`() {
        val kp = P256TestSigner.newKeypair()
        val other = P256TestSigner.newKeypair()
        val msg = "m".toByteArray()
        val sigHex = P256TestSigner.signDer(msg, kp).hex()
        assertFalse(P256.verify(msg, sigHex, other.spkiDer))
    }

    @Test
    fun `tampered message rejects`() {
        val kp = P256TestSigner.newKeypair()
        val sigHex = P256TestSigner.signDer("m".toByteArray(), kp).hex()
        assertFalse(P256.verify("M".toByteArray(), sigHex, kp.spkiDer))
    }

    @Test
    fun `empty sig rejects`() {
        val kp = P256TestSigner.newKeypair()
        assertFalse(P256.verify("m".toByteArray(), "", kp.spkiDer))
    }

    @Test
    fun `odd-length hex rejects`() {
        val kp = P256TestSigner.newKeypair()
        assertFalse(P256.verify("m".toByteArray(), "abc", kp.spkiDer))
    }

    @Test
    fun `non-hex sig rejects`() {
        val kp = P256TestSigner.newKeypair()
        assertFalse(P256.verify("m".toByteArray(), "zzzz", kp.spkiDer))
    }

    @Test
    fun `garbage DER sig rejects`() {
        val kp = P256TestSigner.newKeypair()
        assertFalse(P256.verify("m".toByteArray(), "deadbeef", kp.spkiDer))
    }

    @Test
    fun `malformed spki rejects`() {
        val kp = P256TestSigner.newKeypair()
        val sigHex = P256TestSigner.signDer("m".toByteArray(), kp).hex()
        assertFalse(P256.verify("m".toByteArray(), sigHex, byteArrayOf(1, 2, 3)))
    }
}
