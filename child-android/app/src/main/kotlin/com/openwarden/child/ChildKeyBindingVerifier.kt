package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Verifies a [ChildKeyBinding]: the **binding-signature** half of PROTOCOL §7.3 (ADR-032 D2/D4 check
 * 4b). This is the decision the **parent** runs to accept that a child's TEE-resident Curve25519
 * identity keys are vouched for by its StrongBox `K_bind`. It is shipped here, child-side, as the
 * single shared primitive and as the acceptance test for issue #22 — exactly as [SpkiBinding.verify]
 * (ADR-031) is shipped child-side for the transport binding.
 *
 * Scope: this verifies `child_binding_sig` against the presented `K_bind` public key and applies
 * ADR-025 D6 byte-level validation to the three 32-byte fields. The **attestation-chain** checks that
 * establish `K_bind` is genuine, STRONGBOX-level, GREEN-boot, locked, allow-listed (PROTOCOL §7.3
 * checks 1-4) are the parent-side cert-chain work, deferred to the ADR-025 D5 parent issues; the
 * caller MUST pass the SPKI of the **attested leaf** as [bindingKeySpkiDer].
 *
 * Fail-closed throughout: any missing, mismatched, or malformed input returns `false`.
 */
object ChildKeyBindingVerifier {

    // Identical canonical-JSON rules as SpkiBinding/CommandVerifier/BundleVerifier so the one signing
    // rule never diverges: encodeDefaults so defaulted fields are signed; omit nulls.
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** The exact bytes `K_bind` signs / the parent verifies: JCS-canonical binding minus `sig`. */
    fun canonicalBody(binding: ChildKeyBinding): ByteArray {
        val full = json.encodeToJsonElement(ChildKeyBinding.serializer(), binding) as JsonObject
        return Canonical.canonicalizeWithout(full, "sig").encodeToByteArray()
    }

    /**
     * Accept iff, in order (each failure ⇒ `false`):
     *  1. a presented `K_bind` SPKI exists (else no anchor — pre-attestation / not provisioned);
     *  2. `v == 1`;
     *  3. `child_ed25519_pub` and `child_x25519_pub` each base64url-decode (valid alphabet) to EXACTLY
     *     32 bytes (ADR-025 D6 length + alphabet — never a non-empty string of the wrong size). Full
     *     on-curve **point** validation of the pinned keys is the **parent's** job when it adopts them
     *     (sealed-box audience `K_enc` / signature-verify key `K_id`): RFC 7748 admits every 32-byte
     *     X25519 key, and reliable Ed25519 point validation needs the parent's key-adoption path (the
     *     `net.i2p.crypto.eddsa` spec used here does not reject off-curve points). The binding sig (5)
     *     already ties these exact bytes to attested hardware, so a swapped key is caught regardless.
     *  4. [expectedNonce] is exactly 32 bytes, and `provisioning_nonce` decodes to 32 bytes that equal
     *     it — the nonce the parent issued for *this* attempt (single-use freshness; a replayed
     *     prior-attempt binding, though self-consistent, carries a stale nonce and is rejected —
     *     ADR-032 D2). The parent guarantees the nonce is single-use + discarded (parent-side, deferred).
     *  5. `sig` is non-empty and verifies as ECDSA-P-256 by [bindingKeySpkiDer] over [canonicalBody]
     *     — an attacker who swaps `child_ed25519_pub`/`child_x25519_pub` (or the nonce) breaks this,
     *     and one lacking the `K_bind` private key cannot forge it.
     *
     * @param bindingKeySpkiDer the X.509 SPKI of the **attested `K_bind` leaf** (the caller establishes
     *   it is genuine/STRONGBOX via the cert chain — PROTOCOL §7.3 checks 1-4, parent-side, deferred).
     * @param expectedNonce the raw 32-byte `provisioning_nonce` the parent issued this attempt (§7.1).
     */
    fun verify(binding: ChildKeyBinding, bindingKeySpkiDer: ByteArray?, expectedNonce: ByteArray): Boolean {
        if (bindingKeySpkiDer == null) return false
        if (expectedNonce.size != 32) return false // defense-in-depth: a real single-use nonce is 32 bytes
        if (binding.v != 1) return false
        if (!is32Bytes(binding.child_ed25519_pub)) return false
        if (!is32Bytes(binding.child_x25519_pub)) return false
        val nonce = decode(binding.provisioning_nonce) ?: return false
        if (nonce.size != 32) return false
        // Constant-time freshness check: the binding must carry the exact nonce issued this attempt.
        if (!MessageDigest.isEqual(nonce, expectedNonce)) return false
        if (binding.sig.isEmpty()) return false
        return P256.verify(canonicalBody(binding), binding.sig, bindingKeySpkiDer)
    }

    /** True iff [b64url] decodes (valid alphabet) to exactly 32 bytes (ADR-025 D6 length + alphabet). */
    private fun is32Bytes(b64url: String): Boolean = decode(b64url)?.size == 32

    private fun decode(b64url: String): ByteArray? = try {
        if (b64url.isEmpty()) null else Base64.getUrlDecoder().decode(b64url)
    } catch (e: Exception) {
        null
    }
}
