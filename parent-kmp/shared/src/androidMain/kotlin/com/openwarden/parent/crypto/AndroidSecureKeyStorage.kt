package com.openwarden.parent.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
 * **Fail-closed when the store can't be opened (#144 / ADR-033 amendment).** Building the
 * user-authentication-required MasterKey **throws** `IllegalStateException("Secure lock screen must be
 * enabled …")` on a device with no secure lock screen. Per the [SecureKeyStorage] contract, [read]
 * and [clear] swallow that (and any other open failure) and degrade to "no key" — so the pairing flow
 * (`PairingSessionManager.start` → `rootPublicKey()` → here) falls to its graceful
 * `NotProvisioned` path instead of crashing the app. [write] refuses **loudly**
 * ([SecureStorageUnavailableException]) so provisioning never believes a key was stored when it was
 * not. The prefs are built lazily and **re-attempted after a failure**, so the store recovers once the
 * user sets a screen lock — without restarting the app.
 *
 * Exercised on-device (androidInstrumentedTest); the fail-closed open/read/write semantics are
 * host-tested by injecting a throwing [prefsFactory].
 */
class AndroidSecureKeyStorage internal constructor(
    private val prefsFactory: () -> SharedPreferences,
) : SecureKeyStorage {
    constructor(context: Context) : this({ createEncryptedPrefs(context) })

    // Cached only on SUCCESS (a failed open is re-attempted next call, so the store recovers once a
    // secure lock screen exists). @Synchronized: the root store may be touched from the UI and the
    // pairing network path.
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Synchronized
    private fun prefsOrNull(): SharedPreferences? {
        cachedPrefs?.let { return it }
        return runCatching { prefsFactory() }
            .onFailure { Log.w(TAG, "secure root-key store unavailable (no secure lock screen?): ${it.message}") }
            .getOrNull()
            ?.also { cachedPrefs = it }
    }

    /**
     * Throws [SecureStorageUnavailableException] (never the raw keystore exception) so a provisioning
     * flow can surface a clean "set a screen lock first" message instead of crashing.
     */
    override fun write(blob: ByteArray) {
        val prefs =
            prefsOrNull()
                ?: throw SecureStorageUnavailableException(
                    "Secure key storage is unavailable — set a device screen lock before creating the recovery key.",
                )
        prefs.edit().putString(KEY, blob.toHex()).commit()
    }

    /** Fail-closed: any open/read failure yields `null` (no usable key), never a crash (#144). */
    override fun read(): ByteArray? =
        runCatching { prefsOrNull()?.getString(KEY, null)?.fromHex() }
            .onFailure { Log.w(TAG, "secure root-key read failed: ${it.message}") }
            .getOrNull()

    /** Best-effort: a store that can't be opened has nothing to clear, so a failure is swallowed. */
    override fun clear() {
        runCatching { prefsOrNull()?.edit()?.remove(KEY)?.commit() }
            .onFailure { Log.w(TAG, "secure root-key clear failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "OpenWardenSecureStore"
        const val MASTER_KEY_ALIAS = "openwarden-parent-root"
        const val PREFS_FILE = "openwarden_parent_root_keys"
        const val KEY = "root_keys_v1"

        fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context, MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setRequestStrongBoxBacked(true)
                    .setUserAuthenticationRequired(true)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
