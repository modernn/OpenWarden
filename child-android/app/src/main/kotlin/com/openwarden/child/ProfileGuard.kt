package com.openwarden.child

import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * Watchdog surface for **profile-escape detection** (ADR-022 / issue #12).
 *
 * Blocking the *creation* of a managed/private profile is the Day-One restriction baseline's job
 * (`DISALLOW_ADD_MANAGED_PROFILE` always, `DISALLOW_ADD_PRIVATE_PROFILE` on API 35+ — see
 * [PolicyEnforcer.requiredRestrictions]). This guard is the **detection backstop**: if a profile
 * appears anyway (a pre-existing one, or a window before the restriction stuck), the watchdog
 * notices the profile count climbing above the baseline and surfaces it.
 *
 * Honest scope (ADR-022): with no event log / parent transport yet, "surface" is a local
 * containment **log** plus the watchdog's existing re-assert of the blocking restrictions on the
 * same tick. A parent-facing alert lands when the event log does — the same staged honesty as
 * ADR-020's "FRP implemented but not yet wired".
 *
 * The count source is injected as a seam so the trip logic is deterministically testable without
 * a live device. [forContext] wires the real [UserManager.getUserProfiles].
 */
class ProfileGuard(
    private val profileCount: () -> Int,
    private val baseline: Int = BASELINE_PROFILES,
    private val onExtraProfile: (Int) -> Unit = { count ->
        Log.w(
            TAG,
            "Extra user profile detected (count=$count > baseline=$baseline) — possible Private " +
                "Space / managed-profile escape (B1). Blocking restrictions re-asserted this tick; " +
                "parent alert pending the event log.",
        )
    },
) {

    /**
     * Check the current profile count against the baseline. Returns true (and invokes
     * [onExtraProfile]) when an extra profile is present. Pure-ish: a failure to read the count
     * propagates to the caller, which the watchdog guards with `runCatching` like every surface.
     */
    fun check(): Boolean {
        val count = profileCount()
        return if (count > baseline) {
            onExtraProfile(count)
            true
        } else {
            false
        }
    }

    companion object {
        const val TAG = "OpenWardenProfileGuard"

        /** A locked-down child device has exactly one profile: the primary user. */
        const val BASELINE_PROFILES = 1

        /** Wire the guard to the real [UserManager] profile count. */
        fun forContext(context: Context): ProfileGuard {
            val um = context.getSystemService(Context.USER_SERVICE) as UserManager
            return ProfileGuard(profileCount = { um.userProfiles.size })
        }
    }
}
