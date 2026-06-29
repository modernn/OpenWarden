package com.openwarden.parent.pairing

/**
 * Platform seam (ADR-031 D7): verify a raw Ed25519 signature by the pinned child **identity** key.
 * The real `androidMain` impl is [com.openwarden.parent.pairing] `BouncyCastleEd25519Verifier`
 * (RFC 8032 via Bouncy Castle — the same curve the child's i2p/libsodium signer uses, proven
 * interoperable by the ADR-033 RFC KATs). Host tests inject a programmable boolean so
 * [SpkiBindingVerifier]'s accept/reject matrix is deterministic, and the real impl is exercised with a
 * BC twin signer in `androidUnitTest`.
 *
 * MUST return `false` — never throw — on any failure (malformed key, malformed signature, wrong
 * length), so a bad input fails closed.
 */
interface Ed25519Verifier {
    /**
     * @param message the bytes that were signed (the JCS canonical body, minus `sig`).
     * @param signature the raw 64-byte Ed25519 signature (already hex-decoded by the caller).
     * @param publicKey the pinned child identity key — raw 32 bytes.
     */
    fun verify(
        message: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray,
    ): Boolean
}
