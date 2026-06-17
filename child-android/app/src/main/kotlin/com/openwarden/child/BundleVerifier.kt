package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Ed25519 verification over RFC 8785 JCS canonicalized JSON.
 *
 * The signed body is the full [SignedBundle] minus its `sig` field, canonicalized
 * via [Canonical] (ported byte-rule-identical from the proto module). This now
 * includes the ADR-017 audience field `child_device_id` and the replay counter
 * `policy_seq`, so a parent signature covers them — they cannot be altered after
 * signing without invalidating `sig`.
 *
 * Fail-closed: any exception (bad hex, malformed key, canonicalization failure)
 * returns `false`.
 *
 * NOTE: [Canonical] is a deliberate subset of full RFC 8785 (object-key sort +
 * arrays-in-order + integers-only + minimal escaping) that matches PROTOCOL.md
 * §3.1. The parent signer MUST use the identical rules (same port).
 */
object BundleVerifier {

    // encodeDefaults=true so defaulted fields (empty blocklist/windows/restrictions) are part
    // of the signed body; explicitNulls=false so optional fields left null (private_dns,
    // frp_account_email) are OMITTED, not serialized as `null` — PROTOCOL.md §3.1 rule 6
    // forbids `null`, and the parent signer omits them identically (byte-identical canonical).
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun verify(bundle: SignedBundle, pubkey: ByteArray): Boolean = try {
        Ed25519.verify(canonicalBody(bundle), bundle.sig, pubkey)
    } catch (e: Exception) {
        // canonicalBody can throw (canonicalization failure) — fail-closed.
        false
    }

    /**
     * The exact bytes the parent signs and the child verifies: JCS-canonical
     * encoding of the bundle with the `sig` field removed (ADR-015 / PROTOCOL.md
     * §2.1 "body := canonicalize(bundle without sig)"). Exposed for test vectors
     * that build a known-good signature.
     */
    fun canonicalBody(bundle: SignedBundle): ByteArray {
        val full = json.encodeToJsonElement(SignedBundle.serializer(), bundle) as JsonObject
        return Canonical.canonicalizeWithout(full, "sig").encodeToByteArray()
    }
}
