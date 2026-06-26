package com.openwarden.parent.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * [RootKeyProvider] backed by [SecureKeyStorage] (ADR-033 D4/D6). Reads the serialized [RootKeys]
 * on each call; returns `null` everywhere when storage is empty (fail-closed). Signs with Bouncy
 * Castle Ed25519 over the stored seed — RFC 8032, so the signature verifies under the child's
 * libsodium verifier, and the same stored seed feeds libsodium bundle signing in #27.
 */
class StoredRootKeyProvider(
    private val storage: SecureKeyStorage,
) : RootKeyProvider {
    private fun load(): RootKeys? = storage.read()?.let { RootKeys.deserialize(it) }

    override fun rootPublicKey(): ByteArray? = load()?.ed25519Public

    override fun encryptionPublicKey(): ByteArray? = load()?.x25519Public

    override fun sign(message: ByteArray): ByteArray? {
        val keys = load() ?: return null
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(keys.ed25519Seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** True iff a confirmed root key is persisted. */
    fun isProvisioned(): Boolean = storage.read() != null

    companion object {
        /**
         * Persist [keys] AFTER the confirm-back gate reaches Confirmed (ADR-033 D7). The caller MUST
         * NOT invoke this until [ConfirmGate.State.Confirmed].
         */
        fun provision(
            storage: SecureKeyStorage,
            keys: RootKeys,
        ) {
            storage.write(keys.serialize())
        }
    }
}
