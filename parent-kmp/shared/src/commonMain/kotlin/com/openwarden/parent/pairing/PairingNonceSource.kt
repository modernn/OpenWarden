package com.openwarden.parent.pairing

/**
 * Source of the single-use §7.1 `provisioning_nonce` (ADR-035 D2). Implementations MUST return
 * exactly 32 cryptographically-random bytes. The Android actual ([AndroidPairingNonceSource])
 * wraps `java.security.SecureRandom`; tests inject a deterministic fake.
 *
 * Deliberately distinct from `com.openwarden.parent.policy.NonceGenerator` (PROTOCOL §2 anti-replay
 * nonce — 16 bytes, hex): the pairing nonce is 32 bytes, base64url, and feeds the child's StrongBox
 * `setAttestationChallenge` (§7.2), so it has a different size and purpose.
 */
interface PairingNonceSource {
    /** Exactly 32 fresh cryptographically-random bytes. */
    fun freshNonce(): ByteArray
}
