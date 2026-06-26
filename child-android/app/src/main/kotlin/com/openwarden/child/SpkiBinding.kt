package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Binds a TLS leaf certificate's SPKI to the child Ed25519 **identity** key (ADR-031 D2). This is the
 * provenance that closes red-team TR1 and makes "HTTPS with self-signed cert pinned post-pairing"
 * (PROTOCOL §4) safe **without** a trust-on-first-use window.
 *
 * [verify] is the decision the **parent** runs to accept-or-reject a presented TLS cert; it is shipped
 * here, child-side, as the single shared primitive (and as the acceptance test for issue #21 —
 * "a spoofed `_openwarden._tcp` responder without the pinned SPKI/identity is rejected"). TLS itself is
 * confidentiality-only: this binding never authenticates a *message*; every message is independently
 * admitted by its parent-signed app-layer check (ADR-030/-031 D1), regardless of transport.
 *
 * Fail-closed throughout: any missing, mismatched, or malformed input returns `false`.
 */
object SpkiBinding {
    // Identical canonical-JSON rules as CommandVerifier/BundleVerifier/HeartbeatVerifier so the one
    // signing rule never diverges: encodeDefaults so defaulted fields are signed; omit nulls.
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    /**
     * RFC 7469 SPKI pin: base64url(SHA-256([spkiDer])), where [spkiDer] is the DER-encoded
     * SubjectPublicKeyInfo of the TLS leaf certificate. base64url + no padding to match the pairing
     * pubkey encoding (ADR-025 §7.1) and stay URL/TXT-safe.
     */
    fun spkiSha256(spkiDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(spkiDer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** The exact bytes the child identity key signs / the parent verifies: JCS-canonical assertion minus `sig`. */
    fun canonicalBody(assertion: SpkiAssertion): ByteArray {
        val full = json.encodeToJsonElement(SpkiAssertion.serializer(), assertion) as JsonObject
        return Canonical.canonicalizeWithout(full, "sig").encodeToByteArray()
    }

    /**
     * Accept the TLS channel iff [assertion] vouches for the cert the peer **actually presented**
     * ([presentedSpkiDer]) and is signed by the [pinnedIdentityPubkey] the parent holds. ADR-031 D2,
     * in order, each failure ⇒ `false` (reject — refuse the channel, never TOFU-accept):
     *
     *  1. a pinned child identity key exists (else there is no trust anchor — pre-pairing);
     *  2. `v == 1`;
     *  3. `spki_sha256` decodes to EXACTLY a 32-byte SHA-256 (ADR-025 D6 byte-level validation) whose
     *     bytes equal SHA-256 of the presented SPKI — binds the assertion to *this* cert, so replaying
     *     the genuine child's assertion while presenting a different (e.g. attacker) cert fails here;
     *  4. the Ed25519 signature verifies over the canonical body against the pinned identity key — an
     *     attacker lacking the child identity private key cannot forge an assertion for its own SPKI.
     */
    fun verify(
        assertion: SpkiAssertion,
        presentedSpkiDer: ByteArray,
        pinnedIdentityPubkey: ByteArray?,
    ): Boolean {
        if (pinnedIdentityPubkey == null) return false
        if (assertion.v != 1) return false
        if (assertion.spki_sha256.isEmpty()) return false
        // ADR-025 D6 doctrine, carried to the sync channel: decode the claimed pin to bytes and require
        // EXACTLY a 32-byte SHA-256 (not merely a non-empty string, which is the gap PR #64 had on the
        // pairing channel), then compare BYTES — never accept an ill-formed/wrong-length pin string.
        val claimed =
            try {
                Base64.getUrlDecoder().decode(assertion.spki_sha256)
            } catch (e: Exception) {
                return false
            }
        if (claimed.size != 32) return false
        val presented =
            try {
                MessageDigest.getInstance("SHA-256").digest(presentedSpkiDer)
            } catch (e: Exception) {
                return false
            }
        if (!MessageDigest.isEqual(claimed, presented)) return false
        return try {
            Ed25519.verify(canonicalBody(assertion), assertion.sig, pinnedIdentityPubkey)
        } catch (e: Exception) {
            false
        }
    }
}
