package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Wraps DevicePolicyManager. All policy mutations go through here so we have a single
 * audit point. NOTE: only callable when this app is Device Owner.
 *
 * ## Fail-closed contract (ADR-020 / ADR-022)
 * [applyDayOneRestrictions] never RETURNS in a partially-unrestricted state. It applies the
 * full baseline ([requiredRestrictions]), reads every restriction back via [UserManager], and
 * **throws** [RestrictionEnforcementException] if any required restriction is not verifiably set.
 * On that throw it also calls [DevicePolicyManager.lockNow] as a last-resort containment so a
 * half-locked device is not left usable. The previous implementation logged-and-continued on a
 * per-restriction failure — that was a fail-OPEN gap and is the bug ADR-020 closes.
 *
 * [applyAllowlist] enforces the same fail-closed shape for **deny-by-default launch** (ADR-022):
 * it suspends every non-allowlisted, non-exempt user app, escalates to *hiding* anything that
 * resists suspension, reads the launch-blocked state back, and on any deny-target that stays
 * launchable it locks the device and throws [AllowlistEnforcementException].
 *
 * @param context Device-Owner app context.
 * @param isRestrictionSet readback seam — defaults to the DO-authoritative
 *   [DevicePolicyManager.getUserRestrictions] for this admin (what *we* set, not the effective
 *   union of all sources). Injectable so tests can drive the verify path deterministically.
 * @param installedApps seam — the installed packages (excluding self) with their system flag.
 *   Injectable so allowlist tests do not depend on the host's real package list.
 * @param isLaunchBlocked readback seam — true when a package is verifiably un-launchable
 *   (suspended OR hidden) for this admin. The allowlist verify path reads through this.
 * @param alwaysExempt extra packages that must never be suspended (e.g. the active default
 *   launcher). System apps and self are exempt unconditionally; this adds to that set.
 *   **Re-evaluated on every [applyAllowlist] call** so a launcher change between watchdog ticks
 *   cannot leave a stale exemption (the now-non-default launcher must become a deny target).
 * @param lock the fail-closed containment action (default `dpm.lockNow()`). Routed through a seam
 *   so the lock half of the fail-closed contract is independently testable.
 */
