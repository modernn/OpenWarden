package com.openwarden.parent.crypto

import com.openwarden.proto.Canonical
import com.openwarden.proto.SignedCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The ONE signing-input rule for lock / unlock commands (ADR-030 / ADR-046 D3): the RFC 8785 (JCS)
 * canonical bytes of the [SignedCommand] with its `sig` field removed — **byte-identical** to the
 * child's `CommandVerifier.canonicalBody`, so a command signed here with BouncyCastle Ed25519 verifies
 * under the child's i2p Ed25519 verifier.
 *
 * `encodeDefaults = true` so `v` / `type` / `child_device_id` / `issued_at` are part of the signing
 * input (mirrors [PolicySigner]); `explicitNulls = false` (there are no nullable fields in
 * [SignedCommand], kept for parity with the bundle signer). Pure: the actual Ed25519 sign is the
 * caller's [RootKeyProvider.sign] over these bytes.
 */
object CommandSigner {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    /** Canonical signing-input bytes for [cmd] (the `sig` field excluded). */
    fun signingBytes(cmd: SignedCommand): ByteArray {
        // The child gates issued_at to the JCS-safe integer range before verifying; bound it here too
        // so the parent never emits a command the child will reject as out-of-range (ADR-017).
        Canonical.requireJcsSafe(cmd.issuedAt)
        val obj = json.encodeToJsonElement(SignedCommand.serializer(), cmd) as JsonObject
        return Canonical.canonicalizeWithout(obj, "sig").encodeToByteArray()
    }
}
