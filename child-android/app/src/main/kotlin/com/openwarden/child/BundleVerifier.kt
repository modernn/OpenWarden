package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Ed25519 verification over RFC 8785 JCS canonicalized JSON.
 *
 * **ADR-040 / ADR-019 D2-D3: the verifier verifies over the bytes it RECEIVED.** The live entry
 * point is [verifyDocument], which canonicalizes the *received* [JsonObject] minus its `sig` field
 * and verifies the detached signature carried in `sig`. It MUST NOT re-canonicalize a re-encoding of
 * the child's own typed [SignedBundle] model — that reintroduces signer/verifier byte drift (a
 * defaulted field's presence/absence, an unknown parent-signed field dropped by `ignoreUnknownKeys`,
 * key reordering) and fails genuinely valid signatures (PROTOCOL.md §3.1 MUST NOT).
 *
 * [toWireDocument] / [canonicalBody] / [verify] operate on the TYPED model and are signer-side test
 * conveniences ONLY (golden vectors) — ADR-019 D3: the typed model is a post-verification parse
 * target, never the verifier's signing input.
 *
 * Fail-closed: any exception (bad hex, malformed key, JC1 overflow, canonicalization failure,
 * missing/non-string `sig`) returns `false`.
 *
 * NOTE: [Canonical] is a deliberate subset of full RFC 8785 (object-key sort + arrays-in-order +
 * integers-only + minimal escaping) that matches PROTOCOL.md §3.1. The parent signer MUST use the
 * identical rules (same port). **If you change one, change both.**
 */
object BundleVerifier {
    // encodeDefaults=true so defaulted fields (empty blocklist/windows/restrictions) are part of the
    // signed body; explicitNulls=false so optional fields left null (private_dns, frp_account_email)
    // are OMITTED, not serialized as `null` — PROTOCOL.md §3.1 rule 6 forbids `null`. Used ONLY for
    // the signer-side test conveniences below; the live verifier path uses the received JsonObject.
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    /**
     * The exact signing bytes of a RECEIVED wire object (ADR-040 D1/D2): the JCS-canonical encoding
     * of the object with its `sig` field removed. JC1 integer bounds are asserted over the whole tree
     * first (ADR-017 / ADR-019 D4), so an out-of-range integer in ANY field fails closed BEFORE the
     * signature check. Throws on JC1 violation or a non-integer number.
     */
    fun signingBytes(receivedDoc: JsonObject): ByteArray {
        Canonical.requireNoNulls(receivedDoc) // §3.1 rule 6: null forbidden in a signed document
        Canonical.requireAllIntegersJcsSafe(receivedDoc)
        return Canonical.canonicalizeWithout(receivedDoc, "sig").encodeToByteArray()
    }

    /**
     * Verify the detached Ed25519 signature carried in `receivedDoc["sig"]` over the signing bytes of
     * the RECEIVED object (ADR-040 D3). This is the live verifier entry point. Fail-closed: a missing
     * or non-string `sig`, a JC1 overflow, a canonicalization failure, or a bad signature returns
     * `false`.
     */
    fun verifyDocument(
        receivedDoc: JsonObject,
        pubkey: ByteArray,
    ): Boolean =
        try {
            val sigHex =
                (receivedDoc["sig"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?: return false // no signature on the wire — never admissible
            Ed25519.verify(signingBytes(receivedDoc), sigHex, pubkey)
        } catch (e: Exception) {
            // JC1 / canonicalization failure — fail-closed.
            false
        }

    /**
     * Signer-side test convenience ONLY (ADR-019 D3 / ADR-040): the wire JSON object a typed bundle
     * serializes to. NOT a verifier input — the live verifier uses the object it actually received.
     */
    internal fun toWireDocument(bundle: SignedBundle): JsonObject =
        json.encodeToJsonElement(SignedBundle.serializer(), bundle) as JsonObject

    /**
     * The canonical signed body of a TYPED bundle (JCS minus `sig`). Signer-side / golden-vector test
     * convenience ONLY (ADR-019 D3). The live verifier path is [verifyDocument] over the received
     * object — never this typed re-serialization.
     */
    internal fun canonicalBody(bundle: SignedBundle): ByteArray =
        Canonical.canonicalizeWithout(toWireDocument(bundle), "sig").encodeToByteArray()

    /**
     * Verify over a TYPED bundle by re-serializing it to its wire object. **Test/golden convenience
     * ONLY** (ADR-040): the live path verifies over the received document via [verifyDocument]. Equal
     * to [verifyDocument] only when the received bytes match this typed re-encoding byte-for-byte —
     * exactly the assumption ADR-040 stops relying on for real traffic.
     */
    @Deprecated(
        "ADR-040: verify over the RECEIVED document via verifyDocument(JsonObject, key); the typed " +
            "re-serialization is for tests/golden vectors only.",
        ReplaceWith("verifyDocument(toWireDocument(bundle), pubkey)"),
    )
    internal fun verify(
        bundle: SignedBundle,
        pubkey: ByteArray,
    ): Boolean = verifyDocument(toWireDocument(bundle), pubkey)
}
