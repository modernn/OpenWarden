package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Real-crypto host tests (ADR-038 test plan, issue #97) — run on the host JVM via `androidUnitTest`
 * (the ADR-033/037 precedent), no device, no libsodium:
 *  - [BouncyCastleSasKdf] reproduces the RFC 5869 HKDF-SHA256 KAT (pins Extract/Expand param ordering);
 *  - the §7.4 four-key SAS produces a frozen golden six-emoji sequence (independently computed);
 *  - substituting **each** of the four pinned keys — incl. **both** X25519 keys — changes the emojis
 *    (the executable regression for the X25519-substitution MITM that ADR-025 D2a closes).
 */
class SixEmojiSasRealKdfTest {
    private val kdf = BouncyCastleSasKdf()
    private val sas = SixEmojiSas(kdf)

    private fun hex(b: ByteArray) = b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun fromHex(s: String) = ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    // ---- RFC 5869 Appendix A.1 Test Case 1: locks the BC (ikm, salt, info) ordering --------------

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm =
            kdf.hkdfSha256(
                salt = fromHex("000102030405060708090a0b0c"),
                ikm = ByteArray(22) { 0x0b },
                info = fromHex("f0f1f2f3f4f5f6f7f8f9"),
                length = 42,
            )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
            "BouncyCastleSasKdf MUST reproduce the RFC 5869 TC1 OKM (Extract-then-Expand, salt-not-ikm-first)",
        )
    }

    // ---- §7.4 golden vector (docs/test-vectors/pairing/pair-09-sas-kat) --------------------------

    private val parentEd = ByteArray(32) { 1 }
    private val parentX = ByteArray(32) { 2 }
    private val childEd = ByteArray(32) { 3 }
    private val childX = ByteArray(32) { 4 }
    private val nonce = ByteArray(32) { 5 }

    @Test
    fun sasGoldenVector() {
        // Independently computed (Python hmac/HKDF): out16 = 63b98004f20f7ed1c6f52a51d421740a
        // -> first-36-bit indices [24, 59, 38, 0, 1, 15] -> Apple, Bell, Hourglass, Dog, Cat, Flower.
        assertEquals(
            "63b98004f20f7ed1c6f52a51d421740a",
            hex(kdf.hkdfSha256(SixEmojiSas.SALT, parentEd + parentX + childEd + childX, nonce, 16)),
            "the 16-byte HKDF output for the golden inputs is frozen canon",
        )
        // Pinned as LITERAL glyphs (NOT via SasEmojiTable) so a table reorder/drift fails this test —
        // the golden vector must be independent of the SUT's own table (Codex review #2).
        val expected = listOf("🍎", "🔔", "⌛", "🐶", "🐱", "🌷")
        assertContentEquals(expected, sas.derive(parentEd, parentX, childEd, childX, nonce), "golden six-emoji SAS")
    }

    // ---- four-key substitution regressions (ADR-025 D2a) ----------------------------------------

    @Test
    fun substitutingAnyOfTheFourKeysChangesTheSas() {
        val base = sas.derive(parentEd, parentX, childEd, childX, nonce)

        // flip one byte of each key in turn; every case MUST change the six emojis.
        val pe2 = parentEd.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val px2 = parentX.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val ce2 = childEd.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val cx2 = childX.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertNotEquals(base, sas.derive(pe2, parentX, childEd, childX, nonce), "parent_ed25519_pub swap MUST change the SAS")
        assertNotEquals(base, sas.derive(parentEd, px2, childEd, childX, nonce), "parent_x25519_pub swap MUST change the SAS (D2a)")
        assertNotEquals(base, sas.derive(parentEd, parentX, ce2, childX, nonce), "child_ed25519_pub swap MUST change the SAS")
        assertNotEquals(
            base,
            sas.derive(parentEd, parentX, childEd, cx2, nonce),
            "child_x25519_pub swap MUST change the SAS (D2a — the X25519 MITM gap)",
        )
    }

    @Test
    fun nonceBindsTheSas() {
        val base = sas.derive(parentEd, parentX, childEd, childX, nonce)
        val nonce2 = nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNotEquals(base, sas.derive(parentEd, parentX, childEd, childX, nonce2), "a different provisioning_nonce MUST change the SAS")
    }

    // ---- both sides agree; key order is load-bearing --------------------------------------------

    @Test
    fun derivationIsDeterministicAndOrderSensitive() {
        // Both peers hold all four keys post-handshake and apply the same fixed order -> identical SAS.
        assertContentEquals(
            sas.derive(parentEd, parentX, childEd, childX, nonce),
            sas.derive(parentEd, parentX, childEd, childX, nonce),
            "same inputs -> same SAS on both sides",
        )
        // Swapping the Ed positions (parent<->child) is a different IKM -> different SAS: order is canon.
        assertNotEquals(
            sas.derive(parentEd, parentX, childEd, childX, nonce),
            sas.derive(childEd, parentX, parentEd, childX, nonce),
            "the four-key order is load-bearing (D1)",
        )
    }

    @Test
    fun goldenEmojisAreActualMatrixGlyphs() {
        // Guard the table didn't drift: index 24/59/38/0/1/15 are Apple/Bell/Hourglass/Dog/Cat/Flower.
        assertTrue(SasEmojiTable.EMOJIS[24] == "🍎" && SasEmojiTable.EMOJIS[0] == "🐶" && SasEmojiTable.EMOJIS[63] == "📌")
    }
}
