package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Minimal landing UI. v1 will replace with:
 *   - Pairing screen (QR generator) if not yet paired
 *   - "Why am I blocked?" status screen if running
 *   - Recovery phrase verification screen (parent enters phrase to access settings)
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            textSize = 18f
            text = renderStatus()
            setPadding(48, 96, 48, 48)
        }
        setContentView(text)
    }

    private fun renderStatus(): String {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isDO = dpm.isDeviceOwnerApp(packageName)
        val store = PolicyStore(this)
        val active = store.loadActive()
        return buildString {
            appendLine("OpenWarden")
            appendLine("")
            appendLine("Device Owner: ${if (isDO) "YES" else "NO — run dpm set-device-owner"}")
            appendLine("Parent pinned: ${if (store.parentPubkey() != null) "yes" else "no — pair with parent app"}")
            appendLine("Active policy: ${active?.issued_at ?: "none"}")
        }
    }
}
