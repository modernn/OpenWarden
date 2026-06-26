package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log

/**
 * Applies lock-screen and keyguard-surface lockdowns that close the web-leak surfaces
 * catalogued in DEFENSES.md §21-28.
 *
 * This class is intentionally self-contained and is NOT wired into the boot / enforcement
 * sequence yet. A human-gated issue (#8) will integrate it via PolicyEnforcer.
 * TODO(#8): apply from enforcement sequence.
 *
 * ## DPM API used
 * [DevicePolicyManager.setKeyguardDisabledFeatures] — requires Device Owner.
 * Each flag below is documented with the DEFENSES # it addresses.
 *
 * ## Items with NO DPM API (handled by docs/config — see DEFENSES.md §21-28)
 * - Quick Settings tile teardown: no DPM API; mitigated by removing sensitive tiles via
 *   Settings → Display → Quick Settings (parent playbook).
 * - PiP overlay persistence: no DPM API; mitigated by disabling PiP for individual apps
 *   via AppOps / per-app battery settings in the parent playbook.
 * - Google Assistant invocation from lock screen: no DPM API on AOSP; mitigated by
 *   disabling Assistant (Settings → Apps → Default apps) in the parent playbook.
 * - Quick Tap (Pixel back-tap gesture): no DPM API; mitigated by disabling in
 *   Settings → System → Gestures → Quick Tap.
 */
class LockScreenLockdown(
    private val context: Context,
) {
    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = AdminReceiver.componentName(context)

    /**
     * The combined keyguard-feature disable mask applied by [apply].
     *
     * Flags chosen and rationale:
     *
     * - [DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA]
     *   Closes DEFENSE #21: lock-screen camera shortcut launches Camera → Lens → web search.
     *   Prevents camera launch from keyguard without unlocking the device first.
     *
     * - [DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL]
     *   Closes DEFENSE #22: lock-screen widgets can expose data (clocks, calendar events,
     *   notifications summaries) to an observer without device unlock.
     *
     * - [DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS]
     *   Closes DEFENSE #23: trust agents (e.g., Smart Lock — Bluetooth / on-body detection)
     *   can keep the lock screen bypassed; disabling prevents passive unlock.
     *
     * - [DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS]
     *   Closes DEFENSE #24: notification content visible on the lock screen leaks
     *   incoming messages, OTP codes, and chat previews without PIN entry.
     *
     * KEYGUARD_DISABLE_FEATURES_ALL is intentionally NOT used — it is a catch-all that
     * includes undocumented flags and would disable the PIN/pattern keyguard itself, which
     * is not the intent. We only disable specific lock-screen surfaces.
     */
    val disabledKeyguardFeatures: Int =
        DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA or
            DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL or
            DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS or
            DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS

    /**
     * Applies [disabledKeyguardFeatures] via [DevicePolicyManager.setKeyguardDisabledFeatures].
     *
     * Requires this app to be the Device Owner. Calling this when NOT the Device Owner
     * will throw [IllegalArgumentException] via [require].
     */
    fun apply() {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "LockScreenLockdown.apply() requires Device Owner — app is not DO"
        }
        dpm.setKeyguardDisabledFeatures(admin, disabledKeyguardFeatures)
        Log.i(TAG, "Keyguard features disabled: 0x${disabledKeyguardFeatures.toString(16)}")
    }

    /**
     * Disables the secure camera on the keyguard only — a narrower version for callers
     * that only want to close the Camera→Lens→web leak (DEFENSE #21) without touching
     * notification / widget / trust-agent surfaces.
     *
     * NOTE: this does NOT globally disable the camera. Global camera disable
     * ([DevicePolicyManager.setCameraDisabled]) is a heavier-weight policy decision
     * (affects all apps, not just lock-screen) and is tracked separately as a parent-
     * configurable policy option.
     * TODO: global camera-disable is a separate policy decision — add a policy field and
     *       apply via PolicyEnforcer when parent opts in.
     */
    fun disableCameraOnKeyguardOnly() {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "disableCameraOnKeyguardOnly() requires Device Owner"
        }
        // Merge with whatever flags are already set so we don't accidentally clear others.
        val current = dpm.getKeyguardDisabledFeatures(admin)
        val merged = current or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
        dpm.setKeyguardDisabledFeatures(admin, merged)
        Log.i(TAG, "KEYGUARD_DISABLE_SECURE_CAMERA applied (merged mask: 0x${merged.toString(16)})")
    }

    companion object {
        const val TAG = "OpenWardenLockdown"
    }
}
