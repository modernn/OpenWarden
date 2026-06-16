package com.openwarden.child

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "DeviceAdmin enabled")
        PolicyService.start(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i(TAG, "Provisioning complete — Device Owner role active")
        // First-boot policy: lock down the obvious stuff before anything else can happen.
        PolicyEnforcer(context).applyDayOneRestrictions()
        PolicyService.start(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "DeviceAdmin disabled — DO should never reach here without a decommission flow")
    }

    companion object {
        const val TAG = "OpenWardenAdmin"
        fun componentName(context: Context) = ComponentName(context, AdminReceiver::class.java)
    }
}
