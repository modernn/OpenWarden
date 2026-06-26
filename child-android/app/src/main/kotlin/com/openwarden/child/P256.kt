package com.openwarden.child

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Shared ECDSA-P-256 verification primitive — the counterpart to [Ed25519] for the one place the
 * protocol uses a NIST curve: the child **device-binding key** `K_bind` (ADR-032). `K_bind` is EC
 * P-256 because that is what Android StrongBox can attest (Curve25519 cannot be StrongBox-backed);
 * it signs the [ChildKeyBinding] that ties the TEE-resident Curve25519 identity keys to attested
 * hardware (PROTOCOL §7.3 check 4b).
 *
 * Fail-closed: any exception (bad hex, malformed SPKI, malformed signature, wrong curve) returns
 * `false`. The verifier treats `child_binding_sig` as a one-shot accept/reject and never as a
 * uniqueness/replay/dedup key, so ECDSA `(r, n-s)` malleability is not exploitable (ADR-032 D2).
 */
object P256 {
    /**
     * Verify [sigHex] (hex of a DER-encoded ECDSA signature) over [message] against [spkiDer], the
     * X.509 SubjectPublicKeyInfo of a P-256 public key (e.g. the attested `K_bind` leaf key).
     */
    fun verify(
        message: ByteArray,
        sigHex: String,
        spkiDer: ByteArray,
    ): Boolean {
        return try {
            val sig = sigHex.hexToBytes()
            if (sig.isEmpty()) return false
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiDer))
            val engine = Signature.getInstance("SHA256withECDSA")
            engine.initVerify(key)
            engine.update(message)
            engine.verify(sig)
        } catch (e: Exception) {
            false
        }
    }

    private fun String.hexToBytes(): ByteArray {
        if (length % 2 != 0) throw IllegalArgumentException("odd-length hex")
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
