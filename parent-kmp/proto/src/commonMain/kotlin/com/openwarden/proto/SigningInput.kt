package com.openwarden.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The single signing rule (ADR-015).
 *
 * Every Ed25519 signature in OpenWarden — over a [PolicyBundle] **and** over an
 * [EventEntry] — is computed and verified over the **same** input: the RFC 8785 (JCS)
 * canonical bytes of the wire object with its `sig` field removed. There is exactly one
 * rule and one function, so no field can sit outside the signed region.
 *
 * This closes red-team **SG1**: the old STORE_AND_FORWARD scheme signed only a
 * concatenation `payload‖prev_hash‖seq`, leaving `v`, `issued_at`, and `payload_type`
 * unsigned and therefore malleable. Here the canonical form covers the whole object, so
 * mutating any field — including those three — changes the signing bytes and invalidates
 * the signature.
 *
 * Integer bounding (ADR-017 / JC1): every JSON integer in the object is asserted within
 * `0..2^53-1` ([Canonical.MAX_JCS_SAFE_INTEGER]) *before* canonicalization. JCS formats
 * numbers as ECMAScript doubles, which are exact only to 2^53-1; an out-of-range
 * `policy_seq`/timestamp would either fail to round-trip (signer and verifier disagree →
 * SIG_FAIL) or let an attacker pin the replay floor so high no future bundle can exceed it
 * (a permanent update-freeze DoS). We fail closed by rejecting it up front.
 *
 * Pure logic — no native crypto — so it compiles and tests on the desktop JVM. The actual
 * Ed25519 `crypto_sign` over [forBundle]/[forEntry]/[forDocument] bytes lives in `:shared`
 * (libsodium) and is exercised on-device / in CI where the native library is present.
 */
object SigningInput {
    /** The wire field that carries the detached signature; always excluded from its own input. */
    const val SIG_FIELD: String = "sig"

    // encodeDefaults=true so defaulted fields (e.g. `v`, empty blocklist/windows/restrictions)
    // are part of the signed bytes; explicitNulls=false so optional fields left null
    // (private_dns, frp_account_email) are OMITTED — PROTOCOL.md §3.1 rule 6 forbids `null`.
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** Signer side: canonical signing bytes for a [PolicyBundle] (ADR-015). */
    fun forBundle(bundle: PolicyBundle): ByteArray =
        canonicalSigningBytes(json.encodeToJsonElement(PolicyBundle.serializer(), bundle))

    /** Signer side: canonical signing bytes for an [EventEntry] (ADR-015). */
    fun forEntry(entry: EventEntry): ByteArray =
        canonicalSigningBytes(json.encodeToJsonElement(EventEntry.serializer(), entry))

    /**
     * Verifier side: canonical signing bytes for an already-parsed wire document.
     *
     * A verifier MUST canonicalize the document it actually received — not a re-encoding of
     * its own typed model — so that signature verification depends only on the bytes the
     * signer committed to, never on whether the two ends agree about defaulted fields.
     */
    fun forDocument(obj: JsonObject): ByteArray = canonicalSigningBytes(obj)

    private fun canonicalSigningBytes(element: JsonElement): ByteArray {
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("signing input must be a JSON object, was ${element::class.simpleName}")
        requireAllIntegersJcsSafe(obj)
        return Canonical.canonicalizeWithout(obj, SIG_FIELD).encodeToByteArray()
    }

    /**
     * Recursively assert that every JSON integer in [element] is within the JCS-safe range
     * (ADR-017). Non-integer numbers are rejected later by [Canonical]; here we additionally
     * reject integers above 2^53-1, which `toLong` would otherwise accept but JCS cannot
     * round-trip. Applied to the whole tree (defense in depth), not just the named counters.
     */
    fun requireAllIntegersJcsSafe(element: JsonElement) {
        when (element) {
            is JsonObject -> element.values.forEach { requireAllIntegersJcsSafe(it) }
            is JsonArray -> element.forEach { requireAllIntegersJcsSafe(it) }
            JsonNull -> Unit // null carries no integer; null-policy handled on the verifier path
            is JsonPrimitive ->
                if (!element.isString) {
                    val c = element.content
                    if (c != "true" && c != "false") {
                        // A bare JSON number. It MUST be an integer within the JCS-safe range.
                        // toLongOrNull() == null means a float/exponent or a value beyond Long's
                        // range — reject HERE, fail-closed, rather than letting it slip through to
                        // canonicalization with a misleading "non-integer" error (review finding).
                        val l = c.toLongOrNull()
                            ?: throw IllegalArgumentException(
                                "JSON number '$c' is not a JCS-safe integer (must be an integer in 0..2^53-1)",
                            )
                        Canonical.requireJcsSafe(l)
                    }
                }
        }
    }
}
