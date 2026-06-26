package com.openwarden.parent.policy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Durable parent `policy_seq` sequencer (ADR-034 D3). Mirrors the child `ReplayFloorStore` idiom:
 * `EncryptedSharedPreferences` under a StrongBox-backed `MasterKey`, `commit()` + readback,
 * fail-closed. [reserveNext] persists the new (strictly greater) seq BEFORE returning it, so the
 * parent never issues a seq it didn't durably record. `@Synchronized` for concurrent pushes.
 */
class AndroidPolicySeqStore(
    context: Context,
) : PolicySeqStore {
    private val prefs by lazy {
        val masterKey =
            MasterKey
                .Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                // Best-effort hardware binding; falls back to TEE where StrongBox is absent.
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

    @Synchronized
    override fun reserveNext(): Long {
        val last = prefs.getLong(KEY_LAST_SEQ, 0L) // 0 = none issued yet → first reserve = 1 (genesis)
        val next = last + 1
        check(next > last) { "policy_seq overflow at $last" }
        // commit() returns false on a failed durable write. Failing to detect that would let the
        // parent emit a bundle under a seq it never persisted — after a restart it could reissue the
        // same seq for different content. Fail closed: throw so no bundle is sent under this seq.
        check(prefs.edit().putLong(KEY_LAST_SEQ, next).commit()) {
            "policy_seq commit() failed for $next (fail-closed)"
        }
        val readback = prefs.getLong(KEY_LAST_SEQ, 0L)
        check(readback == next) { "policy_seq readback ($readback) != $next (fail-closed)" }
        return next
    }

    private companion object {
        const val MASTER_KEY_ALIAS = "openwarden-parent-seq"
        const val PREFS_FILE = "openwarden_parent_policy_seq"
        const val KEY_LAST_SEQ = "last_policy_seq"
    }
}
