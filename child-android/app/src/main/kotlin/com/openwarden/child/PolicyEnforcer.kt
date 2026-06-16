package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * Wraps DevicePolicyManager. All policy mutations go through here so we have a single
 * audit point. NOTE: only callable when this app is Device Owner.
 */
class PolicyEnforcer(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = AdminReceiver.componentName(context)

    /**
     * Restrictions we apply unconditionally on Day One. These are policy floor — even if the
     * signed policy bundle says otherwise, we never relax these. (Yet. v2 may allow parent to
     * temporarily lift specific ones, e.g. for debug sessions, gated by recovery phrase.)
     */
    fun applyDayOneRestrictions() {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "Not Device Owner — cannot enforce restrictions"
        }

        val day1 = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_OUTGOING_BEAM,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            // NEVER add DISALLOW_OUTGOING_CALLS — emergency dialer must remain.
        )
        day1.forEach {
            try {
                dpm.addUserRestriction(admin, it)
                Log.i(TAG, "Restriction applied: $it")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply $it: ${e.message}")
            }
        }
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
     * Set Factory Reset Protection account. FRP survives even a fastboot wipe.
     * accountIds: list of Google account IDs (NOT email — the obfuscated_gaia_id from
     * GoogleAuthUtil.getAccountId()). Parent must obtain these.
     */
    fun applyFrpAccounts(accountIds: List<String>) {
        require(dpm.isDeviceOwnerApp(context.packageName))
        // TODO(v1): use setFactoryResetProtectionPolicy on API 30+.
    }

    companion object {
        const val TAG = "OpenWardenEnforcer"
    }
}
