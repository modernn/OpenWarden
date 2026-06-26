package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Ed25519 verification of a [SignedHeartbeat] over RFC 8785 JCS canonical JSON (ADR-024 D4).
 * Byte-rules are identical to [BundleVerifier]: body = canonicalize(heartbeat minus `sig`), via
 * the same [Canonical] subset, so the parent signer and this verifier emit byte-identical bytes.
 *
 * Fail-closed: any exception (bad hex, malformed key, canonicalization failure) returns `false`.
 */
object HeartbeatVerifier {
    // encodeDefaults=true so defaulted fields are signed; explicitNulls=false to match the bundle
    // signer's omit-null rule (PROTOCOL.md §3.1 rule 6). Heartbeat has no nullable fields today,
    // but the config is kept identical to BundleVerifier so the canonical rules never diverge.
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    fun verify(
        hb: SignedHeartbeat,
        pubkey: ByteArray,
    ): Boolean =
        try {
            Ed25519.verify(canonicalBody(hb), hb.sig, pubkey)
        } catch (e: Exception) {
            false
        }

    /** The exact bytes the parent signs / child verifies: JCS-canonical heartbeat without `sig`. */
    fun canonicalBody(hb: SignedHeartbeat): ByteArray {
        val full = json.encodeToJsonElement(SignedHeartbeat.serializer(), hb) as JsonObject
        return Canonical.canonicalizeWithout(full, "sig").encodeToByteArray()
    }
}
