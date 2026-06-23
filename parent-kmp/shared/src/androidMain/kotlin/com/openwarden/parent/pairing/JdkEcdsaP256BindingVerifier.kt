package com.openwarden.parent.pairing

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Real §7.3 check-4b verifier (ADR-037 D4): `SHA256withECDSA` (ECDSA-P-256) by the leaf `K_bind`
 * key over the JCS binding bytes, via the JDK provider (Android ships an EC + ECDSA provider; no
 * Bouncy Castle needed for this path). Returns `false` — never throws — on any failure, so a
 * malformed key or signature fails closed.
 *
 * The signature is a one-shot accept/reject; ECDSA `(r, n−s)` malleability is irrelevant because it
 * is never cached or used as a uniqueness/replay key (ADR-032 D2).
 */
class JdkEcdsaP256BindingVerifier : EcdsaP256BindingVerifier {
    override fun verify(
        leafSpkiDer: ByteArray,
        signedBytes: ByteArray,
        derSignature: ByteArray,
    ): Boolean =
        try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(leafSpkiDer))
            if (pub !is ECPublicKey || pub.params.curve.field.fieldSize != 256) {
                false // defense-in-depth: only a 256-bit EC key (the verifier core also asserts leafIsEcP256).
            } else {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(pub)
                    update(signedBytes)
                    verify(derSignature)
                }
            }
        } catch (e: Exception) {
            false // bad key spec / malformed DER signature / provider error ⇒ fail-closed.
        }
}
