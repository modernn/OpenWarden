package com.openwarden.parent.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The §7.2 response the **child** POSTs to the parent pairing endpoint after scanning the §7.1 QR
 * (PROTOCOL §7.2, as amended by ADR-032). The parent slice (b) endpoint (ADR-036) parses this off
 * untrusted LAN input, validates its shape + the D6 byte-level pubkey encoding, then hands it to
 * slice (c) for the §7.3 attestation + §7.4 SAS crypto.
 *
 * Encodings as they arrive on the wire (validated downstream, never trusted here):
 *  - [childEd25519Pub] / [childX25519Pub]: base64url(32), unpadded;
 *  - [childAttestationCertChain]: the StrongBox cert chain for `K_bind`, each cert base64(DER);
 *  - [childBindingSig]: hex ECDSA-P-256 by `K_bind` over JCS{v,child_ed25519_pub,child_x25519_pub,
 *    provisioning_nonce} (ADR-032 §7.3 check 4b).
 */
@Serializable
data class ChildPairingResponse(
    val v: Int,
    @SerialName("child_ed25519_pub") val childEd25519Pub: String,
    @SerialName("child_x25519_pub") val childX25519Pub: String,
    @SerialName("child_attestation_cert_chain") val childAttestationCertChain: List<String>,
    @SerialName("child_binding_sig") val childBindingSig: String,
) {
    companion object {
        // Tolerant of forward-compatible unknown fields (matching the child ApiServer inbound config),
        // but a missing required field still fails the decode -> the endpoint treats it as malformed.
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a §7.2 body. Returns `null` on any malformed JSON / missing field — never throws. */
        fun parse(body: String): ChildPairingResponse? =
            try {
                json.decodeFromString(serializer(), body)
            } catch (e: Exception) {
                null
            }
    }
}
