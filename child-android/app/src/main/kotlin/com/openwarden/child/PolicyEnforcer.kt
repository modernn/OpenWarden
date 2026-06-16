package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Wraps DevicePolicyManager. All policy mutations go through here so we have a single
 * audit point. NOTE: only callable when this app is Device Owner.
 *
 * ## Fail-closed contract (ADR-020)
 * [applyDayOneRestrictions] never RETURNS in a partially-unrestricted state. It applies the
 * full canonical baseline ([requiredRestrictions]), reads every restriction back via
 * [UserManager], and **throws** [RestrictionEnforcementException] if any required restriction
 * is not verifiably set. On that throw it also calls [DevicePolicyManager.lockNow] as a
 * last-resort containment so a half-locked device is not left usable. The previous
 * implementation logged-and-continued on a per-restriction failure — that was a fail-OPEN
 * gap and is the bug this class closes.
 *
 * @param context Device-Owner app context.
 * @param isRestrictionSet readback seam — defaults to the real [UserManager]. Injectable so
 *   tests can drive the verify path deterministically without depending on whether the
 *   Robolectric shadow wires `addUserRestriction` through to `UserManager`.
 */
class PolicyEnforcer(
    private val context: Context,
    private val isRestrictionSet: (String) -> Boolean = defaultRestrictionReader(context),
) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = AdminReceiver.componentName(context)

    /**
     * The canonical Day-One user-restriction baseline — DEFENSES.md row 2, the v1 ship set.
     * This list IS the "strict baseline": there is no relaxed variant in v1, so fail-closed
     * means "the whole list verifiably set, or throw."
     *
     * Each entry maps to an attack class in ATTACKS.md / DEFENSES.md. Order is grouped
     * roughly by escape family (reset/boot, debug/sideload, accounts/users, transport).
     *
     * NEVER add `DISALLOW_OUTGOING_CALLS` — the emergency dialer must remain reachable.
     * `DISALLOW_CONFIG_PRIVATE_DNS` is intentionally NOT here: the whole DNS fail-closed
     * floor (pin the resolver + lock the toggle) is owned by issue #19 so the DNS story
     * lives in one place.
     */
    val requiredRestrictions: List<String> = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_CONFIG_VPN,
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        DISALLOW_OEM_UNLOCK,
        UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_USB_FILE_TRANSFER,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_USER_SWITCH,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_REMOVE_USER,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
        UserManager.DISALLOW_CONFIG_TETHERING,
        UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
        UserManager.DISALLOW_OUTGOING_BEAM,
    )

    /**
     * Apply the full Day-One baseline, then verify every restriction is set. Idempotent —
     * safe to call on first provision and again on every boot / watchdog tick to re-assert
     * after a tampering attempt.
     *
     * Fail-closed: tries to apply every restriction (more restriction is the safe direction
     * even if one entry fails), then [verifyOrThrow]. If verification finds any gap it locks
     * the device and rethrows — it NEVER returns normally while partially unrestricted.
     *
     * @throws IllegalArgumentException if this app is not Device Owner.
     * @throws RestrictionEnforcementException if any required restriction is not verifiably set.
     */
    fun applyDayOneRestrictions() {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "Not Device Owner — cannot enforce restrictions"
        }

        requiredRestrictions.forEach { key ->
            try {
                dpm.addUserRestriction(admin, key)
                Log.i(TAG, "Restriction applied: $key")
            } catch (e: Exception) {
                // Do NOT return here — keep applying the rest (fail toward more restriction),
                // then let verifyOrThrow() catch the gap. Swallowing-and-returning was the
                // old fail-OPEN bug.
                Log.e(TAG, "Failed to apply $key: ${e.message}")
            }
        }

        try {
            verifyOrThrow()
        } catch (e: RestrictionEnforcementException) {
            // Last-resort containment: a partially-unrestricted device must not stay usable.
            // runCatching so a lockNow failure can never mask the real enforcement exception.
            runCatching { dpm.lockNow() }
                .onFailure { Log.e(TAG, "lockNow() containment failed: ${it.message}") }
            throw e
        }
    }

    /** Required restrictions that are not currently set, per the [isRestrictionSet] readback. */
    fun missingRestrictions(): List<String> = requiredRestrictions.filterNot(isRestrictionSet)

    /**
     * Throws [RestrictionEnforcementException] if any required restriction is not verifiably
     * set. Pure check — no side effects — so it is deterministically testable.
     */
    fun verifyOrThrow() {
        val missing = missingRestrictions()
        if (missing.isNotEmpty()) throw RestrictionEnforcementException(missing)
    }

    /**
     * Apply allowlist via setPackagesSuspended. Apps not in allowlist become
     * visible-but-grayed with an admin message ("Ask dad").
     *
     * @return packages that failed to suspend (e.g. system pkgs)
     */
    fun applyAllowlist(allowlist: Set<String>): List<String> {
        require(dpm.isDeviceOwnerApp(context.packageName))
        val pm = context.packageManager
        val installed = pm.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it != context.packageName }   // never suspend self

        val toSuspend = installed.filterNot { it in allowlist }.toTypedArray()
        val toUnsuspend = installed.filter { it in allowlist }.toTypedArray()

        dpm.setPackagesSuspended(admin, toUnsuspend, false)
        return dpm.setPackagesSuspended(admin, toSuspend, true).toList()
    }

    /**
     * v1: best-effort wallpaper-style nag. v2: replace with proper PIN gate.
     */
    fun lockNow() {
        dpm.lockNow()
    }

    /**
     * Bind Factory Reset Protection to the parent's Google account(s). FRP survives a
     * fastboot wipe, so a kid who wipes the device cannot re-set-up without a bound account.
     *
     * @param accountIds obfuscated GAIA account IDs (NOT emails — the id from
     *   `GoogleAuthUtil.getAccountId()`). The parent app obtains and supplies these.
     *
     * Honesty caveat (ADR-020 / research/07): FRP reliably blocks reset only on Pixel-class
     * hardware with a locked bootloader. On much of Tier-2 (vendor unlock tools) FRP is
     * bypassable — this is a documented gap, mitigated separately by heartbeat-silence
     * alerts, not a guarantee.
     *
     * Fail-closed: refuses to ENABLE FRP with an empty account set (that would brick the
     * device with no recovery account). API 30+ only; older API logs and no-ops.
     *
     * @throws IllegalArgumentException if not Device Owner or [accountIds] is empty.
     */
    fun applyFrpAccounts(accountIds: List<String>) {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "Not Device Owner — cannot set FRP policy"
        }
        require(accountIds.isNotEmpty()) {
            "FRP requires at least one parent account id — refusing to enable with no recovery account"
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "FRP policy requires API 30+; running on ${Build.VERSION.SDK_INT} — not applied")
            return
        }
        val policy = FactoryResetProtectionPolicy.Builder()
            .setFactoryResetProtectionAccounts(accountIds)
            .setFactoryResetProtectionEnabled(true)
            .build()
        dpm.setFactoryResetProtectionPolicy(admin, policy)
        Log.i(TAG, "FRP policy applied for ${accountIds.size} account(s)")
    }

    companion object {
        const val TAG = "OpenWardenEnforcer"

        /**
         * `DISALLOW_OEM_UNLOCK` is a `@SystemApi`/hidden [UserManager] constant — not
         * referenceable from the public SDK — so we pin its stable AOSP string key. A Device
         * Owner can still set the restriction by key. Best-effort: reliably enforced only on
         * Pixel-class hardware with a locked bootloader; a silent no-op on much of Tier-2
         * (vendor unlock tools) per docs/research/07. Mitigated separately by heartbeat-silence
         * alerts, not a guarantee.
         */
        const val DISALLOW_OEM_UNLOCK = "no_oem_unlock"

        /** Default readback seam: the real per-user restriction state via [UserManager]. */
        private fun defaultRestrictionReader(context: Context): (String) -> Boolean {
            val um = context.getSystemService(Context.USER_SERVICE) as UserManager
            return { key -> um.hasUserRestriction(key) }
        }
    }
}

/**
 * Thrown when the Day-One restriction baseline cannot be verified fully set. Carries the
 * list of restrictions that are still missing so the caller / logs can see exactly what
 * failed to lock down.
 */
class RestrictionEnforcementException(val missing: List<String>) :
    IllegalStateException("Fail-closed: required user restrictions not verifiably set: $missing")
