package com.openwarden.parent.pairing

/**
 * A successfully parsed and persisted child peer identity.
 *
 * Populated by [PairingPayloadParser] after scanning a valid child pairing QR.
 * The parent pins [childEd25519Pub] (policy-bundle audience binding) and
 * [childX25519Pub] (sealed-box events) and [tlsSpki] (TLS certificate pin for
 * the mDNS transport channel). PROTOCOL.md §7.
 *
 * No crypto is generated here — pubkeys are consumed from the QR payload.
 */
data class PinnedPeer(
    /** Base64url-encoded 32-byte child Ed25519 public key. */
    val childEd25519Pub: String,
    /** Base64url-encoded 32-byte child X25519 public key. */
    val childX25519Pub: String,
    /** Base64url-encoded 32-byte TLS SPKI pin for the child's self-signed cert. */
    val tlsSpki: String,
)
