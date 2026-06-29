package com.openwarden.parent.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The parent's view of the child-emitted TLS-SPKI vouch (ADR-031 D2; the parent half deferred at D5/D7).
 * The child signs it with its Ed25519 **identity** key over the SHA-256 of its TLS leaf cert's SPKI; the
 * parent verifies it ([SpkiBindingVerifier]) against the identity key it pinned at pairing, to reject a
 * LAN MITM that spoofs `_openwarden._tcp` (red-team TR1). This is the exact wire shape the child-android
 * `SpkiAssertion` emits — byte-for-byte JCS agreement is required, so the wire keys are pinned by
 * [SerialName] to snake_case regardless of the camelCase property names.
 *
 *  - [spkiSha256] = base64url(SHA-256(DER SubjectPublicKeyInfo of the TLS leaf cert)) — RFC 7469
 *    SPKI-pin semantics (survives a cert renewal that keeps the same key);
 *  - [sig] = hex Ed25519 by the child identity key over the RFC 8785 JCS bytes of the object minus `sig`.
 *
 * Defaults exist only so a partial object still parses; an empty [spkiSha256] or [sig] can never verify
 * (fail-closed — see [SpkiBindingVerifier.verify]).
 */
@Serializable
data class SpkiAssertion(
    val v: Int,
    @SerialName("spki_sha256") val spkiSha256: String = "",
    val sig: String = "",
) {
    companion object {
        // Tolerant of forward-compatible unknown fields (matching the §7.2 ChildPairingResponse parse),
        // but a missing required field still fails the decode -> the caller treats it as malformed.
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a received assertion. Returns `null` on any malformed JSON / missing field — never throws. */
        fun parse(body: String): SpkiAssertion? =
            try {
                json.decodeFromString(serializer(), body)
            } catch (e: Exception) {
                null
            }
    }
}
