package com.openwarden.parent.policy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openwarden.parent.pairing.Base64Url
import com.openwarden.parent.pairing.PinnedChild
import com.openwarden.parent.pairing.PinnedChildStore

/**
 * Persisted pinned child (ADR-034 D4 + ADR-039). Holds **both** the `child_ed25519_pub` (identity /
 * `child_device_id` — what [pairedChildId] returns for the signed-bundle sender) and the
 * `child_x25519_pub` (the sealed-box event audience, ADR-015), committed together at pairing (#98) via
 * [pin]. Returns unpaired until pairing pins a child.
 *
 * The pinned keys are not secret, but they share the parent's StrongBox-backed encrypted-at-rest store
 * for tamper-resistance (a flipped audience id is a fail-closed rejection at the child, not a silent
 * mis-address). Implements [PinnedChildStore] write-once + atomic semantics (ADR-039 D2/D3).
 */
class AndroidPairedChildStore(
    context: Context,
) : PairedChildStore,
    PinnedChildStore {
    private val prefs by lazy {
        val masterKey =
            MasterKey
                .Builder(context, MASTER_KEY_ALIAS)
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

    /** base64url child Ed25519 pubkey (`child_device_id`, ADR-034 D4), or `null` if no child is pinned. */
    override fun pairedChildId(): String? = prefs.getString(KEY_CHILD_ED, null)

    /**
     * The pinned child, or `null` if unpaired. Fail-closed (ADR-039 D2/D6): returns non-null **only**
     * when both keys are present and decode to exactly 32 bytes — a lone or malformed key reads as
     * unpaired (no half-pin), never a truncated/over-long array.
     */
    override fun pinnedChild(): PinnedChild? {
        val ed = Base64Url.decode32(prefs.getString(KEY_CHILD_ED, null) ?: return null) ?: return null
        val x = Base64Url.decode32(prefs.getString(KEY_CHILD_X, null) ?: return null) ?: return null
        return PinnedChild(ed, x)
    }

    /**
     * Pin both keys atomically (ADR-039 D1/D2/D3). Write-once: refuses if a child is already pinned
     * (rotation is recovery-gated, §7.5/D8 — not an overwrite). Both keys go in a single `commit()`
     * (synchronous, all-or-nothing), so a write failure leaves nothing pinned. Fail-closed: throws on a
     * second-pin attempt or a failed commit.
     */
    @Synchronized
    override fun pin(child: PinnedChild) {
        // Hard-floor write-once backstop (mirrors PairingPinCoordinator's clean AlreadyPaired path).
        check(prefs.getString(KEY_CHILD_ED, null) == null && prefs.getString(KEY_CHILD_X, null) == null) {
            "child already pinned — re-pair requires the recovery phrase + 24-h delay (fail-closed)"
        }
        val committed =
            prefs
                .edit()
                .putString(KEY_CHILD_ED, Base64Url.encode(child.ed25519Pub))
                .putString(KEY_CHILD_X, Base64Url.encode(child.x25519Pub))
                .commit() // single atomic commit: both keys or neither (no half-pin).
        check(committed) { "pinned child commit() failed (fail-closed)" }
    }

    private companion object {
        const val MASTER_KEY_ALIAS = "openwarden-parent-paired-child"
        const val PREFS_FILE = "openwarden_parent_paired_child"
        const val KEY_CHILD_ED = "paired_child_ed25519"
        const val KEY_CHILD_X = "paired_child_x25519"
    }
}
