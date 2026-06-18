package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Ed25519 verification of a [SignedCommand] over RFC 8785 JCS canonical JSON (ADR-030). Byte-rules
 * are identical to [BundleVerifier] and [HeartbeatVerifier]: body = canonicalize(command minus
 * `sig`), via the same [Canonical] subset, so the parent signer and this verifier emit
 * byte-identical bytes — anything else is `SIG_FAIL`.
 *
 * Fail-closed: any exception (bad hex, malformed key, canonicalization failure) returns `false`.
 */
object CommandVerifier {

    // encodeDefaults=true so defaulted fields are signed; explicitNulls=false to match the bundle
    // signer's omit-null rule (PROTOCOL.md §3.1 rule 6). Kept identical to BundleVerifier /
    // HeartbeatVerifier so the canonical rules never diverge across the three signed wire types.
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun verify(cmd: SignedCommand, pubkey: ByteArray): Boolean = try {
        Ed25519.verify(canonicalBody(cmd), cmd.sig, pubkey)
    } catch (e: Exception) {
        false
    }

    /** The exact bytes the parent signs / child verifies: JCS-canonical command without `sig`. */
    fun canonicalBody(cmd: SignedCommand): ByteArray {
        val full = json.encodeToJsonElement(SignedCommand.serializer(), cmd) as JsonObject
        return Canonical.canonicalizeWithout(full, "sig").encodeToByteArray()
    }
}
