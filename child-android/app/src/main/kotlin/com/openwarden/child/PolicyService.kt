package com.openwarden.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service hosting:
 *   - the Ktor HTTP server (parent control plane)
 *   - the [PolicyWatchdog] that re-asserts the full local policy so the child self-heals
 *
 * specialUse FGS type per Android 14+ restrictions.
 *
 * ## Watchdog triggers (ADR-021)
 * The watchdog re-asserts on three triggers so drift is corrected promptly without draining the
 * battery:
 *   1. **Boot / service start** — `onStartCommand` (BootReceiver starts the FGS on boot).
 *   2. **Connectivity change** — a default-network callback (re-assert when the network comes or
 *      goes; the DNS floor in #19 must be re-pinned on connectivity per ADR-016).
 *   3. **Periodic timer** — every [PolicyWatchdog.INTERVAL_MS], bounding how long a silently
 *      cleared restriction can persist before the watchdog reverts it.
 *
 * `START_STICKY` covers the fourth case: if the OS kills the service it is recreated and
 * re-asserts on the next `onStartCommand`.
 */
class PolicyService : Service() {

    private var apiServer: ApiServer? = null
    private lateinit var watchdog: PolicyWatchdog

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogTick = object : Runnable {
        override fun run() {
            watchdog.reassert()
            watchdogHandler.postDelayed(this, PolicyWatchdog.INTERVAL_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "connectivity available — re-asserting policy")
            watchdog.reassert()
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "connectivity lost — re-asserting policy")
            watchdog.reassert()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        watchdog = PolicyWatchdog.forContext(this)

        // Trigger 2: re-assert on every connectivity change (ADR-016 DNS-floor reassert hook).
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
        }.onFailure { Log.e(TAG, "registerDefaultNetworkCallback failed: ${it.message}") }

        // Trigger 3: periodic watchdog tick — runs immediately, then every INTERVAL_MS.
        watchdogHandler.post(watchdogTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "PolicyService start")
        if (apiServer == null) {
            apiServer = ApiServer(this).also { it.start() }
        }
        // Trigger 1: re-assert immediately on (re)start — the boot path lands here via
        // BootReceiver. reassert() is fail-closed-but-alive and never throws (ADR-020/021).
        watchdog.reassert()
        return START_STICKY
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(watchdogTick)
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
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
