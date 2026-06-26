package com.openwarden.child

import net.i2p.crypto.eddsa.EdDSAEngine
import java.security.MessageDigest

/**
 * Test double for [ChildKeyStore] (ADR-032), standing in for [KeystoreChildKeys] — which needs real
 * StrongBox/TEE and so cannot run on the JVM or emulator. `K_bind` is a synthetic P-256 keypair
 * ([P256TestSigner]), `K_id` a synthetic Ed25519 keypair ([CommandTestSigner]), and `K_enc` a fixed
 * 32-byte X25519 public key (the binding signs over its bytes; tests need no real X25519 keygen).
 *
 * Models the fail-closed pre-provision state: every accessor returns `null` until [provision] runs.
 * Test-source only.
 */
class FakeChildKeyStore(
    private val bind: P256TestSigner.Keypair = P256TestSigner.newKeypair(),
    private val id: CommandTestSigner.Keypair = CommandTestSigner.newKeypair(),
    encPub: ByteArray = ByteArray(32) { (it + 1).toByte() },
) : ChildKeyStore {
    private val encPub: ByteArray = encPub.copyOf() // defensive copy: caller can't mutate post-construction
    private var provisioned = false
    var lastNonce: ByteArray? = null
        private set

    override fun isProvisioned(): Boolean = provisioned

    override fun provision(nonce: ByteArray) {
        provisioned = true
        lastNonce = nonce.copyOf()
    }

    override fun identityPublicKey(): ByteArray? = if (provisioned) id.pubRaw else null

    override fun signIdentity(message: ByteArray): ByteArray? {
        if (!provisioned) return null
        return EdDSAEngine(MessageDigest.getInstance("SHA-512")).run {
            initSign(id.priv)
            update(message)
            sign()
        }
    }

    override fun encryptionPublicKey(): ByteArray? = if (provisioned) encPub.copyOf() else null

    override fun bindingPublicKey(): ByteArray? = if (provisioned) bind.spkiDer else null

    override fun signBinding(message: ByteArray): ByteArray? = if (provisioned) P256TestSigner.signDer(message, bind) else null

    override fun attestationChain(): List<ByteArray>? =
        if (provisioned) listOf(bind.spkiDer) else null // a non-empty synthetic chain; real chain is bench-only

    /** The synthetic `K_bind` SPKI, for driving [ChildKeyBindingVerifier.verify] in tests. */
    fun bindingSpki(): ByteArray = bind.spkiDer
}
