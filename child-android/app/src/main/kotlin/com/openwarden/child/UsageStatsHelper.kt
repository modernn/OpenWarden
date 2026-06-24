package com.openwarden.child

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.serialization.Serializable

/**
 * Queries [UsageStatsManager] for per-app foreground time over the last 24 hours.
 *
 * METADATA ONLY (ADR-042 D1): each [AppUsageEntry] exposes exactly the package name,
 * an optional human label, and floored foreground minutes. No message, photo, audio,
 * or in-app activity is ever read or transmitted. This is the stalkerware boundary in
 * docs/KID_TRANSPARENCY.md; the already-disclosed [MonitoredCategory.APP_USAGE] /
 * [MonitoredCategory.SCREEN_TIME] categories cover it.
 *
 * Grant on a connected device / emulator (the appops grant the manifest's
 * PACKAGE_USAGE_STATS permission needs):
 *   adb shell appops set com.openwarden.child.debug GET_USAGE_STATS allow
 * (debug builds append the ".debug" applicationId suffix.)
 *
 * No-grant behaviour is build-dependent (ADR-042 D2): release builds return
 * [UsageResult.Unavailable] (honest empty, never fabricated numbers); debug builds
 * return a clearly-labelled [UsageResult.DemoFallback] so #25 dashboard development
 * has something to render before the grant is set.
 */
object UsageStatsHelper {

    private const val TAG = "UsageStatsHelper"
    private const val WINDOW_MS = 24L * 60 * 60 * 1000 // 24 h
    private const val TOP_N = 15

    @Serializable
    data class AppUsageEntry(
        val packageName: String,
        val label: String?,
        val foregroundMinutes: Long,
    )

    sealed class UsageResult {
        /** Real metadata from UsageStatsManager. */
        data class OnDevice(val entries: List<AppUsageEntry>) : UsageResult()

        /** Debug-only, clearly `[DEMO]`-labelled illustrative data, returned when the
         *  PACKAGE_USAGE_STATS appops grant is missing on a debuggable build. */
        data class DemoFallback(val entries: List<AppUsageEntry>) : UsageResult()

        /** Release no-grant: honest empty. Never fabricates numbers (ADR-042 D2). */
        object Unavailable : UsageResult()

        /** Unexpected error — caller serves an empty list, never demo data (ADR-042 D4). */
        data class Error(val message: String) : UsageResult()
    }

    /**
     * @param debuggable whether this is a debuggable build. Defaulted from
     *   [ApplicationInfo.FLAG_DEBUGGABLE] (ADR-042 D3 — no BuildConfig dependency);
     *   injectable so both no-grant branches are tested deterministically.
     */
    fun query(
        context: Context,
        debuggable: Boolean = isDebuggable(context),
    ): UsageResult {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()

            // queryUsageStats is well-supported and correctly shadowed in tests. We aggregate
            // foreground time per package ourselves rather than use the more obscure
            // queryAndAggregateUsageStats whose Robolectric shadow (4.13) is incomplete.
            val statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW_MS, now)

            if (statsList.isNullOrEmpty()) {
                Log.w(TAG, "UsageStatsManager returned empty/null — appops grant likely missing")
                return noGrant(debuggable)
            }

            // Aggregate per-package totals (queryUsageStats can return multiple intervals).
            val aggregated = mutableMapOf<String, Long>()
            for (stat in statsList) {
                aggregated[stat.packageName] =
                    (aggregated[stat.packageName] ?: 0L) + stat.totalTimeInForeground
            }

            val pm = context.packageManager
            val entries = aggregated.entries
                .filter { it.value > 0 }
                .map { (pkg, totalMs) ->
                    val label = try {
                        pm.getApplicationLabel(
                            pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA),
                        ).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                    AppUsageEntry(
                        packageName = pkg,
                        label = label,
                        foregroundMinutes = totalMs / 60_000,
                    )
                }
                .filter { it.foregroundMinutes > 0 }
                .sortedByDescending { it.foregroundMinutes }
                .take(TOP_N)

            if (entries.isEmpty()) {
                Log.w(TAG, "All usage entries had 0 foreground minutes — appops grant likely missing")
                noGrant(debuggable)
            } else {
                UsageResult.OnDevice(entries)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException querying UsageStats — appops grant not given: ${e.message}")
            noGrant(debuggable)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error querying UsageStats: ${e.message}")
            UsageResult.Error(e.message ?: "unknown error")
        }
    }

    /** ADR-042 D2: release fails closed to honest-empty; debug shows labelled demo data. */
    private fun noGrant(debuggable: Boolean): UsageResult =
        if (debuggable) UsageResult.DemoFallback(demoFallback()) else UsageResult.Unavailable

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Illustrative, `[DEMO]`-labelled data for debug builds only (ADR-042 D2). Values are
     * not real; packages are well-known apps so the parent dashboard renders meaningfully.
     * The `[DEMO]` label prefix is asserted by test so it can never read as real data.
     */
    private fun demoFallback(): List<AppUsageEntry> = listOf(
        AppUsageEntry("com.android.chrome", "[DEMO] Chrome", 82),
        AppUsageEntry("com.google.android.youtube", "[DEMO] YouTube", 47),
        AppUsageEntry("com.roblox.client", "[DEMO] Roblox", 35),
        AppUsageEntry("com.instagram.android", "[DEMO] Instagram", 28),
        AppUsageEntry("com.discord", "[DEMO] Discord", 21),
        AppUsageEntry("com.google.android.apps.maps", "[DEMO] Maps", 14),
        AppUsageEntry("com.android.settings", "[DEMO] Settings", 9),
        AppUsageEntry("com.snapchat.android", "[DEMO] Snapchat", 7),
        AppUsageEntry("com.tiktok.android", "[DEMO] TikTok", 5),
        AppUsageEntry("com.netflix.mediaclient", "[DEMO] Netflix", 3),
    )
}
