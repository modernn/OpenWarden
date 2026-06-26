package com.openwarden.child

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(
        context: Context,
        intent: Intent,
    ) {
        Log.i(TAG, "DeviceAdmin enabled")
        PolicyService.start(context)
    }

    override fun onProfileProvisioningComplete(
        context: Context,
        intent: Intent,
    ) {
        Log.i(TAG, "Provisioning complete — Device Owner role active")
        // First-boot policy: lock down the obvious stuff before anything else can happen.
        // applyDayOneRestrictions() is fail-closed: on a verify gap it attempts lockNow()
        // containment and throws (ADR-020). Catch it so the FGS watchdog still starts and keeps
        // re-asserting; the enforcer has already attempted to lock the device, so swallowing here
        // is fail-closed-but-alive (the watchdog retries on the next tick), not fail-open.
        try {
            PolicyEnforcer(context).applyDayOneRestrictions()
        } catch (e: Exception) {
            Log.e(TAG, "Day-one restriction apply failed at provisioning (lock attempted, watchdog will retry): ${e.message}")
        } finally {
            PolicyService.start(context)
        }
    }

    override fun onDisabled(
        context: Context,
        intent: Intent,
    ) {
        Log.w(TAG, "DeviceAdmin disabled — DO should never reach here without a decommission flow")
    }

    companion object {
        const val TAG = "OpenWardenAdmin"

        fun componentName(context: Context) = ComponentName(context, AdminReceiver::class.java)
    }
}
