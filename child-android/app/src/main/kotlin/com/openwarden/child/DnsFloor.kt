package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * The fail-closed DNS floor (ADR-016 / red-team K3). Pins device-wide Private DNS to a **public
 * filtering** resolver over DNS-over-TLS, so any local-resolver outage still resolves through a
 * *filtering* upstream — never the carrier default, never `OFF`, never `OPPORTUNISTIC`.
 *
 * "No filtering" is not a reachable state on a managed child device:
 *  - an unknown / empty / non-filtering requested host resolves to the default filtering host
 *    ([resolveFilteringHost]); and
 *  - the DPC **never** calls `setGlobalPrivateDnsModeOpportunistic` / OFF.
 *
 * The child cannot edit or clear it — [applyFloor] also asserts `DISALLOW_CONFIG_PRIVATE_DNS`.
 * `applyFloor` is idempotent and is re-asserted by [PolicyWatchdog] on boot, connectivity change,
 * and the periodic tick (ADR-021).
 *
 * @param context Device-Owner app context.
 * @param readMode readback seam for the current Private DNS mode — defaults to the real DPM.
 *   Injectable so the fail-closed verify path is testable without depending on the Robolectric
 *   shadow round-tripping `setGlobalPrivateDnsModeSpecifiedHost`.
 * @param readHost readback seam for the current Private DNS host — defaults to the real DPM.
 * @param readPrivateDnsLocked readback seam for whether `DISALLOW_CONFIG_PRIVATE_DNS` is set —
 *   defaults to the DO-authoritative `dpm.getUserRestrictions(admin)`.
 */
class DnsFloor(
    private val context: Context,
    private val readMode: () -> Int = defaultModeReader(context),
    private val readHost: () -> String? = defaultHostReader(context),
    private val readPrivateDnsLocked: () -> Boolean = defaultLockReader(context),
) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = AdminReceiver.componentName(context)

    /**
     * Pin Private DNS to the filtering floor and lock it. [requestedHost] is the parent's
     * choice (from the signed bundle's `private_dns`); it is honored only if it is a known
     * public filtering resolver, else the default filtering host is used ([resolveFilteringHost]).
     *
     * Fail-closed: after setting, verifies the mode is `PROVIDER_HOSTNAME` and the host matches,
     * and throws [DnsFloorException] otherwise — it never returns having left DNS in an
     * unfiltered/OFF/OPPORTUNISTIC state.
     *
     * @throws IllegalArgumentException if this app is not Device Owner.
     * @throws DnsFloorException if the floor is not verifiably pinned to the filtering host.
     */
    fun applyFloor(requestedHost: String?) {
        require(dpm.isDeviceOwnerApp(context.packageName)) {
            "Not Device Owner — cannot pin DNS floor"
        }
        val host = resolveFilteringHost(requestedHost)

        // Lock the toggle so the child cannot edit or clear Private DNS (ADR-016). Don't skip the
        // pin if this throws, but the lock IS part of the fail-closed verify below — a swallowed
        // lock failure cannot pass verifyOrThrow.
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS) }
            .onFailure { Log.e(TAG, "lock DISALLOW_CONFIG_PRIVATE_DNS failed: ${it.message}") }

        val rc = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)
        if (rc != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
            // Do not give up and fall to OFF — log and let verifyOrThrow decide fail-closed.
            // The default host (Cloudflare for Families) is highly available; a persistent error
            // is a real network/serving problem the watchdog keeps retrying.
            Log.e(TAG, "setGlobalPrivateDnsModeSpecifiedHost($host) rc=$rc")
        }
        verifyOrThrow(host)
    }

    /**
     * Throws [DnsFloorException] unless Private DNS is verifiably `PROVIDER_HOSTNAME` pinned to
     * [expectedHost] **and** the `DISALLOW_CONFIG_PRIVATE_DNS` toggle-lock is set. The lock is
     * part of the fail-closed contract: a pinned-but-unlocked floor lets the child change DNS in
     * Settings until the next watchdog tick. Pure check (via the readback seams) — testable.
     */
    fun verifyOrThrow(expectedHost: String) {
        val mode = readMode()
        val host = readHost()?.lowercase()
        val locked = readPrivateDnsLocked()
        if (mode != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME ||
            host != expectedHost ||
            !locked
        ) {
            throw DnsFloorException(mode, host, expectedHost, locked)
        }
    }

    companion object {
        const val TAG = "OpenWardenDnsFloor"

        /** Default filtering floor — Cloudflare for Families (malware + adult), DoT. */
        const val DEFAULT_FILTERING_HOST = "family.cloudflare-dns.com"

        /**
         * Curated **public filtering** DoT resolvers — the only choosable floor hosts (v1).
         * Each blocks **both malware and adult content** (the floor must preserve *adult*
         * filtering on any outage, ADR-016). Malware-only resolvers (e.g. Quad9 `dns.quad9.net`)
         * are deliberately excluded — failing over to them would leave adult content unfiltered
         * behind a "filtering" label. "No filtering" is deliberately not a member.
         *
         * v1 narrows the choosable set to these curated hosts; a non-empty `private_dns` that is
         * not a member is silently mapped to [DEFAULT_FILTERING_HOST] ([resolveFilteringHost]) —
         * fail-closed but a real narrowing vs ADR-016's wider list. NextDNS per-account hosts and
         * parent self-hosted resolvers (which cannot be statically validated as adult-filtering)
         * and a parent-visible "your resolver was overridden" signal are a tracked follow-up.
         */
        val FILTERING_RESOLVERS: Set<String> = setOf(
            DEFAULT_FILTERING_HOST,                  // Cloudflare for Families — malware + adult
            "family-filter-dns.cleanbrowsing.org",   // CleanBrowsing Family — malware + adult
        )

        /**
         * **Fail-closed host selection.** Returns [requested] only if it is a known public
         * filtering resolver; otherwise [DEFAULT_FILTERING_HOST]. `null`, empty, `"off"`,
         * `"opportunistic"`, `127.0.0.1`/localhost, and any non-filtering resolver (e.g.
         * `1.1.1.1`) all resolve to the default filtering host — "no filtering" is never reachable.
         */
        fun resolveFilteringHost(requested: String?): String =
            requested?.trim()?.lowercase()?.takeIf { it in FILTERING_RESOLVERS } ?: DEFAULT_FILTERING_HOST

        private fun defaultModeReader(context: Context): () -> Int {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = AdminReceiver.componentName(context)
            return { dpm.getGlobalPrivateDnsMode(admin) }
        }

        private fun defaultHostReader(context: Context): () -> String? {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = AdminReceiver.componentName(context)
            return { dpm.getGlobalPrivateDnsHost(admin) }
        }

        private fun defaultLockReader(context: Context): () -> Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = AdminReceiver.componentName(context)
            return { dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) }
        }
    }
}

/**
 * Thrown when the DNS floor cannot be verified pinned to the expected filtering host — carries
 * the observed mode + host so the failure is diagnosable in logs.
 */
class DnsFloorException(
    val mode: Int,
    val actualHost: String?,
    val expectedHost: String,
    val locked: Boolean = true,
) : IllegalStateException(
    "Fail-closed: DNS floor not verifiably pinned " +
        "(mode=$mode host=$actualHost expected=$expectedHost locked=$locked)",
)
