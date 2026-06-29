package com.openwarden.child

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Builds the installed-app list for the `/apps` endpoint.
 *
 * METADATA ONLY: package name + human-readable label. No content, no usage times, no in-app
 * data — stalkerware boundary. This is the same surface disclosed under
 * [MonitoredCategory.APP_ALLOWLIST] in the Kid Transparency screen.
 *
 * System-app inclusion: only non-system user-installed apps are returned. System apps are always
 * exempt from the deny-by-default allowlist ([PolicyEnforcer.applyAllowlist]), so they are not
 * meaningful targets for the parent's allowlist editor. Returning only user-installed apps keeps
 * the editor list clean and consistent with what the enforcer actually suspends.
 */
object InstalledAppsHelper {
    /**
     * Pure mapping from a raw package-info list to [AppEntry] instances. Extracted for
     * host-testability: callers inject the list so no live [PackageManager] is needed in tests.
     *
     * @param packages raw list of (packageName, flags, label) triples — [Triple] of
     *   (pkg, appInfoFlags, labelOrNull).
     * @param selfPackage this app's own package name — filtered out of the result.
     * @return [AppEntry] list, user-installed only (non-system, non-self), label defaulting to
     *   the package name when the PM could not resolve it.
     */
    fun mapToEntries(
        packages: List<Triple<String, Int, String?>>,
        selfPackage: String,
    ): List<AppEntry> =
        packages
            .filter { (pkg, flags, _) ->
                pkg != selfPackage && (flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }.map { (pkg, _, label) ->
                AppEntry(packageName = pkg, label = label ?: pkg)
            }.sortedBy { it.label }

    /**
     * Queries [PackageManager] for all installed packages and returns user-installed (non-system)
     * [AppEntry] records, excluding self.
     *
     * Errors fail closed to an empty list so the parent receives a valid (possibly empty) envelope
     * rather than an HTTP 500.
     */
    fun query(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val self = context.packageName
        val raw =
            pm.getInstalledPackages(0).map { info ->
                val flags = info.applicationInfo?.flags ?: 0
                val label =
                    try {
                        val appInfo = pm.getApplicationInfo(info.packageName, PackageManager.GET_META_DATA)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                Triple(info.packageName, flags, label)
            }
        return mapToEntries(raw, self)
    }
}