class PolicyEnforcer(
    private val context: Context,
    private val isRestrictionSet: (String) -> Boolean = defaultRestrictionReader(context),
    private val installedApps: () -> List<InstalledApp> = defaultInstalledAppsReader(context),
    private val isLaunchBlocked: (String) -> Boolean = defaultLaunchBlockedReader(context),
    private val alwaysExempt: () -> Set<String> = defaultExemptReader(context),
    private val lock: () -> Unit = defaultLockAction(context),
) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = AdminReceiver.componentName(context)

    /**
     * The canonical Day-One user-restriction baseline for *this* device's OS level — the
     * ADR-020 ship set plus the ADR-022 profile-escape block. API-aware; see
     * [requiredRestrictionsForSdk] for the full composition and the per-OS rationale.
     */
    val requiredRestrictions: List<String> = requiredRestrictionsForSdk(Build.VERSION.SDK_INT)

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
     * Deny-by-default launch enforcement (ADR-022 / issue #12). Suspends every installed user app
     * that is not on [allowlist] and not exempt, escalates to **hiding** anything that resists
     * suspension, then **verifies and fails closed**:
     *
     *  1. Exempt set = system apps + self + [alwaysExempt] (the active launcher). Suspending these
     *     would brick the device and they are not the threat surface (arbitrary user-installed,
     *     cloned, dual-app, or sideloaded packages are).
     *  2. Allowlisted apps are un-suspended / un-hidden (idempotent: a now-allowed app becomes
     *     launchable again).
     *  3. Deny targets are suspended; whatever the suspend call reports it could not suspend is
     *     escalated to `setApplicationHidden(true)` — strictly stronger (gone from the launcher).
     *  4. Readback via [isLaunchBlocked]. Any deny target still launchable is a fail-OPEN gap —
     *     `lockNow()` containment then throw [AllowlistEnforcementException], mirroring ADR-020.
     *
     * Idempotent and safe to call on every bundle apply and every watchdog tick.
     *
     * @throws IllegalArgumentException if this app is not Device Owner.
     * @throws AllowlistEnforcementException if any deny-target app is still launchable after apply.
     */
    fun applyAllowlist(allowlist: Set<String>): AllowlistResult {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "Not Device Owner — cannot enforce the app allowlist"
        }

        // (1) Enumerate. If we cannot read the installed-package list we cannot prove the deny set
        // is contained — that is a fail-OPEN read error, so contain (lockNow) and throw rather than
        // silently skip enforcement (the watchdog would otherwise swallow a bare throw and leave
        // the prior launch state in place). Same fail-closed shape as a verify gap.
        val apps = try {
            installedApps()
        } catch (e: Exception) {
            runCatching { lock() }.onFailure { Log.e(TAG, "lockNow() containment failed: ${it.message}") }
            throw AllowlistEnforcementException(
                emptyList(),
                "could not enumerate installed packages — cannot prove allowlist containment: ${e.message}",
            )
        }
        val pkgs = apps.map { it.pkg }
        // Re-evaluate the exempt set every call (F3): the active launcher can change between ticks,
        // and a now-non-default launcher must NOT keep a stale exemption.
        val exempt = apps.filter { it.isSystem }.map { it.pkg }.toSet() + alwaysExempt() + context.packageName

        val denyTargets = pkgs.filterNot { it in allowlist }.filterNot { it in exempt }
        val allowTargets = pkgs.filter { it in allowlist }

        // (2) Restore allowlisted apps — a previously-blocked app that is now allowed must launch.
        // setPackagesSuspended(false) returns the packages it could NOT un-suspend; surface them so
        // a now-allowed app silently stuck blocked is at least visible (over-restriction, not a leak).
        val failedUnsuspend =
            runCatching { dpm.setPackagesSuspended(admin, allowTargets.toTypedArray(), false).toList() }
                .getOrElse { Log.e(TAG, "unsuspend allowlisted threw: ${it.message}"); emptyList() }
        if (failedUnsuspend.isNotEmpty()) Log.w(TAG, "allowlisted apps still suspended (could not un-suspend): $failedUnsuspend")
        allowTargets.forEach { pkg ->
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                .onFailure { Log.e(TAG, "unhide allowlisted $pkg failed: ${it.message}") }
        }

        // (3) Deny-by-default: suspend the deny set. setPackagesSuspended returns the packages it
        // could NOT suspend; if the whole call throws, treat all deny targets as un-contained so
        // the escalation + verify path still runs (fail toward more restriction).
        val failedSuspend: List<String> =
            runCatching { dpm.setPackagesSuspended(admin, denyTargets.toTypedArray(), true).toList() }
                .getOrElse {
                    Log.e(TAG, "setPackagesSuspended threw: ${it.message}")
                    denyTargets
                }

        // Escalate: a cloned/sideloaded app that ignores suspension must not stay launchable.
        failedSuspend.forEach { pkg ->
            runCatching { dpm.setApplicationHidden(admin, pkg, true) }
                .onFailure { Log.e(TAG, "hide escalation failed for $pkg: ${it.message}") }
        }

        // (4) Verify-or-throw. Any deny target neither suspended nor hidden can still launch.
        val stillLaunchable = denyTargets.filterNot { isLaunchBlocked(it) }
        if (stillLaunchable.isNotEmpty()) {
            runCatching { lock() }
                .onFailure { Log.e(TAG, "lockNow() containment failed: ${it.message}") }
            throw AllowlistEnforcementException(stillLaunchable)
        }

        return AllowlistResult(blocked = denyTargets, exempt = pkgs.filter { it in exempt })
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

        /**
         * `DISALLOW_ADD_PRIVATE_PROFILE` (Android 15 / API 35 "Private Space"). Pinned by its
         * stable AOSP string key so the source compiles and reads identically regardless of the
         * compile SDK, mirroring [DISALLOW_OEM_UNLOCK]. Only ever ADDED to the required set on
         * API 35+ (see [requiredRestrictions]) so it can never trip the fail-closed lock on an
         * OS that has no Private Space to block.
         */
        const val DISALLOW_ADD_PRIVATE_PROFILE = "no_add_private_profile"

        /** Android 15 / API 35 — first OS with a Private Space to block. */
        const val PRIVATE_SPACE_MIN_SDK = 35

        /**
         * The required Day-One restriction set for a given OS level (ADR-020 baseline + ADR-022
         * profile-escape block). Pure and **sdk-parameterized** so the API-gated composition is
         * unit-testable without Robolectric having to emulate a specific platform (the repo's
         * Robolectric tops out below API 35). The String constants are compile-time inlined, so
         * this needs no live Android runtime.
         *
         * The first 17 are DEFENSES.md row 2 (the v1 ship set). Then:
         *  - `DISALLOW_ADD_MANAGED_PROFILE` — always (API 21+); blocks a work-profile escape. It
         *    carries a Java `@Deprecated` marker, but the restriction bit is still recorded and
         *    read back by DevicePolicyManager, so verify-or-throw stays valid (the deprecation is
         *    a compile-time annotation, not a runtime no-op).
         *  - `DISALLOW_ADD_PRIVATE_PROFILE` — **[PRIVATE_SPACE_MIN_SDK]+ only** (Android 15 Private
         *    Space). Runtime-conditional: applying an unknown restriction key on an older OS would
         *    never read back set, and that gap would trip the fail-closed `lockNow()` on every boot
         *    — i.e. brick a pre-15 device. So the required set itself is API-aware.
         *
         * NEVER add `DISALLOW_OUTGOING_CALLS` — the emergency dialer must remain reachable.
         * `DISALLOW_CONFIG_PRIVATE_DNS` is intentionally NOT here: the DNS fail-closed floor is
         * owned by issue #19 (ADR-016) so the DNS story lives in one place.
         */
        @Suppress("DEPRECATION")
        fun requiredRestrictionsForSdk(sdkInt: Int): List<String> = buildList {
            add(UserManager.DISALLOW_FACTORY_RESET)
            add(UserManager.DISALLOW_SAFE_BOOT)
            add(UserManager.DISALLOW_DEBUGGING_FEATURES)
            add(UserManager.DISALLOW_CONFIG_VPN)
            add(UserManager.DISALLOW_MODIFY_ACCOUNTS)
            add(DISALLOW_OEM_UNLOCK)
            add(UserManager.DISALLOW_APPS_CONTROL)
            add(UserManager.DISALLOW_USB_FILE_TRANSFER)
            add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            add(UserManager.DISALLOW_USER_SWITCH)
            add(UserManager.DISALLOW_ADD_USER)
            add(UserManager.DISALLOW_REMOVE_USER)
            add(UserManager.DISALLOW_CONFIG_DATE_TIME)
            add(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            add(UserManager.DISALLOW_CONFIG_TETHERING)
            add(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            add(UserManager.DISALLOW_OUTGOING_BEAM)
            // ADR-022 profile-escape block.
            add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
            if (sdkInt >= PRIVATE_SPACE_MIN_SDK) {
                add(DISALLOW_ADD_PRIVATE_PROFILE)
            }
        }

        /**
         * Default readback seam: the **DO-authoritative** restriction state via
         * [DevicePolicyManager.getUserRestrictions] for this admin. This reads back exactly
         * what *our* Device Owner set — NOT [UserManager.hasUserRestriction], which reports the
         * effective restriction from any source (base/system/another admin) and could falsely
         * report success when our `addUserRestriction(admin, …)` did not actually stick.
         */
        private fun defaultRestrictionReader(context: Context): (String) -> Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = AdminReceiver.componentName(context)
            return { key -> dpm.getUserRestrictions(admin).getBoolean(key, false) }
        }

        /** Default installed-apps seam: every installed package except self, with its system flag. */
        private fun defaultInstalledAppsReader(context: Context): () -> List<InstalledApp> = {
            val self = context.packageName
            context.packageManager.getInstalledPackages(0)
                .filter { it.packageName != self }
                .map { info ->
                    val isSystem = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
                    InstalledApp(info.packageName, isSystem)
                }
        }

        /**
         * Default launch-blocked seam: a package is launch-blocked for this admin when it is
         * suspended OR hidden. Reads the DO-authoritative state; any lookup failure
         * (e.g. package gone) is treated as not-blocked so the verify path stays conservative.
         */
        private fun defaultLaunchBlockedReader(context: Context): (String) -> Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = AdminReceiver.componentName(context)
            return { pkg ->
                val suspended = runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
                val hidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
                suspended || hidden
            }
        }

        /**
         * Default exempt seam: re-resolves the **active default launcher** on each call (so a
         * launcher change between watchdog ticks can't leave a stale exemption, F3). Suspending the
         * live home app would brick the device and it is not the deny-by-default threat surface.
         * System apps and self are exempt unconditionally inside [applyAllowlist]; this only adds
         * the launcher.
         *
         * Honesty caveat (ADR-022): if the kid has set a *non-allowlisted third-party launcher*
         * as default, it stays usable here — enforcing the stock launcher is DEFENSES Kid §3.4,
         * tracked separately, not part of this deny-by-default change.
         */
        private fun defaultExemptReader(context: Context): () -> Set<String> = {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val default = context.packageManager
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            setOfNotNull(default)
        }

        /** Default fail-closed containment action: lock the device via this admin's `lockNow()`. */
        private fun defaultLockAction(context: Context): () -> Unit {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return { dpm.lockNow() }
        }
    }
}

