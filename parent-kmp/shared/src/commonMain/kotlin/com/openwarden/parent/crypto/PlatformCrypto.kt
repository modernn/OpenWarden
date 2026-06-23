package com.openwarden.parent.crypto

/**
 * SHA-256 from the platform crypto provider (Bouncy Castle on JVM/Android — ADR-033 D3). Used by
 * [com.openwarden.parent.crypto.bip39.Bip39] for the checksum byte and by the root-key derivation
 * for the Argon2id domain-separation salt. The iOS `actual` is deferred (host-gated off; ADR-033).
 */
internal expect fun openwardenSha256(data: ByteArray): ByteArray
