package com.openwarden.parent.crypto

/**
 * iOS root-key crypto is deferred (ADR-033 Consequences). The iOS targets are only registered on a
 * macOS host; this `actual` keeps the common API total while the iOS Argon2id/derivation is tracked
 * as a follow-up.
 */
internal actual fun openwardenSha256(data: ByteArray): ByteArray =
    throw NotImplementedError("iOS SHA-256 for root-key derivation is deferred — see ADR-033")
