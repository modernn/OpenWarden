package com.openwarden.child

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service hosting:
 *   - the Ktor HTTP server (parent control plane)
 *   - the policy watchdog (re-applies cached bundle on tampering attempts)
 *
 * specialUse FGS type per Android 14+ restrictions.
 */
class PolicyService : Service() {

    private var apiServer: ApiServer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "PolicyService start")
        if (apiServer == null) {
            apiServer = ApiServer(this).also { it.start() }
        }
        // Re-assert the Day-One restriction baseline on every tick. Fail-closed: if a
        // restriction was cleared by a tampering attempt, applyDayOneRestrictions() re-applies
        // it; if it cannot verify the full set it locks the device and throws (ADR-020). We
        // catch so the service stays alive to retry on the next tick — the device is already
        // locked by the enforcer, so this is fail-closed-but-alive, not fail-open.
        try {
            PolicyEnforcer(this).applyDayOneRestrictions()
        } catch (e: Exception) {
            Log.e(TAG, "Day-one restriction re-assert failed (device locked, will retry next tick): ${e.message}")
        }
        // Re-apply current cached bundle to recover from any tampering attempt
        try {
            val bundle = PolicyStore(this).loadActive()
            if (bundle != null) {
                PolicyEnforcer(this).applyAllowlist(bundle.policy.allowlist.toSet())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bundle reapply failed: ${e.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        apiServer?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val ch = NotificationChannel(
            CHAN, "OpenWarden", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "OpenWarden is enforcing your parental controls." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)

        return NotificationCompat.Builder(this, CHAN)
            .setContentTitle("OpenWarden active")
            .setContentText("Parental controls are running.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "OpenWardenService"
        private const val CHAN = "openwarden"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, PolicyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
