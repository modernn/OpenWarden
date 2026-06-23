package com.openwarden.parent.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The §7.1 pairing QR payload the parent **displays** (ADR-025 D1, ADR-035 D8). The child scans it,
 * then POSTs its §7.2 response. The pubkeys and nonce are base64url(32), unpadded (43 chars each).
 *
 * There is deliberately **no `tls_spki` field** (ADR-025 D3): the invented field from the rejected
 * PR #64 inversion stays dead; the TLS sync channel is pinned against the pinned pubkey post-pairing
 * (ADR-031), not via an SPKI hash in the QR.
 */
@Serializable
data class PairingQrPayload(
    val v: Int,
    @SerialName("parent_ed25519_pub") val parentEd25519Pub: String,
    @SerialName("parent_x25519_pub") val parentX25519Pub: String,
    @SerialName("provisioning_nonce") val provisioningNonce: String,
    @SerialName("transport_hints") val transportHints: TransportHints,
)

/** §7.1 `transport_hints`. `iroh_ticket` is a v2 optional, omitted on the v0.3 LAN path. */
@Serializable
data class TransportHints(
    val mdns: String,
    @SerialName("iroh_ticket") val irohTicket: String? = null,
)

/** Stable JSON for the QR payload: `iroh_ticket` is omitted when null (kept off the wire). */
internal val pairingJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

/** Serialize to the §7.1 JSON string the QR encodes (field order matches the spec). */
fun PairingQrPayload.toJson(): String = pairingJson.encodeToString(this)
