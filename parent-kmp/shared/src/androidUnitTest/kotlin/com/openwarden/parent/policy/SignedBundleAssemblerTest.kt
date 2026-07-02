package com.openwarden.parent.policy

import com.openwarden.parent.crypto.FakeSecureKeyStorage
import com.openwarden.parent.crypto.NotProvisionedRootKeyProvider
import com.openwarden.parent.crypto.PolicySigner
import com.openwarden.parent.crypto.RootKeyDerivation
import com.openwarden.parent.crypto.StoredRootKeyProvider
import com.openwarden.parent.crypto.bip39.Bip39
import com.openwarden.proto.Policy
import com.openwarden.proto.PolicyBundle
import com.openwarden.proto.SigningInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

    // Production-shaped wire config (must match PolicySender.json). encodeDefaults=true is load-bearing.
    private val wireJson =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    // The #157 defect shape for the bundle: encodeDefaults DROPPED (explicitNulls held false to isolate it).
    private val noDefaultsJson = Json { explicitNulls = false }

    @Test
    fun signedBundleVerifiesOverTheTransmittedWireBytes() {
        // #160 / ADR-047 D4: verify the REAL Ed25519 signature over the bytes actually transmitted
        // (serialize → parse → canonicalize as the child does per ADR-040), not a re-derivation from the
        // typed model. Proves the wire the parent sends is the wire the child accepts.
        val provider = provisionedProvider()
        val signed = assertNotNull(SignedBundleAssembler.assemble(unsigned(), provider))
        val pub = assertNotNull(provider.rootPublicKey())

        val wire = wireJson.encodeToString(PolicyBundle.serializer(), signed)
        assertTrue(wire.contains("\"v\":1"), "transmitted wire must carry v:1")
        val wireBytes = SigningInput.forDocument(Json.parseToJsonElement(wire).jsonObject)
        assertTrue(verifiesUnder(pub, signed.sig!!, wireBytes), "real signature must verify over the transmitted wire bytes")
    }

    @Test
    fun droppingDefaultsOnTheWireBreaksTheRealSignature() {
        // The negative twin: a wire serialized WITHOUT encodeDefaults drops `v` + the empty policy lists,
        // so the child canonicalizes different bytes and the real signature FAILS — exactly #157, but for
        // the bundle. This is what the invariant (ADR-047 D1) prevents.
        val provider = provisionedProvider()
        val signed = assertNotNull(SignedBundleAssembler.assemble(unsigned(), provider))
        val pub = assertNotNull(provider.rootPublicKey())

        val bad = noDefaultsJson.encodeToString(PolicyBundle.serializer(), signed)
        assertFalse(bad.contains("\"v\":"), "no-encodeDefaults wire must drop v")
        val badBytes = SigningInput.forDocument(Json.parseToJsonElement(bad).jsonObject)
        assertFalse(verifiesUnder(pub, signed.sig!!, badBytes), "signature must NOT verify over a defaults-dropped wire")
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
