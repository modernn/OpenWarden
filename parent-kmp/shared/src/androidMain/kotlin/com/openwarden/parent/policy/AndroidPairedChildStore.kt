package com.openwarden.parent.policy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted pinned child id (ADR-034 D4). Returns `null` until pairing (#23) pins a child via
 * [setPairedChild]. The pinned `child_device_id` is not secret, but it shares the parent's
 * encrypted-at-rest store for tamper-resistance (a flipped audience id is a fail-closed rejection at
 * the child, not a silent mis-address).
 */
class AndroidPairedChildStore(context: Context) : PairedChildStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun pairedChildId(): String? = prefs.getString(KEY_CHILD_ID, null)

    /** Called by pairing (#23) once the child Ed25519 pubkey is pinned. Fail-closed on write failure. */
    fun setPairedChild(childDeviceId: String) {
        check(prefs.edit().putString(KEY_CHILD_ID, childDeviceId).commit()) {
            "paired child commit() failed (fail-closed)"
        }
    }

    private companion object {
        const val MASTER_KEY_ALIAS = "openwarden-parent-paired-child"
        const val PREFS_FILE = "openwarden_parent_paired_child"
        const val KEY_CHILD_ID = "paired_child_id"
    }
}
