package com.openwarden.parent.crypto

import com.openwarden.parent.crypto.bip39.Bip39
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fail-closed provider semantics over an in-memory storage double (ADR-033 D4/D6/D8). */
class StoredRootKeyProviderTest {
    private fun keys() = RootKeyDerivation.deriveRootKeys(Bip39.encode(ByteArray(32)))

    @Test
    fun failClosedBeforeProvision() {
        val provider = StoredRootKeyProvider(FakeSecureKeyStorage())
        assertNull(provider.rootPublicKey())
        assertNull(provider.encryptionPublicKey())
        assertNull(provider.sign(byteArrayOf(1, 2, 3)))
        assertFalse(provider.isProvisioned())
    }

    @Test
    fun providesKeysAfterProvision() {
        val storage = FakeSecureKeyStorage()
        val k = keys()
        StoredRootKeyProvider.provision(storage, k)
        val provider = StoredRootKeyProvider(storage)

        assertTrue(provider.isProvisioned())
        assertContentEquals(k.ed25519Public, provider.rootPublicKey())
        assertContentEquals(k.x25519Public, provider.encryptionPublicKey())
    }

    @Test
    fun signatureFromProviderVerifies() {
        val storage = FakeSecureKeyStorage()
        val k = keys()
        StoredRootKeyProvider.provision(storage, k)
        val provider = StoredRootKeyProvider(storage)

        val msg = "policy-bundle".encodeToByteArray()
        val sig = assertNotNull(provider.sign(msg))
        val verifier =
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(k.ed25519Public, 0))
                update(msg, 0, msg.size)
            }
        assertTrue(verifier.verifySignature(sig))
    }

    @Test
    fun clearedStorageIsFailClosedAgain() {
        val storage = FakeSecureKeyStorage()
        StoredRootKeyProvider.provision(storage, keys())
        storage.clear()
        val provider = StoredRootKeyProvider(storage)
        assertFalse(provider.isProvisioned())
        assertNull(provider.sign(byteArrayOf(9)))
    }
}
