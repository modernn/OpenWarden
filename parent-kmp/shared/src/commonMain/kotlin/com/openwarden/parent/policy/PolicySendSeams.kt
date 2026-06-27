package com.openwarden.parent.policy

/**
 * Parent-side monotonic policy_seq sequencer (ADR-034 D3 / PROTOCOL §5). The parent is the
 * authoritative monotonicity anchor: [reserveNext] durably persists and returns a strictly-greater
 * seq each call, starting at [GENESIS_FIRST_VALID_SEQ], and NEVER returns a value ≤ one already
 * issued. A reserved seq binds to exactly one signed bundle; gaps are fine (the child accepts any
 * `seq > floor` within `MAX_SEQ_JUMP`), seq reuse for *different content* is what this forbids.
 */
interface PolicySeqStore {
    /** Durably reserve + return the next strictly-greater policy_seq. Throws if the write fails. */
    fun reserveNext(): Long

    companion object {
        /** PROTOCOL §5.6 / `ReplayFloor.GENESIS_FIRST_VALID_SEQ` — seq 0 is reserved, never live. */
        const val GENESIS_FIRST_VALID_SEQ: Long = 1
    }
}

/**
 * The pinned child identity this parent commands (ADR-034 D4). `child_device_id` = base64url(child
 * Ed25519 pubkey), established at pairing (#23). Returns `null` until a child is paired; the sender
 * fails closed (refuses to send) when `null`.
 */
interface PairedChildStore {
    /** base64url child Ed25519 pubkey, or `null` if no child is paired yet. */
    fun pairedChildId(): String?
}

/** CSPRNG-fresh per-bundle nonce (PROTOCOL §2: 16 bytes, 32 hex chars). Seam for deterministic tests. */
interface NonceGenerator {
    /** 16 random bytes as 32 lowercase hex chars. */
    fun newNonceHex(): String
}

/** Sends a serialized signed bundle to the child `/policy` endpoint (ADR-034 D5). */
interface PolicyTransport {
    suspend fun postPolicy(bundleJson: String): PolicyPostResult
}

sealed interface PolicyPostResult {
    /** Child applied the bundle (200, `{"status":"applied","policy_seq"}`). */
    data class Applied(
        val policySeq: Long,
    ) : PolicyPostResult

    /** Child rejected (400, `{"error","reason"}`) — e.g. REGRESSION, EXPIRED, SIG_FAIL. */
    data class Rejected(
        val reason: String,
    ) : PolicyPostResult

    /** Network/transport failure — the same signed bundle may be retried (ADR-034 D3). */
    data class TransportError(
        val message: String,
    ) : PolicyPostResult
}

internal fun ByteArray.toHexLower(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
