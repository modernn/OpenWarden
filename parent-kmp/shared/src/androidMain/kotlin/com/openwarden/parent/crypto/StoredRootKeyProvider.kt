package com.openwarden.parent.crypto

import android.util.Log
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
    // #144 (crypto review Finding 2): a present-but-malformed blob (an interrupted/partial write, a
    // future format skew) makes RootKeys.deserialize() throw `require(size == 128)`. Unguarded, that
    // escaped rootPublicKey() straight into PairingSessionManager.start() and reproduced the #144 crash
    // one layer up — the same class of defect #144 set out to kill. Degrade a corrupt blob to "no usable
    // key" (fail-closed → not provisioned), logging loudly so corruption is observable, never silent.
    // Safe: the AES-GCM-authenticated store fails decryption (→ read() returns null) on external tamper,
    // so a valid-decrypt-but-wrong-size blob can only be our own bad write; and re-onboarding mints a NEW
    // identity the already-paired child will not trust — corruption is not a takeover path.
    private fun load(): RootKeys? =
        storage.read()?.let { blob ->
            runCatching { RootKeys.deserialize(blob) }
                .onFailure { Log.w(TAG, "stored root key is corrupt/unreadable — treating as not provisioned: ${it.message}") }
                .getOrNull()
        }

    override fun rootPublicKey(): ByteArray? = load()?.ed25519Public

    override fun encryptionPublicKey(): ByteArray? = load()?.x25519Public

    override fun sign(message: ByteArray): ByteArray? {
        val keys = load() ?: return null
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(keys.ed25519Seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * True iff a confirmed, **well-formed** root key is persisted. A present-but-corrupt blob reads
     * back as not provisioned (fail-closed, consistent with the accessors above — #144 Finding 2).
     */
    fun isProvisioned(): Boolean = load() != null

    companion object {
        private const val TAG = "OpenWardenRootKeys"

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
