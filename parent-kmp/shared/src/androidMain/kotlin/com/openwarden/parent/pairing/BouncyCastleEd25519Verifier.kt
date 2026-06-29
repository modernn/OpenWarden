package com.openwarden.parent.pairing

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Real ADR-031 D7 Ed25519 verifier: RFC 8032 via Bouncy Castle — the same curve and the same
 * canonicalize-then-Ed25519 rule the child's signer uses (ADR-033's RFC 8032 KATs prove BC ≡ libsodium ≡
 * i2p eddsa, so a child-emitted `SpkiAssertion` verifies here). Returns `false` — never throws — on any
 * failure (wrong key/sig length, malformed key, bad signature), so a bad input fails closed.
 */
class BouncyCastleEd25519Verifier : Ed25519Verifier {
    override fun verify(
        message: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray,
    ): Boolean =
        try {
            // A real Ed25519 public key is 32 bytes and a signature is 64; reject anything else outright
            // (BC would throw on a wrong-length key — we fail closed explicitly before constructing it).
            if (publicKey.size != 32 || signature.size != 64) {
                false
            } else {
                Ed25519Signer()
                    .apply {
                        init(false, Ed25519PublicKeyParameters(publicKey, 0))
                        update(message, 0, message.size)
                    }.verifySignature(signature)
            }
        } catch (e: Exception) {
            false
        }
}
