package com.openwarden.parent.policy

import com.openwarden.parent.crypto.FakeSecureKeyStorage
import com.openwarden.parent.crypto.NotProvisionedRootKeyProvider
import com.openwarden.parent.crypto.PolicySigner
import com.openwarden.parent.crypto.RootKeyDerivation
import com.openwarden.parent.crypto.StoredRootKeyProvider
import com.openwarden.parent.crypto.bip39.Bip39
import com.openwarden.proto.Policy
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real root-key sign → verify round-trip + tamper rejection (ADR-034 D7), host-side via Bouncy
 * Castle (the same RFC 8032 Ed25519 the child's libsodium verifier accepts — ADR-033 RFC KATs).
 */
class SignedBundleAssemblerTest {
    private fun provisionedProvider(): StoredRootKeyProvider {
        val storage = FakeSecureKeyStorage()
        StoredRootKeyProvider.provision(storage, RootKeyDerivation.deriveRootKeys(Bip39.encode(ByteArray(32))))
        return StoredRootKeyProvider(storage)
    }

    private fun unsigned() =
        PolicyBundleBuilder.build(
            policy = Policy(allowlist = listOf("a.app")),
            childDeviceId = "child-1",
            policySeq = 1,
            nowMs = 1_000L,
            freshnessWindowMs = 86_400_000L,
            nonceHex = "0".repeat(32),
        )

    private fun verifiesUnder(
        pub: ByteArray,
        sigHex: String,
        signedBytes: ByteArray,
    ): Boolean {
        val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(pub, 0)) }
        verifier.update(signedBytes, 0, signedBytes.size)
        return verifier.verifySignature(sigHex.hexToBytes())
    }

    @Test
    fun signsAndVerifiesUnderRootKey() {
        val provider = provisionedProvider()
        val signed = assertNotNull(SignedBundleAssembler.assemble(unsigned(), provider))
        assertNotNull(signed.sig)
        val pub = assertNotNull(provider.rootPublicKey())
        // signingBytes strips `sig`, so it equals the bytes that were signed.
        assertTrue(verifiesUnder(pub, signed.sig!!, PolicySigner.signingBytes(signed)))
    }

    @Test
    fun tamperInvalidatesSignature() {
        val provider = provisionedProvider()
        val signed = assertNotNull(SignedBundleAssembler.assemble(unsigned(), provider))
        val tampered = signed.copy(policy = Policy(allowlist = listOf("evil.app")))
        val pub = assertNotNull(provider.rootPublicKey())
        assertFalse(verifiesUnder(pub, signed.sig!!, PolicySigner.signingBytes(tampered)))
    }

    @Test
    fun failClosedWhenNotProvisioned() {
        assertNull(SignedBundleAssembler.assemble(unsigned(), NotProvisionedRootKeyProvider))
    }

    @Test
    fun rejectsAlreadySignedBundle() {
        val provider = provisionedProvider()
        val signed = assertNotNull(SignedBundleAssembler.assemble(unsigned(), provider))
        assertFailsWith<IllegalArgumentException> { SignedBundleAssembler.assemble(signed, provider) }
    }
}

private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }
