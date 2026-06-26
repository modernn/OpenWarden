package com.openwarden.child

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

/**
 * Shared Ed25519 verification primitive — one crypto path for every signed parent artifact:
 * policy bundles ([BundleVerifier]) and heartbeats ([HeartbeatVerifier]).
 *
 * Fail-closed: any exception (bad hex, malformed key, bad signature) returns `false`.
 */
object Ed25519 {
    /** Verify [sigHex] (hex Ed25519) over [message] against the 32-byte [pubkey]. */
    fun verify(
        message: ByteArray,
        sigHex: String,
        pubkey: ByteArray,
    ): Boolean =
        try {
            val sig = sigHex.hexToBytes()
            val spec = EdDSAPublicKeySpec(pubkey, EdDSANamedCurveTable.getByName("Ed25519"))
            val key = EdDSAPublicKey(spec)
            val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
            engine.initVerify(key)
            engine.update(message)
            engine.verify(sig)
        } catch (e: Exception) {
            false
        }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
