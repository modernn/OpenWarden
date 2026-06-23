package com.openwarden.parent.crypto.bip39

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BIP-39 vectors (ADR-033 D5/D8). Runs on the host JVM (Bouncy Castle SHA-256 actual). The all-zeros
 * 256-bit vector is the canonical `abandon …(×23) art`.
 */
class Bip39Test {

    @Test
    fun allZeroEntropyVector() {
        val mnemonic = Bip39.encode(ByteArray(32))
        assertEquals(24, mnemonic.size)
        assertTrue(mnemonic.take(23).all { it == "abandon" }, "first 23 words must be 'abandon'")
        assertEquals("art", mnemonic[23], "all-zero 256-bit mnemonic ends in 'art'")
    }

    @Test
    fun roundTripArbitraryEntropy() {
        val entropy = ByteArray(32) { (it * 7 + 1).toByte() }
        val mnemonic = Bip39.encode(entropy)
        assertTrue(Bip39.isValid(mnemonic))
        assertContentEquals(entropy, Bip39.decode(mnemonic))
    }

    @Test
    fun roundTripAllOnes() {
        val entropy = ByteArray(32) { 0xFF.toByte() }
        assertContentEquals(entropy, Bip39.decode(Bip39.encode(entropy)))
    }

    @Test
    fun rejectsBadChecksum() {
        val mnemonic = Bip39.encode(ByteArray(32)).toMutableList()
        // Swap a middle word to a different valid word: entropy changes, stored checksum does not.
        mnemonic[5] = if (mnemonic[5] == "ability") "abandon" else "ability"
        assertFalse(Bip39.isValid(mnemonic))
        assertFailsWith<IllegalArgumentException> { Bip39.decode(mnemonic) }
    }

    @Test
    fun rejectsUnknownWord() {
        val mnemonic = Bip39.encode(ByteArray(32)).toMutableList()
        mnemonic[0] = "notabip39word"
        assertFailsWith<IllegalArgumentException> { Bip39.decode(mnemonic) }
    }

    @Test
    fun rejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> { Bip39.decode(List(12) { "abandon" }) }
    }

    @Test
    fun wordlistIsCanonicalSize() {
        // index 2047 ("zoo") must round-trip — proves the full 2048-word list parsed.
        val entropy = ByteArray(32) { 0xFF.toByte() }
        val mnemonic = Bip39.encode(entropy)
        assertEquals("zoo", mnemonic[0]) // all-ones first 11 bits = index 2047 = "zoo"
    }
}
