package com.openwarden.parent.pairing

import com.openwarden.proto.Canonical
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The §7.3 check-4b signing input (ADR-032 D2, ADR-037 D6): the RFC 8785 (JCS) canonical bytes of
 *
 * ```
 * { "v": 1, "child_ed25519_pub": "...", "child_x25519_pub": "...", "provisioning_nonce": "..." }
 * ```
 *
 * The child (#22) signs exactly these bytes with `K_bind` (ECDSA-P-256); the parent re-derives them
 * and verifies. **Byte-for-byte agreement is load-bearing** — so the values here are the *verbatim
 * wire strings*: `child_*_pub` exactly as POSTed, and `provisioning_nonce` as the unpadded
 * base64url(32) the §7.1 QR carried (`Base64Url.encode(session.nonce())`). Canonicalization is the
 * single shared [Canonical] JCS used for bundles and events, so there is no second canonicalizer to
 * drift. A twin-signer test pins the agreement; substituted-`ed`/`x` and stale-`nonce` reject cases
 * prove the body actually covers each field.
 */
internal object ChildKeyBindingCanonical {
    fun bytes(
        v: Int,
        childEd25519Pub: String,
        childX25519Pub: String,
        provisioningNonceB64Url: String,
    ): ByteArray {
        val obj =
            buildJsonObject {
                // Insertion order is irrelevant — Canonical sorts keys (UTF-16 code-unit order) per JCS.
                put("v", JsonPrimitive(v))
                put("child_ed25519_pub", JsonPrimitive(childEd25519Pub))
                put("child_x25519_pub", JsonPrimitive(childX25519Pub))
                put("provisioning_nonce", JsonPrimitive(provisioningNonceB64Url))
            }
        return Canonical.canonicalize(obj).encodeToByteArray()
    }
}
