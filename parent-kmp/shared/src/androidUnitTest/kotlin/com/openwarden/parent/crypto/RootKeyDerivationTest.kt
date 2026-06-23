package com.openwarden.parent.crypto

import com.openwarden.parent.crypto.bip39.Bip39
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Root-key derivation vector + properties (ADR-033 D1/D8). Runs host-side via Bouncy Castle (no
 * device, no libsodium). The all-zeros mnemonic ratifies CRYPTO.md §2's placeholder vector.
 */
class RootKeyDerivationTest {

    private val allZeroMnemonic = Bip39.encode(ByteArray(32))

    @Test
    fun deterministicSamePhraseSameKeys() {
        val a = RootKeyDerivation.deriveRootKeys(allZeroMnemonic)
        val b = RootKeyDerivation.deriveRootKeys(allZeroMnemonic)
        assertContentEquals(a.serialize(), b.serialize())
    }

    @Test
    fun structuralSizes() {
        val k = RootKeyDerivation.deriveRootKeys(allZeroMnemonic)
        assertEquals(32, k.ed25519Seed.size)
        assertEquals(32, k.ed25519Public.size)
        assertEquals(32, k.x25519Private.size)
        assertEquals(32, k.x25519Public.size)
        assertEquals(64, RootKeyDerivation.deriveSeed(allZeroMnemonic).size)
    }

    @Test
    fun ed25519PublicMatchesStoredSeed() {
        val k = RootKeyDerivation.deriveRootKeys(allZeroMnemonic)
        val pub = Ed25519PrivateKeyParameters(k.ed25519Seed, 0).generatePublicKey().encoded
        assertContentEquals(pub, k.ed25519Public)
    }

    @Test
    fun signatureVerifiesUnderDerivedPublicKey() {
        val k = RootKeyDerivation.deriveRootKeys(allZeroMnemonic)
        val msg = "openwarden-root".encodeToByteArray()
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(k.ed25519Seed, 0))
            update(msg, 0, msg.size)
        }
        val sig = signer.generateSignature()
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(k.ed25519Public, 0))
            update(msg, 0, msg.size)
        }
        assertTrue(verifier.verifySignature(sig))
    }

    @Test
    fun rejectsInvalidMnemonic() {
        val bad = allZeroMnemonic.toMutableList().apply { this[0] = "ability" } // breaks checksum
        assertFailsWith<IllegalArgumentException> { RootKeyDerivation.deriveSeed(bad) }
    }

    @Test
    fun ratifiedVector() {
        // ADR-033 D8 / CRYPTO.md §2 — frozen for the all-zeros 256-bit mnemonic ("abandon …×23 art").
        // Any drift in params (256 MiB / t=4 / p=2), salts, info labels, or curve impl breaks this.
        val seed = RootKeyDerivation.deriveSeed(allZeroMnemonic)
        val k = RootKeyDerivation.deriveRootKeys(allZeroMnemonic)
        assertEquals(
            "371250dc567bbf489044c0780951b33484869df84394baea232b3a6af2a1128a" +
                "c60a67033822531e0887de304b4d260a10ea40d1026654cedad10cafbecdaae7",
            seed.toHexString(),
        )
        assertEquals("b7b3e447d8a0c0063a82dc86c7183604269557a6817cf91a2a5aa7dbdc11998f", k.ed25519Seed.toHexString())
        assertEquals("7716d982ba225b69533e60b90a66c670af966d2f95512b5b1f93cbcc00375a58", k.x25519Private.toHexString())
        assertEquals("1ca19c6f54b3841f0fa4dd8e9de6168b2509a0504043133bf1fc0c9522488b17", k.ed25519Public.toHexString())
        assertEquals("e6bfad93496d3149ed72a79e5ef0ab60c3d153f419e895d0abbaa6a930bc7819", k.x25519Public.toHexString())
    }
}
