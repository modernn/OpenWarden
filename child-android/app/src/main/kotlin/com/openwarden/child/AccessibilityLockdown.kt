package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log

/**
 * Locks down third-party accessibility services on the child device.
 *
 * ## Threat model — A1: accessibility-service escape
 * Third-party accessibility services (e.g. screen readers, automation tools, and
 * stalkerware-adjacent "parental" helpers installed by a tech-savvy child) can:
 *   - Programmatically tap "Open" on suspended-app dialogs to escape our allowlist.
 *   - Dismiss our lock-screen or overlay dialogs.
 *   - Bypass Factory Reset Protection prompts.
 *
 * The Device Owner API [DevicePolicyManager.setPermittedAccessibilityServices] lets us
 * restrict which accessibility services are allowed to run.
 *
 * ## emptyList() ≠ null — critical distinction
 * - `null`  → ALL accessibility services are permitted (the default, unrestricted state).
 * - `emptyList()` → NO third-party accessibility services are permitted (our locked state).
 *   OpenWarden's own accessibility service (if any, future work) would need to be added to
 *   this list explicitly.
 *
 * Always pass `emptyList()`, never `null`.
 *
 * // TODO(#8): call from PolicyEnforcer day-one sequence
 */
class AccessibilityLockdown(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = AdminReceiver.componentName(context)

    /**
     * Disables all third-party accessibility services by setting an empty permitted list.
     *
     * Requires this app to be the Device Owner. Will throw [IllegalArgumentException] if not.
     *
     * Also attempts to clear the accessibility shortcut target if a Device-Owner-safe
     * mechanism is available via [DevicePolicyManager]. The `accessibility_shortcut_target_service`
     * secure setting cannot be cleared by a DO app directly (requires WRITE_SECURE_SETTINGS,
     * which is a shell-only permission on API 33+). That limitation is accepted; the shortcut
     * is inert once the service it points to is blocked by [setPermittedAccessibilityServices].
     *
     * // NOTE: shortcut-target clear needs WRITE_SECURE_SETTINGS — documented limitation,
     * //       covered by instrumented test #13
     */
    fun apply() {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "AccessibilityLockdown.apply() requires Device Owner — not currently DO"
        }

        // emptyList() = zero third-party a11y services permitted.
        // null would mean ALL permitted — must NOT pass null here.
        dpm.setPermittedAccessibilityServices(admin, emptyList())
        Log.i(TAG, "Accessibility services locked down: setPermittedAccessibilityServices(emptyList)")

        // Best-effort shortcut clearing via DO-available API.
        // DevicePolicyManager has no direct API to clear accessibility_shortcut_target_service
        // without WRITE_SECURE_SETTINGS, which is unavailable to DO apps (shell-only, API 33+).
        // The shortcut is effectively inert because the service it targets is blocked above.
        // See: https://source.android.com/docs/core/connect/device-identifiers
        // NOTE: shortcut-target clear needs WRITE_SECURE_SETTINGS — documented limitation,
        //       covered by instrumented test #13
        Log.i(TAG, "Note: accessibility_shortcut_target_service cannot be cleared by DO app — inert after service lockdown")
    }

    companion object {
        const val TAG = "OpenWardenA11yLockdown"
    }
}
