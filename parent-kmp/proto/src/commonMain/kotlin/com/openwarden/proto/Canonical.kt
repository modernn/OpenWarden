package com.openwarden.proto

/**
 * Deterministic JSON canonicalization (RFC 8785 JCS) — the signing input for
 * bundles and event entries (ADR-015: one signing rule = JCS of the object
 * without its `sig` field).
 *
 * SCAFFOLD: the full RFC 8785 serializer (lexicographically sorted keys, no
 * insignificant whitespace, ECMAScript number formatting) lands with PROTOCOL.md §3.
 * This object currently only enforces the ADR-017 integer bound so dependent code
 * can rely on it; it is NOT yet a conformant JCS encoder.
 */
object Canonical {
    /** 2^53 − 1: the largest integer RFC 8785 ECMAScript number formatting round-trips exactly (ADR-017). */
    const val MAX_JCS_SAFE_INTEGER: Long = 9_007_199_254_740_991L

    /** Fail-closed guard: reject any integer/timestamp outside the JCS-safe range before signing/verifying. */
    fun requireJcsSafe(value: Long) {
        require(value in 0..MAX_JCS_SAFE_INTEGER) {
            "integer $value outside JCS-safe range 0..2^53-1 (ADR-017)"
        }
    }
}
