package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

/**
 * Ed25519 verification over RFC 8785 JCS canonicalized JSON.
 *
 * TODO(v1): implement actual RFC 8785 canonicalization. Current stub uses
 * kotlinx.serialization default ordering which is NOT canonical — must be replaced
 * before any production deployment.
 */
object BundleVerifier {

    fun verify(bundle: SignedBundle, pubkey: ByteArray): Boolean {
        return try {
            val canonical = canonicalize(bundle)
            val sig = bundle.sig.hexToBytes()
            val spec = EdDSAPublicKeySpec(pubkey, EdDSANamedCurveTable.getByName("Ed25519"))
            val key = EdDSAPublicKey(spec)
            val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
            engine.initVerify(key)
            engine.update(canonical)
            engine.verify(sig)
        } catch (e: Exception) {
            false
        }
    }

    private fun canonicalize(bundle: SignedBundle): ByteArray {
        // STUB — see TODO above.
        val unsigned = buildJsonObject {
            put("v", bundle.v)
            put("issued_at", bundle.issued_at)
            put("expires_at", bundle.expires_at)
            put("nonce", bundle.nonce)
            put("policy", Json.encodeToJsonElement(PolicyDoc.serializer(), bundle.policy))
        }
        return Json.encodeToString(JsonObject.serializer(), unsigned).encodeToByteArray()
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
