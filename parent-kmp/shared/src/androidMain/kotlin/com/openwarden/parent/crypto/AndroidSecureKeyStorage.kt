package com.openwarden.parent.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Real Android [SecureKeyStorage] (ADR-033 D6): the serialized root keys are AES-GCM-wrapped in
 * EncryptedSharedPreferences under a StrongBox-backed MasterKey, with user authentication required
 * (CRYPTO.md §1 `requireUserAuthentication=true`). StrongBox is best-effort (the platform falls back
 * to TEE on devices without it).
 *
 * NOTE (surfaced for review): `setUserAuthenticationRequired(true)` means every read/write needs a
 * fresh device unlock. That is correct for a recovery-grade root authority but interacts with #27's
 * background bundle signing — whether the bundle-signing path uses this auth-gated root key directly
 * or a derived session key is a follow-up decision, not settled here.
 *
 * Exercised on-device (androidInstrumentedTest); the fail-closed provider logic is host-tested
 * against an in-memory [SecureKeyStorage] double.
 */
class AndroidSecureKeyStorage(
    context: Context,
) : SecureKeyStorage {
    private val prefs by lazy {
        val masterKey =
            MasterKey
                .Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .setUserAuthenticationRequired(true)
                .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun write(blob: ByteArray) {
        prefs.edit().putString(KEY, blob.toHex()).commit()
    }

    override fun read(): ByteArray? = prefs.getString(KEY, null)?.fromHex()

    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val MASTER_KEY_ALIAS = "openwarden-parent-root"
        const val PREFS_FILE = "openwarden_parent_root_keys"
        const val KEY = "root_keys_v1"

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
