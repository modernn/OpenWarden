package com.openwarden.parent.crypto

/**
 * The parent's root authority (ADR-033 / CRYPTO.md §1). Mirrors the child's
 * [com.openwarden.child.IdentityKeyProvider] seam: every accessor is nullable and the
 * not-provisioned default returns `null` everywhere (fail-closed — no key, no signature).
 */
interface RootKeyProvider {
    /** Ed25519 root public key (32 raw bytes), or `null` before the phrase is confirmed. */
    fun rootPublicKey(): ByteArray?

    /** X25519 root encryption public key (32 raw bytes), or `null` before confirm. */
    fun encryptionPublicKey(): ByteArray?

    /** Ed25519-sign [message] with the root key; `null` if not provisioned (fail-closed). */
    fun sign(message: ByteArray): ByteArray?
}

/** Fail-closed default until the recovery phrase is generated + confirmed (ADR-033 D6/D7). */
object NotProvisionedRootKeyProvider : RootKeyProvider {
    override fun rootPublicKey(): ByteArray? = null
    override fun encryptionPublicKey(): ByteArray? = null
    override fun sign(message: ByteArray): ByteArray? = null
}