/** An installed package and whether it is a system app (system apps are never suspended). */
data class InstalledApp(val pkg: String, val isSystem: Boolean)

/**
 * Outcome of [PolicyEnforcer.applyAllowlist] on success (it throws on a fail-closed gap):
 * [blocked] = the deny-by-default targets that were suspended/hidden; [exempt] = installed
 * packages that were left alone (system / self / active launcher).
 */
data class AllowlistResult(val blocked: List<String>, val exempt: List<String>)

/**
 * Thrown when the Day-One restriction baseline cannot be verified fully set. Carries the
 * list of restrictions that are still missing so the caller / logs can see exactly what
 * failed to lock down.
 */
class RestrictionEnforcementException(val missing: List<String>) :
    IllegalStateException("Fail-closed: required user restrictions not verifiably set: $missing")

/**
 * Thrown when deny-by-default launch enforcement cannot prove the deny set is contained — either a
 * deny target is still launchable after suspend + hide-escalation ([stillLaunchable] names them),
 * or the installed-package list could not be enumerated at all. The device is locked (best-effort)
 * before this is thrown, mirroring the Day-One fail-closed containment.
 */
class AllowlistEnforcementException(
    val stillLaunchable: List<String>,
    reason: String = "non-allowlisted apps still launchable after apply: $stillLaunchable",
) : IllegalStateException("Fail-closed (allowlist): $reason")
