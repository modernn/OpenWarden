package com.openwarden.parent.crypto.bip39

import com.openwarden.parent.crypto.openwardenSha256

/**
 * BIP-39 mnemonic encoding for the parent recovery phrase (ADR-033 D5). v1 supports the
 * 24-word / 256-bit-entropy configuration only (CLAUDE.md invariant). Pure-Kotlin bit-packing;
 * the one SHA-256 it needs (the checksum byte) comes from the platform provider.
 */
object Bip39 {
    const val ENTROPY_BYTES = 32 // 256-bit entropy
    const val WORD_COUNT = 24

    private val wordlist = BIP39_ENGLISH_WORDLIST
    private val wordIndex: Map<String, Int> = wordlist.withIndex().associate { (i, w) -> w to i }

    /** Encode 256-bit [entropy] to a 24-word mnemonic (entropy + 8-bit SHA-256 checksum). */
    fun encode(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "entropy must be $ENTROPY_BYTES bytes" }
        val checksum = openwardenSha256(entropy)[0] // first 8 checksum bits for 256-bit entropy
        val bits = ArrayList<Boolean>(ENTROPY_BYTES * 8 + 8)
        for (b in entropy) for (i in 7 downTo 0) bits.add((b.toInt() shr i) and 1 == 1)
        for (i in 7 downTo 0) bits.add((checksum.toInt() shr i) and 1 == 1)
        return (0 until WORD_COUNT).map { group ->
            var idx = 0
            for (j in 0 until 11) idx = (idx shl 1) or (if (bits[group * 11 + j]) 1 else 0)
            wordlist[idx]
        }
    }

    /** True iff [mnemonic] is a valid 24-word BIP-39 phrase (known words + checksum). */
    fun isValid(mnemonic: List<String>): Boolean = runCatching { decode(mnemonic) }.isSuccess

    /**
     * Decode a 24-word [mnemonic] back to its 256-bit entropy, verifying the checksum. Throws
     * [IllegalArgumentException] on unknown word, wrong length, or bad checksum (fail-closed).
     */
    fun decode(mnemonic: List<String>): ByteArray {
        require(mnemonic.size == WORD_COUNT) { "mnemonic must be $WORD_COUNT words" }
        val bits = ArrayList<Boolean>(WORD_COUNT * 11)
        for (word in mnemonic) {
            val idx = wordIndex[word] ?: throw IllegalArgumentException("unknown BIP-39 word")
            for (j in 10 downTo 0) bits.add((idx shr j) and 1 == 1)
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BYTES) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            entropy[i] = v.toByte()
        }
        val expected = openwardenSha256(entropy)[0].toInt() and 0xFF
        var actual = 0
        for (j in 0 until 8) actual = (actual shl 1) or (if (bits[ENTROPY_BYTES * 8 + j]) 1 else 0)
        require(actual == expected) { "BIP-39 checksum mismatch" }
        return entropy
    }
}
