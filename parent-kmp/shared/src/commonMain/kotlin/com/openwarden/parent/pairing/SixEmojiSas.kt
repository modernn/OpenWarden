package com.openwarden.parent.pairing

/**
 * HKDF-SHA256 seam for the §7.4 SAS derivation (ADR-038 D6). The real `androidMain` implementation is
 * `BouncyCastleSasKdf` (Bouncy Castle `HKDFBytesGenerator`, the ADR-033 host-testable precedent); host
 * tests inject a fake to drive [SixEmojiSas]'s mapping deterministically, and the real impl is checked
 * against an RFC 5869 KAT in `androidUnitTest`.
 */
fun interface SasKdf {
    /**
     * HKDF-SHA256 (RFC 5869): Extract(salt, ikm) then Expand(info) → [length] bytes.
     * @return exactly [length] bytes; implementations MUST NOT return short.
     */
    fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray
}

/**
 * The §7.4 six-emoji SAS core (ADR-025 D2a, ADR-038). Pure given a [SasKdf]: it concatenates the four
 * pinned public keys in the **fixed order** `parent_ed ‖ parent_x ‖ child_ed ‖ child_x` (D1), runs
 * HKDF-SHA256 with the canonical salt + the `provisioning_nonce` as `info` to 16 bytes, and maps the
 * output to six emojis via the pinned [SasEmojiTable] (D2/D3).
 *
 * Any substituted key — **including either X25519 key**, which §7.3 attestation does not bind — changes
 * the whole HKDF output and therefore the emojis, so the human compare catches the substitution (the
 * X25519-MITM gap D2a closes).
 *
 * Fail-closed: every input public key MUST be exactly 32 bytes; a wrong length throws (no SAS), never a
 * silent truncation that could collide with a different key.
 */
class SixEmojiSas(
    private val kdf: SasKdf,
) {
    /**
     * Derive the six-emoji SAS for one pairing attempt. [nonce] is the raw 32-byte `provisioning_nonce`.
     * Returns [SasEmojiTable.EMOJI_COUNT] emoji strings in display order.
     */
    fun derive(
        parentEd25519Pub: ByteArray,
        parentX25519Pub: ByteArray,
        childEd25519Pub: ByteArray,
        childX25519Pub: ByteArray,
        nonce: ByteArray,
    ): List<String> {
        requireKey(parentEd25519Pub, "parent_ed25519_pub")
        requireKey(parentX25519Pub, "parent_x25519_pub")
        requireKey(childEd25519Pub, "child_ed25519_pub")
        requireKey(childX25519Pub, "child_x25519_pub")

        // ADR-038 D1: fixed four-key order. A wrong order would derive a different (but still
        // deterministic) SAS, so the order is canon both peers share.
        val ikm = parentEd25519Pub + parentX25519Pub + childEd25519Pub + childX25519Pub
        val out = kdf.hkdfSha256(SALT, ikm, nonce, OUTPUT_BYTES)
        require(out.size >= OUTPUT_BYTES) { "SAS KDF returned ${out.size} bytes, need $OUTPUT_BYTES" }
        return SasEmojiTable.lookup(out)
    }

    private fun requireKey(
        key: ByteArray,
        name: String,
    ) = require(key.size == KEY_BYTES) { "$name MUST be exactly $KEY_BYTES bytes, got ${key.size}" }

    companion object {
        /** ADR-038 D1: the canonical HKDF Extract salt. */
        val SALT: ByteArray = "openwarden-pair-v1".encodeToByteArray()

        /** §7.4 / ADR-038 D1: HKDF output length. */
        const val OUTPUT_BYTES: Int = 16

        /** Each pinned public key is 32 bytes (Ed25519 / X25519). */
        const val KEY_BYTES: Int = 32
    }
}
