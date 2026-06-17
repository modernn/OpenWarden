package com.openwarden.child

import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * Watchdog surface for **profile-escape detection + containment** (ADR-022 / issue #12).
 *
 * Blocking the *creation* of a managed/private profile is the Day-One restriction baseline's job
 * (`DISALLOW_ADD_MANAGED_PROFILE` always, `DISALLOW_ADD_PRIVATE_PROFILE` on API 35+ — see
 * [PolicyEnforcer.requiredRestrictionsForSdk]). This guard is the **detection + fail-closed
 * backstop**: a profile that exists anyway (one that pre-dated our Device Owner, or slipped in
 * during a window before the restriction stuck) is a *full allowlist bypass* — anything can run
 * inside it. So when the profile count climbs above the baseline this guard both logs a
 * containment warning **and locks the device** ([contain]), the same fail-closed `lockNow()`
 * response every other surface uses on a gap (ADR-020 / ADR-022 D2).
 *
 * Honest scope (ADR-022 D3): with no event log / parent transport yet, there is no parent-facing
 * alert and we do **not** attempt to *remove* the rogue profile (Private Space removal under
 * Device Owner is unverified and potentially destructive). Containment is local: log + lock, on
 * every tick the extra profile persists, until it is resolved. A parent alert lands when the
 * event log does — the same staged honesty as ADR-020's "FRP implemented but not yet wired".
 *
 * The count source and the containment action are injected as seams so the trip logic is
 * deterministically testable without a live device. [forContext] wires the real
 * [UserManager.getUserProfiles] and [PolicyEnforcer.lockNow].
 */
class ProfileGuard(
    private val profileCount: () -> Int,
    private val baseline: Int = BASELINE_PROFILES,
    private val onExtraProfile: (Int) -> Unit = { count ->
        Log.w(
            TAG,
            "Extra user profile detected (count=$count > baseline=$baseline) — possible Private " +
                "Space / managed-profile escape (B1). Locking device for containment; parent alert " +
                "pending the event log.",
        )
    },
    private val contain: () -> Unit = {},
) {

    /**
     * Check the current profile count against the baseline. On an extra profile, fire
     * [onExtraProfile] (record) then [contain] (lock), and return true. Returns false only when the
     * device is *verifiably* at the single-user baseline.
     *
     * Fail-closed on a read error (F2): if [profileCount] throws we cannot prove the device is at
     * the baseline, so we **contain** (lock) rather than let an unknown — possibly extra — profile
     * stay usable. The watchdog would otherwise just swallow-and-log the throw, leaving it usable.
     */
    fun check(): Boolean {
        val count = try {
            profileCount()
        } catch (e: Exception) {
            Log.e(TAG, "profile count read failed — cannot prove baseline, containing: ${e.message}")
            contain()
            return true
        }
        if (count <= baseline) return false
        onExtraProfile(count)
        contain()
        return true
    }

    companion object {
        const val TAG = "OpenWardenProfileGuard"

        /** A locked-down child device has exactly one profile: the primary user. */
        const val BASELINE_PROFILES = 1

        /** Wire the guard to the real profile count and the fail-closed `lockNow()` containment. */
        fun forContext(context: Context): ProfileGuard {
            val um = context.getSystemService(Context.USER_SERVICE) as UserManager
            return ProfileGuard(
                profileCount = { um.userProfiles.size },
                contain = {
                    runCatching { PolicyEnforcer(context).lockNow() }
                        .onFailure { Log.e(TAG, "lockNow() containment failed: ${it.message}") }
                },
            )
        }
    }
}
