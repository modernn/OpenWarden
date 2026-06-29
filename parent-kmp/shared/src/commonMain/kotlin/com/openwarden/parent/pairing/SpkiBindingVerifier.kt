package com.openwarden.parent.pairing

import com.openwarden.parent.crypto.openwardenSha256
import com.openwarden.proto.Canonical
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The parent-side decision for ADR-031 D2 (the parent half deferred at D5, implemented at D7): accept or
 * reject a presented TLS leaf cert by the child's [SpkiAssertion], against the child Ed25519 **identity**
 * key the parent pinned at pairing. This is the symmetric counterpart to child-android
 * `SpkiBinding.verify`, shipped here so the **parent** — the side that actually connects over the LAN —
 * can reject a spoofed `_openwarden._tcp` responder (red-team TR1 / issue #21). It is proven
 * deterministically by host tests ahead of the deferred live TLS socket (ADR-031 D5).
 *
 * TLS is confidentiality-only (ADR-031 D1): this never authenticates a *message* — every state-changing
 * message is independently admitted by its parent-signed app-layer check, regardless of transport.
 *
 * Pure + seam-injected: the Ed25519 verify is the [Ed25519Verifier] platform seam (ADR-031 D7), so the
 * whole accept/reject matrix is host-deterministic (no native dep in `commonTest`). Fail-closed: any
 * missing / mismatched / malformed input returns `false` — reject the channel, never TOFU-accept.
 */
class SpkiBindingVerifier(
    private val ed25519: Ed25519Verifier,
) {
    /**
     * Accept the TLS channel iff [assertion] vouches for the cert the peer **actually presented**
     * ([presentedSpkiDer]) and is signed by the [pinnedIdentityPubkey] the parent holds (ADR-031 D2),
     * in order — each failure ⇒ `false` (reject):
     *  1. a pinned child identity key exists (else no trust anchor — pre-pairing);
     *  2. `v == 1`;
     *  3. `spki_sha256` decodes to EXACTLY a 32-byte SHA-256 (ADR-025 D6 byte-level validation) whose
     *     bytes equal SHA-256 of the presented SPKI — binds the assertion to *this* cert, so replaying
     *     the genuine child's assertion while presenting a different (attacker) cert fails here;
     *  4. the Ed25519 signature verifies over the canonical body against the pinned identity key — an
     *     attacker lacking the child identity private key cannot forge an assertion for its own SPKI.
     *
     * @param presentedSpkiDer the DER `SubjectPublicKeyInfo` of the **negotiated TLS leaf** cert (from
     *   the completed handshake) — never a cert echoed in an application payload (ADR-031 D5 carry-forward).
     */
    fun verify(
        assertion: SpkiAssertion,
        presentedSpkiDer: ByteArray,
        pinnedIdentityPubkey: ByteArray?,
    ): Boolean {
        if (pinnedIdentityPubkey == null) return false
        if (assertion.v != 1) return false
        if (assertion.spkiSha256.isEmpty()) return false
        // ADR-025 D6: decode the claimed pin to bytes and require EXACTLY a 32-byte SHA-256 (not merely a
        // non-empty string — the PR #64 gap), then compare bytes; never accept an ill-formed/wrong-length pin.
        val claimed = Base64Url.decode32(assertion.spkiSha256) ?: return false
        val presented = openwardenSha256(presentedSpkiDer)
        if (!constantTimeEquals(claimed, presented)) return false
        if (assertion.sig.isEmpty()) return false
        val sigBytes = hexToBytes(assertion.sig) ?: return false
        return ed25519.verify(canonicalBody(assertion), sigBytes, pinnedIdentityPubkey)
    }

    companion object {
        // Identical canonical-JSON rules as the child + the parent's other signed objects: encodeDefaults
        // so defaulted fields are signed; omit nulls. The one JCS rule never diverges (ADR-015/019).
        private val json =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        /**
         * RFC 7469 SPKI pin: base64url-no-pad(SHA-256([spkiDer])). Byte-identical to the child's
         * `SpkiBinding.spkiSha256`, so a child-emitted assertion and the parent's recomputation agree.
         */
        fun spkiSha256(spkiDer: ByteArray): String = Base64Url.encode(openwardenSha256(spkiDer))

        /** The exact bytes the child identity key signs / the parent verifies: JCS-canonical body minus `sig`. */
        fun canonicalBody(assertion: SpkiAssertion): ByteArray {
            val full = json.encodeToJsonElement(SpkiAssertion.serializer(), assertion) as JsonObject
            return Canonical.canonicalizeWithout(full, "sig").encodeToByteArray()
        }

        /** Length-independent, content-constant-time compare of two equal-length digests (mirrors the
         * child's `MessageDigest.isEqual`; both inputs are public hashes, so this is defensive hygiene). */
        private fun constantTimeEquals(
            a: ByteArray,
            b: ByteArray,
        ): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }

        /** Decode an even-length hex string to bytes; `null` on odd length or any non-hex nibble (fail-closed). */
        private fun hexToBytes(s: String): ByteArray? {
            if (s.isEmpty() || s.length % 2 != 0) return null
            val out = ByteArray(s.length / 2)
            var i = 0
            while (i < s.length) {
                val hi = nibble(s[i])
                val lo = nibble(s[i + 1])
                if (hi < 0 || lo < 0) return null
                out[i / 2] = ((hi shl 4) or lo).toByte()
                i += 2
            }
            return out
        }

        private fun nibble(c: Char): Int =
            when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> -1
            }
    }
}
