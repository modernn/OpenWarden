package com.openwarden.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Boot received: ${intent.action}")
        // Re-apply cached policy + restart FGS.
        PolicyService.start(context)
    }
    companion object { const val TAG = "OpenWardenBoot" }
}
