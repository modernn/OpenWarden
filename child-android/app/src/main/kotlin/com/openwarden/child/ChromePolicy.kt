package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Manages Chrome enterprise policy via Device-Owner application restrictions.
 *
 * Threat model coverage:
 * - G1 (gds.google.com hidden browser via Play-Services WebView): blocked via [urlBlocklist]
 *   entries for gds.google.com and related Google help/support endpoints.
 * - W1 (translate proxies, archive.org, 12ft.io, data/blob/file URI schemes): blocked via
 *   [urlBlocklist] entries for translate.goog variants, web.archive.org, 12ft.io, and
 *   dangerous URI scheme patterns.
 *
 * Policy is applied via [DevicePolicyManager.setApplicationRestrictions] targeting
 * [CHROME_PKG] ("com.android.chrome"). This requires Device Owner status.
 *
 * Additional Chrome enterprise policies applied alongside the blocklist:
 * - SafeSitesFilterBehavior=1: enables SafeSites filtering (blocks adult content).
 * - IncognitoModeAvailability=1: disables incognito mode entirely.
 * - SafeBrowsingEnabled=true: enables Google Safe Browsing.
 *
 * @see <a href="https://chromeenterprise.google/policies/#URLBlocklist">Chrome URLBlocklist</a>
 * @see docs/adr/009-browser-strategy.md
 * @see docs/ATTACKS.md (G1, W1)
 *
 * TODO(#8): apply from enforcement sequence once provisioning is wired (human-gated)
 */
class ChromePolicy(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = AdminReceiver.componentName(context)

    // Single source of truth for the Chrome URL blocklist entries.
    //
    // Entries follow Chrome enterprise URLBlocklist pattern syntax:
    //   - Bare hostname: blocks the exact host and all subdomains.
    //   - Wildcard prefix "*.example.com": blocks all subdomains of example.com.
    //   - Scheme pattern e.g. "data://*": blocks all URLs with that scheme.
    //
    // G1 mitigations: gds.google.com, support.google.com/help, play.google.com/help
    // W1 mitigations: translate.goog, *.translate.goog, web.archive.org, 12ft.io,
    //                 data://* (data URI), blob://* (blob URI), file://* (local files)
    val urlBlocklist: List<String> = listOf(
        // G1 — Play-Services WebView hidden browser leak endpoints
        "gds.google.com",
        "support.google.com/help",
        "play.google.com/help",
        // W1 — Google Translate proxy
        "translate.goog",
        "*.translate.goog",
        // W1 — Archive / bypass proxies
        "web.archive.org",
        "12ft.io",
        // W1 — Dangerous URI schemes (data-URL exploit, local file access, blob pages)
        "data://*",
        "blob://*",
        "file://*",
    )

    /**
     * Pushes the Chrome enterprise policy bundle to the system via
     * [DevicePolicyManager.setApplicationRestrictions].
     *
     * Must be called when this app is the Device Owner. Throws [IllegalArgumentException]
     * if called outside Device Owner context (fail-closed: better to crash loudly than to
     * silently skip enforcement).
     */
    fun apply() {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "ChromePolicy.apply() called without Device Owner — cannot push Chrome restrictions"
        }

        val bundle = Bundle().apply {
            putStringArray("URLBlocklist", urlBlocklist.toTypedArray())
            // SafeSites level 1 = filter adult content (Chrome enterprise documented value)
            putInt("SafeSitesFilterBehavior", SAFE_SITES_FILTER_ENABLED)
            // 1 = incognito mode disabled
            putInt("IncognitoModeAvailability", INCOGNITO_MODE_DISABLED)
            putBoolean("SafeBrowsingEnabled", true)
        }

        dpm.setApplicationRestrictions(admin, CHROME_PKG, bundle)
        Log.i(TAG, "Chrome enterprise policy applied: ${urlBlocklist.size} URLBlocklist entries")
    }

    companion object {
        const val CHROME_PKG = "com.android.chrome"
        const val TAG = "OpenWardenChromePolicy"

        // Chrome SafeSitesFilterBehavior=1: enable adult-content filtering.
        private const val SAFE_SITES_FILTER_ENABLED = 1

        // Chrome IncognitoModeAvailability=1: incognito not available.
        private const val INCOGNITO_MODE_DISABLED = 1
    }
}
