package com.openwarden.child

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Shared ECDSA-P-256 test signer — the synthetic stand-in for the StrongBox `K_bind` key (ADR-032),
 * mirroring [CommandTestSigner] for Ed25519. Pure JVM JCE (SunEC), so it runs in plain unit tests.
 * Test-source only.
 */
object P256TestSigner {
    class Keypair(
        val priv: PrivateKey,
        val spkiDer: ByteArray,
    )

    fun newKeypair(): Keypair {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        return Keypair(kp.private, kp.public.encoded) // public.encoded == X.509 SubjectPublicKeyInfo
    }

    /** DER-encoded ECDSA signature over [message] — what [ChildKeyStore.signBinding] returns. */
    fun signDer(
        message: ByteArray,
        kp: Keypair,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.priv)
            update(message)
            sign()
        }
}
