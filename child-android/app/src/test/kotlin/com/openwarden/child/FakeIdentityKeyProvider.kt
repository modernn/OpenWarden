package com.openwarden.child

import net.i2p.crypto.eddsa.EdDSAEngine
import java.security.MessageDigest

/**
 * Test double for [IdentityKeyProvider] backed by a synthetic Ed25519 keypair (via [CommandTestSigner]),
 * standing in for the StrongBox child identity key issue #22 will provide. A `null` keypair models the
 * pre-pairing state ([NotProvisionedIdentityKeyProvider]). Test-source only.
 */
class FakeIdentityKeyProvider(
    private val kp: CommandTestSigner.Keypair?,
) : IdentityKeyProvider {
    override fun identityPublicKey(): ByteArray? = kp?.pubRaw

    override fun sign(message: ByteArray): ByteArray? {
        val k = kp ?: return null
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(k.priv)
        engine.update(message)
        return engine.sign()
    }

    companion object {
        fun withNewKey() = FakeIdentityKeyProvider(CommandTestSigner.newKeypair())

        fun notProvisioned() = FakeIdentityKeyProvider(null)
    }
}
