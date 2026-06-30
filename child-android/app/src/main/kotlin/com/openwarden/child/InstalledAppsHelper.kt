package com.openwarden.child

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Builds the installed-app list for the `/apps` endpoint.
 *
 * METADATA ONLY: package name + human-readable label + category. No content, no usage times,
 * no in-app data — stalkerware boundary. This is the same surface disclosed under
 * [MonitoredCategory.APP_ALLOWLIST] in the Kid Transparency screen.
 *
 * Scope: all packages for which [PackageManager.getLaunchIntentForPackage] returns non-null —
 * i.e. user-facing apps a kid can actually open from the home screen. This covers both
 * user-installed apps and key user-facing system apps (browser, camera, app store, etc.) while
 * excluding pure framework/service packages that have no launcher activity.
 */
object InstalledAppsHelper {
    /**
     * Maps a raw Android [ApplicationInfo.category] integer to an UPPERCASE token string whose
     * values align with the parent's `AppCategory` enum.
     *
     * Pure function — no Android context required, so it is host-unit-testable without
     * Robolectric. [androidCategory] is the value returned by [ApplicationInfo.category].
     */
    internal fun categoryToken(androidCategory: Int): String =
        when (androidCategory) {
            ApplicationInfo.CATEGORY_GAME -> "GAMING"
            ApplicationInfo.CATEGORY_AUDIO -> "ENTERTAINMENT"
            ApplicationInfo.CATEGORY_VIDEO -> "ENTERTAINMENT"
            ApplicationInfo.CATEGORY_IMAGE -> "ENTERTAINMENT"
            ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
            ApplicationInfo.CATEGORY_NEWS -> "NEWS"
            ApplicationInfo.CATEGORY_MAPS -> "UTILITIES"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> "UTILITIES"
            else -> "UNKNOWN" // covers CATEGORY_UNDEFINED (-1) and any future/unknown values
        }

    /**
     * Pure mapping from a raw package-info list to [AppEntry] instances. Extracted for
     * host-testability: callers inject the list so no live [PackageManager] is needed in tests.
     *
     * @param packages raw list of (packageName, label, androidCategory) triples where
     *   [androidCategory] is the [ApplicationInfo.category] int value.
     * @param selfPackage this app's own package name — filtered out of the result.
     * @return [AppEntry] list of all launchable (non-self) packages, sorted by label ascending.
     *   Label defaults to the package name when the PM could not resolve it.
     */
    fun mapToEntries(
        packages: List<Triple<String, String?, Int>>,
        selfPackage: String,
    ): List<AppEntry> =
        packages
            .filter { (pkg, _, _) -> pkg != selfPackage }
            .map { (pkg, label, androidCategory) ->
                AppEntry(
                    packageName = pkg,
                    label = label ?: pkg,
                    category = categoryToken(androidCategory),
                )
            }.sortedBy { it.label }

    /**
     * Queries [PackageManager] for all launchable packages and returns [AppEntry] records,
     * excluding self.
     *
     * Inclusion rule: a package is included iff [PackageManager.getLaunchIntentForPackage]
     * returns non-null — user-facing apps a kid can actually open from the home screen. This
     * covers user-installed apps and key system apps (browser, camera, app store, etc.) while
     * silently dropping pure framework/service packages.
     *
     * Errors fail closed to an empty list so the parent receives a valid (possibly empty) envelope
     * rather than an HTTP 500.
     */
    fun query(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val self = context.packageName
        val raw =
            pm.getInstalledPackages(0).mapNotNull { info ->
                val pkg = info.packageName
                // Include only packages launchable from the home screen — user-facing apps a kid
                // can actually open. getLaunchIntentForPackage returns null for pure
                // framework/service packages, so they are excluded without needing a FLAG_SYSTEM
                // check.
                if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
                val androidCategory = info.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED
                val label =
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                Triple(pkg, label, androidCategory)
            }
        return mapToEntries(raw, self)
    }
}
