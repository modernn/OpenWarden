package com.openwarden.parent.crypto

import com.ionspin.kotlin.crypto.signature.Signature
import com.openwarden.proto.Canonical
import com.openwarden.proto.PolicyBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A parent Ed25519 identity (libsodium). [secretKey] is root signing authority. */
class Identity(
    val publicKey: UByteArray,
    val secretKey: UByteArray,
) {
    companion object {
        /** Requires [bootstrapCrypto] to have completed. */
        fun generate(): Identity {
            val kp = Signature.keypair()
            return Identity(publicKey = kp.publicKey, secretKey = kp.secretKey)
        }
    }
}

/**
 * Signs/verifies policy bundles. There is exactly ONE signing input rule
 * (ADR-015): the RFC 8785 (JCS) canonical bytes of the bundle with its `sig`
 * field removed. Integer fields are bounded to the JCS-safe range first (ADR-017).
 */
object PolicySigner {
    // encodeDefaults=true so `v` (and any other defaulted field) is part of the
    // signing input; `sig` is then stripped by Canonical.canonicalizeWithout.
    private val json = Json { encodeDefaults = true }

    /** The canonical signing input bytes for [bundle] (pure; no libsodium). */
    fun signingBytes(bundle: PolicyBundle): ByteArray {
        Canonical.requireJcsSafe(bundle.policySeq)
        Canonical.requireJcsSafe(bundle.notBefore)
        Canonical.requireJcsSafe(bundle.notAfter)
        val obj = json.encodeToJsonElement(PolicyBundle.serializer(), bundle) as JsonObject
        return Canonical.canonicalizeWithout(obj, "sig").encodeToByteArray()
    }

    /** Detached Ed25519 signature over [signingBytes] (libsodium). */
    fun sign(secretKey: UByteArray, bundle: PolicyBundle): UByteArray =
        Signature.detached(signingBytes(bundle).toUByteArray(), secretKey)

    /** True iff [signature] verifies for [bundle] under [publicKey] (fail-closed on any error). */
    fun verify(publicKey: UByteArray, signature: UByteArray, bundle: PolicyBundle): Boolean =
        runCatching {
            Signature.verifyDetached(signature, signingBytes(bundle).toUByteArray(), publicKey)
            true
        }.getOrDefault(false)
}
