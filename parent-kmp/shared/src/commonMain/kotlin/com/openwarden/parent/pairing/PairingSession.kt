package com.openwarden.parent.pairing

/**
 * One pending pairing attempt (ADR-035 D1/D3). Immutable snapshot holding the assembled §7.1 QR
 * [payloadJson], the raw 32-byte [nonce] (slices (c)/(d) bind the attestation challenge + SAS
 * against it), and the [createdAtMs]/[ttlMs] expiry window.
 *
 * In-memory only (ADR-035 D4): never serialized to disk. Lifecycle (single-active, expiry, burn) is
 * owned by [PairingSessionManager] — instances are created only by it.
 */
class PairingSession internal constructor(
    val payloadJson: String,
    private val nonceBytes: ByteArray,
    val createdAtMs: Long,
    val ttlMs: Long,
) {
    /** Defensive copy of the 32-byte nonce — the session's own copy is never handed out mutable. */
    fun nonce(): ByteArray = nonceBytes.copyOf()

    /** Expired once the full TTL has elapsed (boundary `nowMs == createdAtMs + ttlMs` is expired). */
    fun isExpired(nowMs: Long): Boolean = nowMs - createdAtMs >= ttlMs
}
