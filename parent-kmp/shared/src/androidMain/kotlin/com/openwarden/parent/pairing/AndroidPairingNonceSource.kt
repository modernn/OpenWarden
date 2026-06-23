package com.openwarden.parent.pairing

import java.security.SecureRandom

/**
 * [PairingNonceSource] backed by the platform CSPRNG (ADR-035 D2): 32 fresh `SecureRandom` bytes.
 * Mirrors `com.openwarden.parent.policy.SecureRandomNonceGenerator` (which mints the 16-byte §2
 * anti-replay nonce) — this one mints the 32-byte §7.1 `provisioning_nonce`.
 */
class AndroidPairingNonceSource(
    private val random: SecureRandom = SecureRandom(),
) : PairingNonceSource {
    override fun freshNonce(): ByteArray = ByteArray(32).also(random::nextBytes)
}
