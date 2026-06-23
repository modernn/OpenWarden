package com.openwarden.parent.policy

import java.security.SecureRandom

/** [NonceGenerator] backed by the platform CSPRNG (ADR-034 D1 / PROTOCOL §2: 16 bytes → 32 hex). */
class SecureRandomNonceGenerator(
    private val random: SecureRandom = SecureRandom(),
) : NonceGenerator {
    override fun newNonceHex(): String = ByteArray(16).also(random::nextBytes).toHexLower()
}
