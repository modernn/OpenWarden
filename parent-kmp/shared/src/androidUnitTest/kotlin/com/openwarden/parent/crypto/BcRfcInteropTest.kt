package com.openwarden.parent.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the Bouncy Castle primitives used for the root key are RFC-conformant — hence
 * byte-identical to libsodium, which is equally RFC-conformant. This is the host-provable half of
 * ADR-033 D4's "BC ≡ libsodium" interop claim that the parent→child trust chain rests on, pinned
 * against the published RFC 8032 / RFC 7748 known-answer vectors. (The on-device BC-sign →
 * libsodium-verify cross-check is a tracked follow-up for #27 integration.)
 */
class BcRfcInteropTest {

    private fun hex(s: String) =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    @Test
    fun ed25519MatchesRfc8032Test1() {
        // RFC 8032 §7.1 TEST 1 (empty message).
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expectedPub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val expectedSig =
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015" +
                "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

        val priv = Ed25519PrivateKeyParameters(seed, 0)
        assertEquals(expectedPub, priv.generatePublicKey().encoded.toHexString())

        val signer = Ed25519Signer().apply { init(true, priv) }
        assertEquals(expectedSig, signer.generateSignature().toHexString())

        val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(hex(expectedPub), 0)) }
        assertTrue(verifier.verifySignature(hex(expectedSig)))
    }

    @Test
    fun x25519MatchesRfc7748Section61() {
        // RFC 7748 §6.1 — Alice's keypair; public = X25519(a, 9) = scalarmult_base.
        val priv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val expectedPub = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        assertEquals(expectedPub, X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded.toHexString())
    }
}
