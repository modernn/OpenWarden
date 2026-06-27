package com.openwarden.child

/**
 * Single source of truth for every signal OpenWarden collects from this device.
 *
 * Each entry carries a short [title] (used as a UI label and in test assertions)
 * and [plainLanguage] copy aimed at a 9-to-12-year-old reader.
 *
 * TX1 guard: TransparencyActivity renders *every* entry in this enum. If you add
 * a new monitored signal here, the transparency screen automatically lists it.
 * If you add it without adding it here, TransparencyTest.kt will fail.
 */
enum class MonitoredCategory(
    val title: String,
    val plainLanguage: String,
) {
    /**
     * DNS / web-query log.
     * The DPC intercepts DNS lookups via the Always-on VPN / private-DNS channel
     * to enforce category block-lists. The domain names looked up are logged as
     * metadata (never page content, never request bodies).
     */
    DNS_WEB_QUERIES(
        title = "Websites looked up",
        plainLanguage =
            "Your phone tells your parent which website names it looks up " +
                "(like \"youtube.com\"). Your parent never sees what you read or watched, " +
                "only that the name was looked up.",
    ),

    /**
     * App usage (PACKAGE_USAGE_STATS permission).
     * OpenWarden records which apps were open and for how long. It never reads
     * what happened inside an app.
     */
    APP_USAGE(
        title = "Which apps you use",
        plainLanguage =
            "Your parent sees which apps were open and for how long " +
                "(like \"Roblox — 47 minutes\"). They can't see what you did inside the app.",
    ),

    /**
     * Installed-app inventory (QUERY_ALL_PACKAGES permission).
     * PolicyEnforcer calls pm.getInstalledPackages(0) to build the allowlist that
     * decides which apps the child is permitted to open. The full list of installed
     * package names is read and evaluated; this is a distinct signal from APP_USAGE
     * screen-time and must be disclosed separately.
     */
    INSTALLED_APPS(
        title = "Which apps are on your phone",
        plainLanguage =
            "Your parent can see the list of apps installed on your phone, " +
                "so they can choose which ones you're allowed to open.",
    ),

    /**
     * Screen time total (derived from PACKAGE_USAGE_STATS).
     * Aggregate minutes per day, plus per-app totals.
     */
    SCREEN_TIME(
        title = "How long you use your phone",
        plainLanguage =
            "Your parent sees the total minutes your screen was on each day " +
                "and per-app totals. No detail beyond that.",
    ),

    /**
     * Location (ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION permissions).
     * Only sent as an alert when a policy condition is triggered (e.g. unexpected
     * departure from school during school hours, or missed curfew). Not streamed
     * continuously.
     *
     * FIXME(location-impl): keep this plain-language copy in sync with the ACTUAL
     * location-trigger policy when location is implemented (PolicyService). If location
     * ever becomes continuous, this copy must change.
     */
    LOCATION(
        title = "Where you are, sometimes",
        plainLanguage =
            "Your parent can see where you are, but only if something " +
                "unexpected happens — like leaving school during school hours or not being " +
                "home by curfew. Your location is not shared all the time.",
    ),

    /**
     * Heartbeat / battery telemetry sent via the foreground PolicyService.
     * The periodic keep-alive includes device battery level so the parent can
     * tell whether the child's phone is about to die.
     */
    HEARTBEAT_BATTERY(
        title = "Phone battery level",
        plainLanguage =
            "OpenWarden sends your parent a check-in signal so they know " +
                "your phone is on. That signal includes your battery level so they know if " +
                "your phone is about to run out of power.",
    ),

    /**
     * Policy-enforcement events.
     * When an app or website is blocked, OpenWarden logs that it was blocked
     * (app name or domain name + timestamp). No content is logged.
     */
    BLOCK_EVENTS(
        title = "When something is blocked",
        plainLanguage =
            "If OpenWarden blocks an app or website, your parent sees that " +
                "it was blocked and when. They don't see what you were doing inside it.",
    ),
}
