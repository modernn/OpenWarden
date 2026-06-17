package com.openwarden.parent.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire shape of the QR payload the child device displays after DPC provisioning.
 * The parent scans this QR to establish a pinned channel (PROTOCOL.md §7).
 *
 * Fields:
 * - [v]              schema version (must equal 1)
 * - [childEd25519Pub] base64url(32) — child Ed25519 identity pubkey
 * - [childX25519Pub]  base64url(32) — child X25519 pubkey (for sealed-box events)
 * - [tlsSpki]         base64url(32) — SHA-256 SPKI pin of the child TLS self-signed cert
 */
@Serializable
internal data class PairingQrPayload(
    val v: Int,
    @SerialName("child_ed25519_pub")
    val childEd25519Pub: String,
    @SerialName("child_x25519_pub")
    val childX25519Pub: String,
    @SerialName("tls_spki")
    val tlsSpki: String,
)

/** Typed result of attempting to parse a QR payload string. */
sealed class PairingParseResult {
    /** Payload was valid; [peer] holds the extracted identity material. */
    data class Success(val peer: PinnedPeer) : PairingParseResult()

    /**
     * Payload was rejected. Nothing is persisted.
     *
     * [reason] is a short human-readable diagnostic for logging/display only.
     * Fail-closed: on any error a [Failure] is returned so the caller cannot
     * silently proceed with a partial or missing peer identity (PROTOCOL.md §7,
     * CLAUDE.md fail-closed invariant).
     */
    data class Failure(val reason: String) : PairingParseResult()
}

/**
 * Pure parser for child pairing QR payloads (PROTOCOL.md §7).
 *
 * Responsibilities:
 * 1. Deserialize the JSON string.
 * 2. Validate schema version.
 * 3. Reject any payload with missing/blank identity fields.
 * 4. Return [PairingParseResult.Success] or [PairingParseResult.Failure].
 *
 * No crypto primitives, no key generation, no signing. This layer only
 * parses and validates the *presence* and basic *shape* of identity material.
 * Cryptographic attestation verification (§7.3) is out of scope here and
 * agent-blocked (handled by a human-reviewed crypto module).
 */
object PairingPayloadParser {
    /** The only accepted schema version. */
    private const val SUPPORTED_VERSION = 1

    /**
     * Minimum decoded byte length for a base64url-encoded 32-byte key (no padding).
     * base64url of 32 bytes = 43 chars (ceiling(32*4/3) with no padding).
     */
    private const val MIN_KEY_B64_LEN = 43

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse [raw] (the string decoded from a scanned QR code) into a [PairingParseResult].
     *
     * Returns [PairingParseResult.Failure] for ANY of:
     * - Not valid JSON
     * - [PairingQrPayload.v] != 1
     * - Any required key field is blank or too short to plausibly be 32 encoded bytes
     */
    fun parse(raw: String): PairingParseResult {
        if (raw.isBlank()) {
            return PairingParseResult.Failure("payload is blank")
        }

        val payload: PairingQrPayload = runCatching {
            json.decodeFromString(PairingQrPayload.serializer(), raw)
        }.getOrElse { e ->
            return PairingParseResult.Failure("JSON parse error: ${e.message}")
        }

        if (payload.v != SUPPORTED_VERSION) {
            return PairingParseResult.Failure(
                "unsupported schema version ${payload.v}; expected $SUPPORTED_VERSION",
            )
        }

        val fieldErrors = buildList {
            if (payload.childEd25519Pub.length < MIN_KEY_B64_LEN) {
                add("child_ed25519_pub too short (${payload.childEd25519Pub.length} < $MIN_KEY_B64_LEN)")
            }
            if (payload.childX25519Pub.length < MIN_KEY_B64_LEN) {
                add("child_x25519_pub too short (${payload.childX25519Pub.length} < $MIN_KEY_B64_LEN)")
            }
            if (payload.tlsSpki.length < MIN_KEY_B64_LEN) {
                add("tls_spki too short (${payload.tlsSpki.length} < $MIN_KEY_B64_LEN)")
            }
        }

        if (fieldErrors.isNotEmpty()) {
            return PairingParseResult.Failure("invalid fields: ${fieldErrors.joinToString("; ")}")
        }

        return PairingParseResult.Success(
            PinnedPeer(
                childEd25519Pub = payload.childEd25519Pub,
                childX25519Pub = payload.childX25519Pub,
                tlsSpki = payload.tlsSpki,
            ),
        )
    }
}
